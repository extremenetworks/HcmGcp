package com.extremenetworks.hcm.gcp.mgr;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.services.compute.model.Firewall;
import com.google.api.services.compute.model.Instance;
import com.google.api.services.compute.model.Network;
import com.google.api.services.compute.model.NetworkInterface;
import com.google.api.services.compute.model.Region;
import com.google.api.services.compute.model.Subnetwork;
import com.google.api.services.compute.model.Zone;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.Timestamp;
import com.google.cloud.datastore.Blob;
import com.google.cloud.datastore.BlobValue;
import com.google.cloud.datastore.Datastore;
import com.google.cloud.datastore.DatastoreOptions;
import com.google.cloud.datastore.Entity;
import com.google.cloud.datastore.Key;
import com.rabbitmq.client.Channel;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ResourcesWorker implements Runnable {

	private static final Logger logger = LogManager.getLogger(ResourcesWorker.class);
	private static ObjectMapper jsonMapper = new ObjectMapper();

	// GCP config
	private String projectId;
	private String authFileContent;

	// Rabbit MQ config
	private String RABBIT_QUEUE_NAME;
	private Channel rabbitChannel;

	// DB config
	// private final String dbConnString =
	// "jdbc:mysql://hcm-mysql:3306/Resources?useSSL=false";
	// private final String dbUser = "root";
	// private final String dbPassword = "password";

	// Helpers / Utilities
	private static final JsonFactory jsonFactory = new JsonFactory();
	private final SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
	private final String SRC_SYS_TYPE = "GCP";
	private final String GCP_DATASTORE_ENTITY_KIND = "GCP_Resources";

	private enum RESOURCE_TYPES {
		VM, Firewall, Network, Subnet, Region, Zone
	}

	public ResourcesWorker(String projectId, String authFileContent, String RABBIT_QUEUE_NAME, Channel rabbitChannel) {

		this.projectId = projectId;
		this.authFileContent = authFileContent;

		this.RABBIT_QUEUE_NAME = RABBIT_QUEUE_NAME;
		this.rabbitChannel = rabbitChannel;

		// try {
		// // load and register JDBC driver for MySQL
		// Class.forName("com.mysql.jdbc.Driver");
		// } catch (Exception ex) {
		// logger.error("Error loading mysql jdbc driver within the worker class", ex);
		// }
	}

	@Override
	public void run() {

		logger.debug("Starting Background worker to import compute data from GCP for project with ID " + projectId);

		try {
			GoogleComputeEngineManager computeManager = new GoogleComputeEngineManager();
			boolean connected = computeManager.createComputeConnection(projectId, authFileContent);

			if (!connected) {
				String msg = "Won't be able to retrieve any data from Google Compute Engine since no authentication/authorization/connection could be established";
				logger.error(msg);
				rabbitChannel.basicPublish("", RABBIT_QUEUE_NAME, null, msg.getBytes("UTF-8"));
				return;
			}

			/* Load the JSON credentials file content */
			GoogleCredentials gcpCredentials = null;

			try {
				InputStream credFileInputStream = new ByteArrayInputStream(
						authFileContent.getBytes(StandardCharsets.UTF_8));
				gcpCredentials = GoogleCredentials.fromStream(credFileInputStream);

			} catch (Exception ex) {
				logger.error("Error loading the credentials JSON file content for authorizing against the GCP project "
						+ projectId, ex);
				return;
			}

			Datastore datastore;

			try {
				datastore = DatastoreOptions.newBuilder().setCredentials(gcpCredentials).setProjectId(projectId).build()
						.getService();

			} catch (Exception e) {
				logger.error("Error while trying to setup the 'compute engine' connection for project " + projectId, e);
				return;
			}

			/* Zones */
			List<Object> allZones = computeManager.retrieveAllZones(projectId);
			if (allZones == null || allZones.isEmpty()) {
				String msg = "Error retrieving zones from GCP - stopping any further processing";
				logger.warn(msg);
				rabbitChannel.basicPublish("", RABBIT_QUEUE_NAME, null, msg.getBytes("UTF-8"));
				return;
			}

			writeToDb(datastore, RESOURCE_TYPES.Zone, allZones);
			publishBasicDataToRabbitMQ(RESOURCE_TYPES.Zone, allZones);

			/* Regions */
			List<Object> allRegions = computeManager.retrieveAllRegions(projectId);
			if (allZones == null || allZones.isEmpty()) {
				String msg = "Error retrieving zones from GCP - stopping any further processing";
				logger.warn(msg);
				rabbitChannel.basicPublish("", RABBIT_QUEUE_NAME, null, msg.getBytes("UTF-8"));
				return;
			}

			writeToDb(datastore, RESOURCE_TYPES.Region, allRegions);
			publishBasicDataToRabbitMQ(RESOURCE_TYPES.Region, allRegions);

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

					allInstances.addAll(instancesFromZone);
				}

				writeToDb(datastore, RESOURCE_TYPES.VM, allInstances);
				publishBasicDataToRabbitMQ(RESOURCE_TYPES.VM, allInstances);
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

				writeToDb(datastore, RESOURCE_TYPES.Subnet, allSubnets);
				publishBasicDataToRabbitMQ(RESOURCE_TYPES.Subnet, allSubnets);
			}

			/* Firewalls */
			List<Object> allFirewalls = computeManager.retrieveFirewalls(projectId, "", false);
			if (allFirewalls == null || allFirewalls.isEmpty()) {
				String msg = "Error retrieving firewalls from GCP - stopping any further processing";
				logger.warn(msg);
				rabbitChannel.basicPublish("", RABBIT_QUEUE_NAME, null, msg.getBytes("UTF-8"));
				return;
			}

			writeToDb(datastore, RESOURCE_TYPES.Firewall, allFirewalls);
			publishBasicDataToRabbitMQ(RESOURCE_TYPES.Firewall, allFirewalls);

			/* Networks */
			List<Object> allNetworks = computeManager.retrieveAllNetworks(projectId);
			if (allNetworks == null || allNetworks.isEmpty()) {
				String msg = "Error retrieving networks from GCP - stopping any further processing";
				logger.warn(msg);
				rabbitChannel.basicPublish("", RABBIT_QUEUE_NAME, null, msg.getBytes("UTF-8"));
				return;
			}

			writeToDb(datastore, RESOURCE_TYPES.Network, allNetworks);
			publishBasicDataToRabbitMQ(RESOURCE_TYPES.Network, allNetworks);

			logger.debug("Finished retrieving all resources from GCP project " + projectId);

		} catch (Exception ex) {
			logger.error(ex);
			return;
		}
	}

	/**
	 * Writes the given data (Subnets, VMs, etc.) to the DB
	 * 
	 * @param datastore    Active GCP Datastore connection
	 * @param resourceType Valid types: Subnet, VM, etc.
	 * @param data         Map of resource data. The values can contain any type of
	 *                     object (subnets, VMs, etc.) and will be written to JSON
	 *                     data (and then to a Blob) and then stored in the DB
	 * @return
	 */
	private boolean writeToDb(Datastore datastore, RESOURCE_TYPES resourceType, List<Object> data) {

		try {
			// The name/ID for the new entity
			String name = resourceType.name();

			// The Cloud Datastore key for the new entity
			Key entityKey = datastore.newKeyFactory().setKind(GCP_DATASTORE_ENTITY_KIND).newKey(name);

			// The resource data can be quite large - have to use a Blob for this to work
			// (instead of string)
			Blob resourceDataAsBlob = Blob.copyFrom(jsonMapper.writeValueAsString(data).getBytes());

			// Prepare the new entity
			Entity dataEntity = Entity.newBuilder(entityKey).set("lastUpdated", Timestamp.now())
					.set("projectId", projectId).set("resourceType", resourceType.name())
					.set("resourceData", BlobValue.newBuilder(resourceDataAsBlob).setExcludeFromIndexes(true).build())
					.build();

			logger.debug("About to update / write this entity towards GCP datastore:"
					+ jsonMapper.writeValueAsString(dataEntity));

			// Saves the entity
			datastore.put(dataEntity);

			// // Retrieve entity
			// Entity retrieved = datastore.get(taskKey);

			// InputStream resourceDataIs =
			// retrieved.getBlob("resourceData").asInputStream();

			// ByteArrayOutputStream result = new ByteArrayOutputStream();
			// byte[] buffer = new byte[1024];
			// int length;
			// while ((length = resourceDataIs.read(buffer)) != -1) {
			// result.write(buffer, 0, length);
			// }

			// System.out.printf("Retrieved " + taskKey.getName() + ": " +
			// retrieved.getString("resourceType")
			// + " - data: " + result.toString(StandardCharsets.UTF_8.name()));

			return true;

		} catch (Exception ex) {
			logger.error("Error trying to store resource data within GCP Datastore", ex);
			return false;
		}
	}

	// @Override
	// public void run() {
	//
	// logger.debug("Starting Background worker to import compute data from GCP for
	// project with ID " + projectId);
	//
	// try {
	// GoogleComputeEngineManager computeManager = new GoogleComputeEngineManager();
	// boolean connected = computeManager.createComputeConnection(projectId,
	// authenticationFileName);
	//
	// if (!connected) {
	// String msg = "Won't be able to retrieve any data from Google Compute Engine
	// since no authentication/authorization/connection could be established";
	// logger.error(msg);
	// rabbitChannel.basicPublish("", RABBIT_QUEUE_NAME, null,
	// msg.getBytes("UTF-8"));
	// return;
	// }
	//
	// java.sql.Connection dbConn = DriverManager.getConnection(dbConnString,
	// dbUser, dbPassword);
	//
	// /* Zones */
	// List<Object> allZones = computeManager.retrieveAllZones(projectId);
	// if (allZones == null || allZones.isEmpty()) {
	// String msg = "Error retrieving zones from GCP - stopping any further
	// processing";
	// logger.warn(msg);
	// rabbitChannel.basicPublish("", RABBIT_QUEUE_NAME, null,
	// msg.getBytes("UTF-8"));
	// return;
	// }
	//
	// writeToDb(dbConn, RESOURCE_TYPES.Zone, allZones);
	// publishBasicDataToRabbitMQ(RESOURCE_TYPES.Zone, allZones);
	//// publishToRabbitMQ("Zone", allZones);
	//
	// /* Regions */
	// List<Object> allRegions = computeManager.retrieveAllRegions(projectId);
	// if (allZones == null || allZones.isEmpty()) {
	// String msg = "Error retrieving zones from GCP - stopping any further
	// processing";
	// logger.warn(msg);
	// rabbitChannel.basicPublish("", RABBIT_QUEUE_NAME, null,
	// msg.getBytes("UTF-8"));
	// return;
	// }
	//
	// writeToDb(dbConn, RESOURCE_TYPES.Region, allRegions);
	// publishBasicDataToRabbitMQ(RESOURCE_TYPES.Region, allRegions);
	//// publishToRabbitMQ("Region", allRegions);
	//
	// /* Instances */
	// if (allZones != null && !allZones.isEmpty()) {
	//
	// List<Object> allInstances = new ArrayList<Object>();
	//
	// for (Object zoneGeneric : allZones) {
	//
	// Zone zone = (Zone) zoneGeneric;
	// List<Object> instancesFromZone =
	// computeManager.retrieveInstancesForZone(projectId, zone.getName());
	// if (instancesFromZone == null) {
	// String msg = "Error retrieving instances from GCP zone " + zone.getName()
	// + " - stopping any further processing";
	// logger.warn(msg);
	// rabbitChannel.basicPublish("", RABBIT_QUEUE_NAME, null,
	// msg.getBytes("UTF-8"));
	// return;
	// }
	//
	//// logger.debug("Found " + instancesFromZone.size() + " instances from zone "
	// + zone);
	// allInstances.addAll(instancesFromZone);
	// }
	//
	// writeToDb(dbConn, RESOURCE_TYPES.VM, allInstances);
	// publishBasicDataToRabbitMQ(RESOURCE_TYPES.VM, allInstances);
	// }
	//
	// /* Subnets */
	// if (allRegions != null && !allRegions.isEmpty()) {
	//
	// List<Object> allSubnets = new ArrayList<Object>();
	//
	// for (Object regionGeneric : allRegions) {
	//
	// Region region = (Region) regionGeneric;
	// List<Object> subnetsFromRegion =
	// computeManager.retrieveSubnetworksForRegion(projectId,
	// region.getName());
	// if (subnetsFromRegion == null) {
	// String msg = "Error retrieving subnets from GCP region " + region.getName()
	// + " - stopping any further processing";
	// logger.warn(msg);
	// rabbitChannel.basicPublish("", RABBIT_QUEUE_NAME, null,
	// msg.getBytes("UTF-8"));
	// return;
	// }
	//
	// allSubnets.addAll(subnetsFromRegion);
	// }
	//
	// writeToDb(dbConn, RESOURCE_TYPES.Subnet, allSubnets);
	// publishBasicDataToRabbitMQ(RESOURCE_TYPES.Subnet, allSubnets);
	//// publishToRabbitMQ("Subnet", allSubnets);
	// }
	//
	// /* Firewalls */
	// List<Object> allFirewalls = computeManager.retrieveFirewalls(projectId, "",
	// false);
	// if (allFirewalls == null || allFirewalls.isEmpty()) {
	// String msg = "Error retrieving firewalls from GCP - stopping any further
	// processing";
	// logger.warn(msg);
	// rabbitChannel.basicPublish("", RABBIT_QUEUE_NAME, null,
	// msg.getBytes("UTF-8"));
	// return;
	// }
	//
	// writeToDb(dbConn, RESOURCE_TYPES.Firewall, allFirewalls);
	// publishBasicDataToRabbitMQ(RESOURCE_TYPES.Firewall, allFirewalls);
	//
	//
	// /* Networks */
	// List<Object> allNetworks = computeManager.retrieveAllNetworks(projectId);
	// if (allNetworks == null || allNetworks.isEmpty()) {
	// String msg = "Error retrieving networks from GCP - stopping any further
	// processing";
	// logger.warn(msg);
	// rabbitChannel.basicPublish("", RABBIT_QUEUE_NAME, null,
	// msg.getBytes("UTF-8"));
	// return;
	// }
	//
	// writeToDb(dbConn, RESOURCE_TYPES.Network, allNetworks);
	// publishBasicDataToRabbitMQ(RESOURCE_TYPES.Network, allNetworks);
	//
	//
	// logger.debug("Finished retrieving all resources from GCP project " +
	// projectId);
	//
	// } catch (Exception ex) {
	// logger.error(ex);
	// return;
	// }
	// }
	//
	//

	// /**
	// * Writes the given data (Subnets, VMs, etc.) to the DB
	// *
	// * @param dbConn Active DB connection
	// * @param resourceType Valid types: Subnet, VM,
	// * @param data Map of resource data. The values can contain any type of
	// * object (subnets, VMs, etc.) and will be written to JSON
	// * data and then stored in the DB
	// * @return
	// */
	// private boolean writeToDb(java.sql.Connection dbConn, RESOURCE_TYPES
	// resourceType, List<Object> data) {
	//
	// try {
	// String sqlInsertStmtSubnets = "INSERT INTO gcp (lastUpdated, projectId,
	// resourceType, resourceData) "
	// + "VALUES (NOW(), ?, ?, ?) " + "ON DUPLICATE KEY UPDATE lastUpdated=NOW(),
	// resourceData=?";
	//
	// PreparedStatement prepInsertStmtSubnets =
	// dbConn.prepareStatement(sqlInsertStmtSubnets);
	//
	// prepInsertStmtSubnets.setString(1, projectId);
	// prepInsertStmtSubnets.setString(2, resourceType.name());
	// prepInsertStmtSubnets.setString(3, jsonMapper.writeValueAsString(data));
	// prepInsertStmtSubnets.setString(4, jsonMapper.writeValueAsString(data));
	//
	// prepInsertStmtSubnets.executeUpdate();
	// return true;
	//
	// } catch (Exception ex) {
	// logger.error("Error trying to store resource data within the DB", ex);
	// return false;
	// }
	// }

	// private boolean publishToRabbitMQ(String resourceType, List<Object> data) {
	//
	// try {
	// ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
	// JsonGenerator jsonGen = jsonFactory.createGenerator(outputStream,
	// JsonEncoding.UTF8);
	//
	// jsonGen.writeStartObject();
	//
	// jsonGen.writeStringField("dataType", "resources");
	// jsonGen.writeStringField("sourceSystemType", "gcp");
	// jsonGen.writeStringField("sourceSystemProjectId", projectId);
	//
	// jsonGen.writeArrayFieldStart("data");
	//
	// Date now = new Date();
	//
	// jsonGen.writeStartObject();
	//
	// jsonGen.writeStringField("lastUpdated", dateFormatter.format(now));
	// jsonGen.writeStringField("resourceType", resourceType);
	// jsonGen.writeFieldName("resourceData");
	//
	// jsonGen.writeStartArray();
	// jsonGen.writeRawValue(jsonMapper.writeValueAsString(data));
	// jsonGen.writeEndArray();
	//
	// jsonGen.writeEndObject();
	//
	// jsonGen.writeEndArray();
	// jsonGen.writeEndObject();
	//
	// jsonGen.close();
	// outputStream.close();
	//
	// logger.debug("Forwarding updated list of " + resourceType + "s to the message
	// queue " + RABBIT_QUEUE_NAME);
	// rabbitChannel.basicPublish("", RABBIT_QUEUE_NAME, null,
	// outputStream.toString().getBytes("UTF-8"));
	//
	// return true;
	//
	// } catch (Exception ex) {
	// logger.error("Error trying to publish resource data to RabbitMQ", ex);
	// return false;
	// }
	//
	// }

	private boolean publishBasicDataToRabbitMQ(RESOURCE_TYPES resourceType, List<Object> data) {

		if (data == null || data.isEmpty()) {
			return false;
		}

		try {
			Date now = new Date();
			String lastUpdate = dateFormatter.format(now);

			ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
			JsonGenerator jsonGen = jsonFactory.createGenerator(outputStream, JsonEncoding.UTF8);

			jsonGen.writeStartArray();

			if (resourceType == RESOURCE_TYPES.VM) {

				List<Instance> vms = (List<Instance>) (List<?>) data;
				generateJsonForVMs(jsonGen, vms, lastUpdate);
			}

			else if (resourceType == RESOURCE_TYPES.Firewall) {

				List<Firewall> firewalls = (List<Firewall>) (List<?>) data;
				generateJsonForFirewalls(jsonGen, firewalls, lastUpdate);
			}

			else if (resourceType == RESOURCE_TYPES.Network) {

				List<Network> networks = (List<Network>) (List<?>) data;
				generateJsonForNetworks(jsonGen, networks, lastUpdate);
			}

			else if (resourceType == RESOURCE_TYPES.Subnet) {

				List<Subnetwork> subnets = (List<Subnetwork>) (List<?>) data;
				generateJsonForSubnets(jsonGen, subnets, lastUpdate);
			}

			else if (resourceType == RESOURCE_TYPES.Zone) {

				List<Zone> zones = (List<Zone>) (List<?>) data;
				generateJsonForZones(jsonGen, zones, lastUpdate);
			}

			else if (resourceType == RESOURCE_TYPES.Region) {

				List<Region> regions = (List<Region>) (List<?>) data;
				generateJsonForRegions(jsonGen, regions, lastUpdate);
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

	private void generateJsonForRegions(JsonGenerator jsonGen, List<Region> regions, String lastUpdate) {

		try {
			for (Region region : regions) {

				jsonGen.writeStartObject();

				// Extra Data field
				// String region =
				// zone.getRegion().substring(zone.getRegion().indexOf("/regions/") +
				// "/regions/".length());
				// String extraData = "Status: " + zone.getStatus() + ", Region: " + region;

				jsonGen.writeStringField("name", region.getName());
				jsonGen.writeStringField("srcSysType", SRC_SYS_TYPE);
				jsonGen.writeStringField("resourceType", RESOURCE_TYPES.Region.name());
				// jsonGen.writeStringField("extraData", extraData);
				jsonGen.writeStringField("id", region.getId().toString());
				jsonGen.writeStringField("lastUpdate", lastUpdate);

				/*
				 * Details
				 */
				jsonGen.writeArrayFieldStart("details");
				jsonGen.writeString("Status: " + region.getStatus());

				// Create list of zones within this region
				if (region.getZones() != null && !region.getZones().isEmpty()) {

					String listOfZones = "Zones: ";

					for (String zoneLongName : region.getZones()) {
						String zone = zoneLongName.substring(zoneLongName.indexOf("/zones/") + "/zones/".length());
						listOfZones += zone + ",";
					}

					listOfZones = listOfZones.substring(0, listOfZones.length() - 1);
					jsonGen.writeString(listOfZones);
				}

				// End the "details" array
				jsonGen.writeEndArray();

				// End the current region node
				jsonGen.writeEndObject();
			}

		} catch (Exception ex) {
			logger.error("Error generating JSON content for list of regions", ex);
		}
	}

	private void generateJsonForZones(JsonGenerator jsonGen, List<Zone> zones, String lastUpdate) {

		try {
			for (Zone zone : zones) {

				jsonGen.writeStartObject();

				// Extra Data field
				String region = zone.getRegion()
						.substring(zone.getRegion().indexOf("/regions/") + "/regions/".length());
				// String extraData = "Status: " + zone.getStatus() + ", Region: " + region;

				jsonGen.writeStringField("name", zone.getName());
				jsonGen.writeStringField("srcSysType", SRC_SYS_TYPE);
				jsonGen.writeStringField("resourceType", RESOURCE_TYPES.Zone.name());
				// jsonGen.writeStringField("extraData", extraData);
				jsonGen.writeStringField("id", zone.getId().toString());
				jsonGen.writeStringField("lastUpdate", lastUpdate);

				/*
				 * Details
				 */
				jsonGen.writeArrayFieldStart("details");
				jsonGen.writeString("Region: " + region);
				jsonGen.writeString("Status: " + zone.getStatus());

				// End the "details" array
				jsonGen.writeEndArray();

				// End the current zone node
				jsonGen.writeEndObject();
			}

		} catch (Exception ex) {
			logger.error("Error generating JSON content for list of zones", ex);
		}
	}

	private void generateJsonForNetworks(JsonGenerator jsonGen, List<Network> networks, String lastUpdate) {

		try {
			for (Network network : networks) {

				jsonGen.writeStartObject();

				jsonGen.writeStringField("name", network.getName());
				jsonGen.writeStringField("srcSysType", SRC_SYS_TYPE);
				jsonGen.writeStringField("resourceType", RESOURCE_TYPES.Network.name());
				jsonGen.writeStringField("id", network.getId().toString());
				jsonGen.writeStringField("lastUpdate", lastUpdate);

				/*
				 * Details
				 */
				jsonGen.writeArrayFieldStart("details");
				jsonGen.writeString("Description: " + network.getDescription());

				if (network.getRoutingConfig() != null) {
					jsonGen.writeString("Routing Mode: " + network.getRoutingConfig().getRoutingMode());
				}

				// Create list of subnetworks within this network
				if (network.getSubnetworks() != null && !network.getSubnetworks().isEmpty()) {

					String listOfSubnets = "Subnets: ";

					for (String subnetLongName : network.getSubnetworks()) {
						String subnet = subnetLongName
								.substring(subnetLongName.indexOf("/subnetworks/") + "/subnetworks/".length());
						listOfSubnets += subnet + ",";
					}

					listOfSubnets = listOfSubnets.substring(0, listOfSubnets.length() - 1);
					jsonGen.writeString(listOfSubnets);
				}

				// End the "details" array
				jsonGen.writeEndArray();

				// End the current network node
				jsonGen.writeEndObject();
			}

		} catch (Exception ex) {
			logger.error("Error generating JSON content for list of networks", ex);
		}
	}

	private void generateJsonForSubnets(JsonGenerator jsonGen, List<Subnetwork> subnets, String lastUpdate) {

		try {
			for (Subnetwork subnet : subnets) {

				jsonGen.writeStartObject();

				// Extra Data field
				String region = subnet.getRegion()
						.substring(subnet.getRegion().indexOf("/regions/") + "/regions/".length());
				String network = subnet.getNetwork()
						.substring(subnet.getNetwork().indexOf("/networks/") + "/networks/".length());
				// String extraData = "Gateway: " + subnet.getGatewayAddress() + ", CIDR: " +
				// subnet.getIpCidrRange() + ", Network: " + network + ", Region: " + region;

				jsonGen.writeStringField("name", subnet.getName());
				jsonGen.writeStringField("srcSysType", SRC_SYS_TYPE);
				jsonGen.writeStringField("resourceType", RESOURCE_TYPES.Subnet.name());
				// jsonGen.writeStringField("extraData", extraData);
				jsonGen.writeStringField("id", subnet.getId().toString());
				jsonGen.writeStringField("lastUpdate", lastUpdate);

				/*
				 * Details
				 */
				jsonGen.writeArrayFieldStart("details");
				jsonGen.writeString("Region: " + region);
				jsonGen.writeString("Network: " + network);
				jsonGen.writeString("Gateway Address: " + subnet.getGatewayAddress());
				jsonGen.writeString("CIDR Range: " + subnet.getIpCidrRange());

				// End the "details" array
				jsonGen.writeEndArray();

				// End the current subnet node
				jsonGen.writeEndObject();
			}

		} catch (Exception ex) {
			logger.error("Error generating JSON content for list of subnets", ex);
		}
	}

	private void generateJsonForFirewalls(JsonGenerator jsonGen, List<Firewall> firewalls, String lastUpdate) {

		try {
			for (Firewall fw : firewalls) {

				jsonGen.writeStartObject();

				// Extra Data field
				String network = fw.getNetwork()
						.substring(fw.getNetwork().indexOf("/networks/") + "/networks/".length());
				// String extraData = "Description: " + fw.getDescription() + ", Network: " +
				// network + ", Direction: " + fw.getDirection();

				jsonGen.writeStringField("name", fw.getName());
				jsonGen.writeStringField("srcSysType", SRC_SYS_TYPE);
				jsonGen.writeStringField("resourceType", RESOURCE_TYPES.Firewall.name());
				// jsonGen.writeStringField("extraData", extraData);
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
						allowed += ", Ports: ";
						for (String port : fw.getAllowed().get(0).getPorts()) {
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
						denied += ", Ports: ";
						for (String port : fw.getDenied().get(0).getPorts()) {
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
					for (String srcRange : fw.getSourceRanges()) {
						srcFilters += srcRange + ",";
					}

					srcFilters = srcFilters.substring(0, srcFilters.length() - 1);
					jsonGen.writeString("Source Filters: IP Ranges: " + srcFilters);
				}

				// Target Tag
				if (fw.getTargetTags() != null && !fw.getTargetTags().isEmpty()) {
					String targetTags = "";
					for (String targetTag : fw.getTargetTags()) {
						targetTags += targetTag + ",";
					}

					targetTags = targetTags.substring(0, targetTags.length() - 1);
					jsonGen.writeString("Target Tags: " + targetTags);
				}

				jsonGen.writeString("Description: " + fw.getDescription());
				jsonGen.writeString("Network: " + network);

				jsonGen.writeEndArray();

				jsonGen.writeEndObject();
			}

		} catch (Exception ex) {
			logger.error("Error generating JSON content for list of firewalls", ex);
		}
	}

	private void generateJsonForVMs(JsonGenerator jsonGen, List<Instance> vms, String lastUpdate) {

		try {

			for (Instance vm : vms) {

				jsonGen.writeStartObject();

				// Extra Data field
				String machineType = vm.getMachineType()
						.substring(vm.getMachineType().indexOf("/machineTypes/") + "/machineTypes/".length());
				String zone = vm.getZone().substring(vm.getZone().indexOf("/zones/") + "/zones/".length());
				// String extraData = "Type: " + machineType + ", Status: " + vm.getStatus() +
				// ", Zone: " + zone;

				jsonGen.writeStringField("name", vm.getName());
				jsonGen.writeStringField("srcSysType", SRC_SYS_TYPE);
				jsonGen.writeStringField("resourceType", RESOURCE_TYPES.VM.name());
				// jsonGen.writeStringField("extraData", extraData);
				jsonGen.writeStringField("id", vm.getId().toString());
				jsonGen.writeStringField("lastUpdate", lastUpdate);

				/*
				 * Details
				 */
				jsonGen.writeArrayFieldStart("details");
				jsonGen.writeString("Machine Type: " + machineType);
				jsonGen.writeString("Zone: " + zone);
				jsonGen.writeString("Status: " + vm.getStatus());

				// Network Interfaces
				if (vm.getNetworkInterfaces() != null && !vm.getNetworkInterfaces().isEmpty()) {

					String nwInterfaces = "NW Interfaces: ";
					boolean isFirst = true;

					for (NetworkInterface nic : vm.getNetworkInterfaces()) {
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
					for (String tag : vm.getTags().getItems()) {
						tags += tag + ",";
					}

					tags = tags.substring(0, tags.length() - 1);
					jsonGen.writeString("Tags: " + tags);
				}

				// End the "details" array
				jsonGen.writeEndArray();

				// End the current VM node
				jsonGen.writeEndObject();
			}

		} catch (Exception ex) {
			logger.error("Error generating JSON content for list of VMs", ex);
		}
	}
}
