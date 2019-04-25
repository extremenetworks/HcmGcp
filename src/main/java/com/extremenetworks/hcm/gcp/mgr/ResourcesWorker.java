package com.extremenetworks.hcm.gcp.mgr;

import java.io.ByteArrayOutputStream;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.services.compute.model.Firewall;
import com.google.api.services.compute.model.Instance;
import com.google.api.services.compute.model.NetworkInterface;
import com.google.api.services.compute.model.Region;
import com.google.api.services.compute.model.Subnetwork;
import com.google.api.services.compute.model.Zone;
import com.google.api.services.compute.model.ZoneList;
import com.rabbitmq.client.Channel;

public class ResourcesWorker implements Runnable {

	private static final Logger logger = LogManager.getLogger(ResourcesWorker.class);
	private static ObjectMapper jsonMapper = new ObjectMapper();

	// GCP config
	private String projectId;
	private String authenticationFileName;

	// Rabbit MQ config
	private String RABBIT_QUEUE_NAME;
	private Channel rabbitChannel;

	// DB config
	private final String dbConnString = "jdbc:mysql://hcm-mysql:3306/Resources?useSSL=false";
	private final String dbUser = "root";
	private final String dbPassword = "password";

	// Helpers / Utilities
	private static final JsonFactory jsonFactory = new JsonFactory();
	private final SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
	private final String SRC_SYS_TYPE = "GCP";

	public ResourcesWorker(String projectId, String authenticationFileName, String RABBIT_QUEUE_NAME,
			Channel rabbitChannel) {

		this.projectId = projectId;
		this.authenticationFileName = authenticationFileName;

		this.RABBIT_QUEUE_NAME = RABBIT_QUEUE_NAME;
		this.rabbitChannel = rabbitChannel;

		try {
			// load and register JDBC driver for MySQL
			Class.forName("com.mysql.jdbc.Driver");
		} catch (Exception ex) {
			logger.error("Error loading mysql jdbc driver within the worker class", ex);
		}
	}

	@Override
	public void run() {

		logger.debug("Starting Background worker to import compute data from GCP for project with ID " + projectId);

		try {
			GoogleComputeEngineManager computeManager = new GoogleComputeEngineManager();
			boolean connected = computeManager.createComputeConnection(projectId, authenticationFileName);

			if (!connected) {
				String msg = "Won't be able to retrieve any data from Google Compute Engine since no authentication/authorization/connection could be established";
				logger.error(msg);
				rabbitChannel.basicPublish("", RABBIT_QUEUE_NAME, null, msg.getBytes("UTF-8"));
				return;
			}

			java.sql.Connection dbConn = DriverManager.getConnection(dbConnString, dbUser, dbPassword);

			/* Zones */
			List<Object> allZones = computeManager.retrieveAllZones(projectId);
			if (allZones == null || allZones.isEmpty()) {
				String msg = "Error retrieving zones from GCP - stopping any further processing";
				logger.warn(msg);
				rabbitChannel.basicPublish("", RABBIT_QUEUE_NAME, null, msg.getBytes("UTF-8"));
				return;
			}

			writeToDb(dbConn, "Zone", allZones);
			publishBasicDataToRabbitMQ("Zone", allZones);
//			publishToRabbitMQ("Zone", allZones);

			/* Regions */
			List<Object> allRegions = computeManager.retrieveAllRegions(projectId);
			if (allZones == null || allZones.isEmpty()) {
				String msg = "Error retrieving zones from GCP - stopping any further processing";
				logger.warn(msg);
				rabbitChannel.basicPublish("", RABBIT_QUEUE_NAME, null, msg.getBytes("UTF-8"));
				return;
			}

			writeToDb(dbConn, "Region", allRegions);
			publishBasicDataToRabbitMQ("Region", allRegions);
//			publishToRabbitMQ("Region", allRegions);

			/* Instances */
			if (allZones != null && !allZones.isEmpty()) {

				List<Object> allInstances = new ArrayList<Object>();

				for (Object zoneGeneric : allZones) {

					Zone zone = (Zone) zoneGeneric;
					List<Object> instancesFromZone = computeManager.retrieveInstancesForZone(projectId, zone.getName());
					if (instancesFromZone == null) {
						String msg = "Error retrieving instances from GCP zone " + zone.getName()
								+ " - stopping any further processing";
						logger.warn(msg);
						rabbitChannel.basicPublish("", RABBIT_QUEUE_NAME, null, msg.getBytes("UTF-8"));
						return;
					}

//					logger.debug("Found " + instancesFromZone.size() + " instances from zone " + zone);
					allInstances.addAll(instancesFromZone);
				}

				writeToDb(dbConn, "VM", allInstances);
				publishBasicDataToRabbitMQ("VM", allInstances);
			}

			/* Subnets */
			if (allRegions != null && !allRegions.isEmpty()) {

				List<Object> allSubnets = new ArrayList<Object>();

				for (Object regionGeneric : allRegions) {

					Region region = (Region) regionGeneric;
					List<Object> subnetsFromRegion = computeManager.retrieveSubnetworksForRegion(projectId,
							region.getName());
					if (subnetsFromRegion == null) {
						String msg = "Error retrieving subnets from GCP region " + region.getName()
								+ " - stopping any further processing";
						logger.warn(msg);
						rabbitChannel.basicPublish("", RABBIT_QUEUE_NAME, null, msg.getBytes("UTF-8"));
						return;
					}

					allSubnets.addAll(subnetsFromRegion);
				}

				writeToDb(dbConn, "Subnet", allSubnets);
				publishBasicDataToRabbitMQ("Subnet", allSubnets);
//				publishToRabbitMQ("Subnet", allSubnets);
			}

			/* Firewalls */
			List<Object> allFirewalls = computeManager.retrieveFirewalls(projectId, "", false);
			if (allFirewalls == null || allFirewalls.isEmpty()) {
				String msg = "Error retrieving firewalls from GCP - stopping any further processing";
				logger.warn(msg);
				rabbitChannel.basicPublish("", RABBIT_QUEUE_NAME, null, msg.getBytes("UTF-8"));
				return;
			}

			writeToDb(dbConn, "Firewall", allFirewalls);
			publishBasicDataToRabbitMQ("Firewall", allFirewalls);
//			publishToRabbitMQ("Firewall", allFirewalls);

			logger.debug("Finished retrieving all resources from GCP project " + projectId);

		} catch (Exception ex) {
			logger.error(ex);
			return;
		}
	}

	/**
	 * Writes the given data (Subnets, VMs, etc.) to the DB
	 * 
	 * @param dbConn       Active DB connection
	 * @param resourceType Valid types: Subnet, VM,
	 * @param data         Map of resource data. The values can contain any type of
	 *                     object (subnets, VMs, etc.) and will be written to JSON
	 *                     data and then stored in the DB
	 * @return
	 */
	private boolean writeToDb(java.sql.Connection dbConn, String resourceType, List<Object> data) {

		try {
			String sqlInsertStmtSubnets = "INSERT INTO gcp (lastUpdated, projectId, resourceType, resourceData) "
					+ "VALUES (NOW(), ?, ?, ?) " + "ON DUPLICATE KEY UPDATE lastUpdated=NOW(), resourceData=?";

			PreparedStatement prepInsertStmtSubnets = dbConn.prepareStatement(sqlInsertStmtSubnets);

			prepInsertStmtSubnets.setString(1, projectId);
			prepInsertStmtSubnets.setString(2, resourceType);
			prepInsertStmtSubnets.setString(3, jsonMapper.writeValueAsString(data));
			prepInsertStmtSubnets.setString(4, jsonMapper.writeValueAsString(data));

			prepInsertStmtSubnets.executeUpdate();
			return true;

		} catch (Exception ex) {
			logger.error("Error trying to store resource data within the DB", ex);
			return false;
		}
	}

	private boolean publishToRabbitMQ(String resourceType, List<Object> data) {

		try {
			ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
			JsonGenerator jsonGen = jsonFactory.createGenerator(outputStream, JsonEncoding.UTF8);

			jsonGen.writeStartObject();

			jsonGen.writeStringField("dataType", "resources");
			jsonGen.writeStringField("sourceSystemType", "gcp");
			jsonGen.writeStringField("sourceSystemProjectId", projectId);

			jsonGen.writeArrayFieldStart("data");

			Date now = new Date();

			jsonGen.writeStartObject();

			jsonGen.writeStringField("lastUpdated", dateFormatter.format(now));
			jsonGen.writeStringField("resourceType", resourceType);
			jsonGen.writeFieldName("resourceData");

			jsonGen.writeStartArray();
			jsonGen.writeRawValue(jsonMapper.writeValueAsString(data));
			jsonGen.writeEndArray();

			jsonGen.writeEndObject();

			jsonGen.writeEndArray();
			jsonGen.writeEndObject();

			jsonGen.close();
			outputStream.close();

			logger.debug("Forwarding updated list of " + resourceType + "s to the message queue " + RABBIT_QUEUE_NAME); 
			rabbitChannel.basicPublish("", RABBIT_QUEUE_NAME, null, outputStream.toString().getBytes("UTF-8"));

			return true;

		} catch (Exception ex) {
			logger.error("Error trying to publish resource data to RabbitMQ", ex);
			return false;
		}

	}
	

	private boolean publishBasicDataToRabbitMQ(String resourceType, List<Object> data) {

		if (data == null || data.isEmpty()) {	return false;	}
		
		try {
			Date now = new Date();
			String lastUpdate = dateFormatter.format(now);
			
			ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
			JsonGenerator jsonGen = jsonFactory.createGenerator(outputStream, JsonEncoding.UTF8);

			jsonGen.writeStartArray();
			
			if (resourceType.equals("VM")) {
				
				List<Instance> vms = (List<Instance>)(List<?>) data;
				
				for (Instance vm: vms) {
					
					jsonGen.writeStartObject();
					
					// Extra Data field
					String machineType = vm.getMachineType().substring(vm.getMachineType().indexOf("/machineTypes/") + "/machineTypes/".length());
					String zone = vm.getZone().substring(vm.getZone().indexOf("/zones/") + "/zones/".length());
//					String extraData = "Type: " + machineType + ", Status: " + vm.getStatus() + ", Zone: " + zone;
							
					jsonGen.writeStringField("name", vm.getName());
					jsonGen.writeStringField("srcSysType", SRC_SYS_TYPE);
					jsonGen.writeStringField("resourceType", resourceType);
//					jsonGen.writeStringField("extraData", extraData);
					jsonGen.writeStringField("id", vm.getId().toString());
					jsonGen.writeStringField("lastUpdate", lastUpdate);
					
					jsonGen.writeArrayFieldStart("details");
					jsonGen.writeString("Machine Type: " + machineType);
					jsonGen.writeString("Zone: " + zone);
					jsonGen.writeString("Status: " + vm.getStatus());

					// Network Interfaces
					if (vm.getNetworkInterfaces() != null && !vm.getNetworkInterfaces().isEmpty()) {
					
						String nwInterfaces = "NW Interfaces: ";
						boolean isFirst = true;
						
						for (NetworkInterface nic: vm.getNetworkInterfaces()) {
							if (isFirst) {
								nwInterfaces += "Name: " + nic.getName() + ", int IP: " + nic.getNetworkIP();
								isFirst = false;
							} else {
								nwInterfaces += "; Name: " + nic.getName() + ", int IP: " + nic.getNetworkIP();
							}
							
							if (nic.getAccessConfigs() != null && !nic.getAccessConfigs().isEmpty()) {
								nwInterfaces += ", ext IP: " + nic.getAccessConfigs().get(0).getNatIP();
							}
						}
						jsonGen.writeString(nwInterfaces);
					}
					
					// Tags
					if (vm.getTags() != null && vm.getTags().getItems() != null && !vm.getTags().getItems().isEmpty()) {
						String tags = "";
						for (String tag: vm.getTags().getItems()) {
							tags += tag + ",";
						}
						
						tags = tags.substring(0, tags.length() - 1);
						jsonGen.writeString("Tags: " + tags);
					}
					
					jsonGen.writeEndArray();
					
					jsonGen.writeEndObject();
				};
			}			

			else if (resourceType.equals("Firewall")) {
				
				List<Firewall> firewalls = (List<Firewall>)(List<?>) data;
				
				for (Firewall fw: firewalls) {
					
					jsonGen.writeStartObject();
					
					// Extra Data field
					String network = fw.getNetwork().substring(fw.getNetwork().indexOf("/networks/") + "/networks/".length());
//					String extraData = "Description: " + fw.getDescription() + ", Network: " + network + ", Direction: " + fw.getDirection();
					
					jsonGen.writeStringField("name", fw.getName());
					jsonGen.writeStringField("srcSysType", SRC_SYS_TYPE);
					jsonGen.writeStringField("resourceType", resourceType);
//					jsonGen.writeStringField("extraData", extraData);
					jsonGen.writeStringField("id", fw.getId().toString());
					jsonGen.writeStringField("lastUpdate", lastUpdate);

					jsonGen.writeArrayFieldStart("details");
					jsonGen.writeString("Direction: " + fw.getDirection());
					
					// Allowed or denied protocols and ports
					if (fw.getAllowed() != null && !fw.getAllowed().isEmpty()) {
						
						String allowed;
						
						if (fw.getAllowed().get(0).getIPProtocol() != null) {
							allowed = "Proto: " + fw.getAllowed().get(0).getIPProtocol();
						} else {
							allowed = "Proto: any";
						}
						 
						if (fw.getAllowed().get(0).getPorts() != null && !fw.getAllowed().get(0).getPorts().isEmpty()) {
							allowed +=  ", Ports: ";
							for (String port: fw.getAllowed().get(0).getPorts()) {
								allowed += port + ",";
							}
						} else {
							allowed += ", Ports: any ";
						}
						
						allowed = allowed.substring(0, allowed.length() - 1);
						
						jsonGen.writeString("Allowed: " + allowed);
						
					} else {

						String denied;
						
						if (fw.getDenied().get(0).getIPProtocol() != null) {
							denied = "Proto: " + fw.getDenied().get(0).getIPProtocol();
						} else {
							denied = "Proto: any";
						}
						 
						if (fw.getDenied().get(0).getPorts() != null && !fw.getDenied().get(0).getPorts().isEmpty()) {
							denied +=  ", Ports: ";
							for (String port: fw.getDenied().get(0).getPorts()) {
								denied += port + ",";
							}
						} else {
							denied += ", Ports: any ";
						}
						
						denied = denied.substring(0, denied.length() - 1);
						
						jsonGen.writeString("Denied: " + denied);
					}
					
					// Source Filters: IP Ranges
					if (fw.getSourceRanges() != null && !fw.getSourceRanges().isEmpty()) {
						String srcFilters = "";
						for (String srcRange: fw.getSourceRanges()) {
							srcFilters += srcRange + ",";
						}
						
						srcFilters = srcFilters.substring(0, srcFilters.length() - 1);
						jsonGen.writeString("Source Filters: IP Ranges: " + srcFilters);
					}
					
					// Target Tag
					if (fw.getTargetTags() != null && !fw.getTargetTags().isEmpty()) {
						String targetTags = "";
						for (String targetTag: fw.getTargetTags()) {
							targetTags += targetTag + ",";
						}
						
						targetTags = targetTags.substring(0, targetTags.length() - 1);
						jsonGen.writeString("Target Tags: " + targetTags);
					}
					
					jsonGen.writeString("Description: " + fw.getDescription());
					jsonGen.writeString("Network: " + network);
					
					jsonGen.writeEndArray();
					
					jsonGen.writeEndObject();
				};
			}			

			else if (resourceType.equals("Subnet")) {
				
				List<Subnetwork> subnets = (List<Subnetwork>)(List<?>) data;
				
				for (Subnetwork subnet: subnets) {
					
					jsonGen.writeStartObject();

					// Extra Data field
					String region = subnet.getRegion().substring(subnet.getRegion().indexOf("/regions/") + "/regions/".length());
					String network = subnet.getNetwork().substring(subnet.getNetwork().indexOf("/networks/") + "/networks/".length());
					String extraData = "Gateway: " + subnet.getGatewayAddress() + ", CIDR: " + subnet.getIpCidrRange() + ", Network: " + network + ", Region: " + region;
					
					jsonGen.writeStringField("name", subnet.getName());
					jsonGen.writeStringField("srcSysType", SRC_SYS_TYPE);
					jsonGen.writeStringField("resourceType", resourceType);
					jsonGen.writeStringField("extraData", extraData);
					jsonGen.writeStringField("id", subnet.getId().toString());
					jsonGen.writeStringField("lastUpdate", lastUpdate);
					
					jsonGen.writeEndObject();
				};
			}			

			else if (resourceType.equals("Zone")) {
				
				List<Zone> zones = (List<Zone>)(List<?>) data;
				
				for (Zone zone: zones) {
					
					jsonGen.writeStartObject();

					// Extra Data field
					String region = zone.getRegion().substring(zone.getRegion().indexOf("/regions/") + "/regions/".length());
					String extraData = "Status: " + zone.getStatus() + ", Region: " + region;
					
					jsonGen.writeStringField("name", zone.getName());
					jsonGen.writeStringField("srcSysType", SRC_SYS_TYPE);
					jsonGen.writeStringField("resourceType", resourceType);
					jsonGen.writeStringField("extraData", extraData);
					jsonGen.writeStringField("id", zone.getId().toString());
					jsonGen.writeStringField("lastUpdate", lastUpdate);
					
					jsonGen.writeEndObject();
				};
			}			

			else if (resourceType.equals("Region")) {
				
				List<Region> regions = (List<Region>)(List<?>) data;
				
				for (Region region: regions) {
					
					jsonGen.writeStartObject();

					jsonGen.writeStringField("name", region.getName());
					jsonGen.writeStringField("srcSysType", SRC_SYS_TYPE);
					jsonGen.writeStringField("resourceType", resourceType);
					jsonGen.writeStringField("extraData", "");
					jsonGen.writeStringField("id", region.getId().toString());
					jsonGen.writeStringField("lastUpdate", lastUpdate);
					
					jsonGen.writeEndObject();
				};
			}			

			jsonGen.writeEndArray();
			
			jsonGen.close();
			outputStream.close();

			logger.debug("Forwarding updated list of " + resourceType + "s to the message queue " + RABBIT_QUEUE_NAME); 
			rabbitChannel.basicPublish("", RABBIT_QUEUE_NAME, null, outputStream.toString().getBytes("UTF-8"));

			return true;

		} catch (Exception ex) {
			logger.error("Error trying to publish resource data to RabbitMQ", ex);
			return false;
		}

	}
}
