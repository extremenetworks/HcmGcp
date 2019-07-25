package com.extremenetworks.hcm.gcp;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.services.cloudbilling.Cloudbilling;
import com.google.api.services.cloudbilling.CloudbillingScopes;
import com.google.api.services.cloudbilling.model.ListServicesResponse;
import com.google.api.services.cloudbilling.model.ListSkusResponse;
import com.google.api.services.cloudbilling.model.ProjectBillingInfo;
import com.google.api.services.cloudbilling.model.Service;
import com.google.api.services.compute.Compute;
import com.google.api.services.compute.ComputeScopes;
import com.google.api.services.compute.model.Firewall;
import com.google.api.services.compute.model.FirewallList;
import com.google.api.services.compute.model.Instance;
import com.google.api.services.compute.model.InstanceList;
import com.google.api.services.compute.model.NetworkList;
import com.google.api.services.compute.model.Operation;
import com.google.api.services.compute.model.RegionList;
import com.google.api.services.compute.model.SubnetworkList;
import com.google.api.services.compute.model.Tags;
import com.google.api.services.compute.model.ZoneList;
import com.google.api.services.monitoring.v3.MonitoringScopes;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.monitoring.v3.MetricServiceClient;
import com.google.cloud.monitoring.v3.MetricServiceClient.ListTimeSeriesPagedResponse;
import com.google.cloud.monitoring.v3.MetricServiceSettings;
import com.google.monitoring.v3.ListTimeSeriesRequest;
import com.google.monitoring.v3.ProjectName;
import com.google.monitoring.v3.TimeInterval;
import com.google.monitoring.v3.TimeSeries;
import com.google.protobuf.util.Timestamps;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class GoogleComputeEngineApi {

	private static final Logger logger = LogManager.getLogger(GoogleComputeEngineApi.class);

	ObjectMapper jsonMapper = new ObjectMapper();

	/* One Compute connection object per project id */
	private HashMap<String, Compute> computeConnections;
	private HashMap<String, Cloudbilling> billingConnections;
	private HashMap<String, MetricServiceClient> metricsConnections;

	private static HttpTransport httpTransport;
	private final String APPLICATION_NAME = "Connect/1.0";
	private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();

	/*
	 * Max nr. of results retrieved for all API queries (zones, instances,
	 * firewalls, etc.) per query If more than this number of results exist,
	 * subsequent queries will occur until all results are received
	 */
	private Long maxQueryResults = 100L;

	// 20 seconds default timeout for all firewall rule operations: create, update,
	// delete
	private int timeoutForFwOperations = 20;

	public GoogleComputeEngineApi() {
		computeConnections = new HashMap<String, Compute>();
		billingConnections = new HashMap<String, Cloudbilling>();
		metricsConnections = new HashMap<String, MetricServiceClient>();
	}

	public boolean createComputeConnection(String projectId, String authFileContent) {

		if (projectId == null || projectId.isEmpty()) {
			logger.warn("Cannot create a new Compute connection since no project id was provided");
			return false;
		}
		if (authFileContent == null || authFileContent.isEmpty()) {
			logger.warn("Cannot create a new Compute connection since no authentication file content was provided");
			return false;
		}

		/* Load the JSON credentials file content */
		GoogleCredential credV1 = null;
		GoogleCredentials credV2 = null;

		try {
			InputStream credFileInputStreamV1 = new ByteArrayInputStream(
					authFileContent.getBytes(StandardCharsets.UTF_8));

			List<String> authScopes = new ArrayList<String>();
			authScopes.add(ComputeScopes.COMPUTE);
			authScopes.add(CloudbillingScopes.CLOUD_PLATFORM);
			authScopes.add(MonitoringScopes.MONITORING_READ);

			credV1 = GoogleCredential.fromStream(credFileInputStreamV1).createScoped(authScopes);

			InputStream credFileInputStreamV2 = new ByteArrayInputStream(
					authFileContent.getBytes(StandardCharsets.UTF_8));
			credV2 = GoogleCredentials.fromStream(credFileInputStreamV2).createScoped(authScopes);

		} catch (Exception ex) {
			logger.error("Error loading the credentials JSON file content for authorizing against the GCP project "
					+ projectId, ex);
			return false;
		}

		try {
			httpTransport = GoogleNetHttpTransport.newTrustedTransport();

			// Create compute engine object
			computeConnections.put(projectId, new Compute.Builder(httpTransport, JSON_FACTORY, null)
					.setApplicationName(APPLICATION_NAME).setHttpRequestInitializer(credV1).build());

			billingConnections.put(projectId, new Cloudbilling.Builder(httpTransport, JSON_FACTORY, credV1)
					.setApplicationName("Extreme Networks Hybrid Cloud Manager").build());

			metricsConnections.put(projectId, MetricServiceClient.create(MetricServiceSettings.newBuilder()
					.setCredentialsProvider(FixedCredentialsProvider.create(credV2)).build()));

			return true;

		} catch (Exception e) {
			logger.error("Error while trying to setup the 'compute engine' connection for project " + projectId, e);
			return false;
		}
	}

	public List<Object> retrieveAllZones(String projectId) {

		Compute computeConnection = computeConnections.get(projectId);
		if (computeConnection == null) {
			logger.warn("Cannot retrieve any zones since there is no Compute connection for project " + projectId);
			return null;
		}

		logger.debug("Retrieving all zones from project " + projectId);

		try {
			// Retrieve all zones for the given project
			ZoneList zoneList = computeConnection.zones().list(projectId).setMaxResults(maxQueryResults).execute();

			if (zoneList == null || zoneList.getItems() == null) {
				logger.info("No zones found for project with id " + projectId);
				return null;
			}

			logger.debug("First poll of max " + maxQueryResults + " results returned a list of "
					+ zoneList.getItems().size() + " zones found for project with id " + projectId + ": "
					+ jsonMapper.writeValueAsString(zoneList));

			/* Create a result list that will hold all regions retrieved from all polls */
			List<Object> allZones = new ArrayList<Object>();
			allZones.addAll(zoneList.getItems());

			/*
			 * If we received a "nextPageToken" it means that we have to page through all
			 * zones as there are more zones than we retrieved due to the configured
			 * "MaxResults" parameter
			 */
			int nrOfPolls = 2;

			while (zoneList.getNextPageToken() != null && !zoneList.getNextPageToken().isEmpty()) {

				zoneList = computeConnection.zones().list(projectId).setMaxResults(maxQueryResults)
						.setPageToken(zoneList.getNextPageToken()).execute();

				if (zoneList == null || zoneList.getItems() == null) {
					logger.warn("Error retrieving zones from GCE on poll page " + nrOfPolls
							+ ". Not returning an incomplete list of " + allZones.size() + " zones retrieved so far!");
					return null;
				}

				logger.debug("Received next list of zones from GCE (page nr " + nrOfPolls + "): "
						+ jsonMapper.writeValueAsString(zoneList));
				allZones.addAll(zoneList.getItems());
				nrOfPolls++;
			}

			// To report the correct number of page polls
			nrOfPolls--;

			logger.debug("Finished retrieving the full list of " + allZones.size() + " zones using " + nrOfPolls
					+ " page polls");
			return allZones;

		} catch (Exception e) {
			logger.error("Error while trying to retrieve all zones for project with id " + projectId, e);
			return null;
		}
	}

	public List<Object> retrieveAllRegions(String projectId) {

		Compute computeConnection = computeConnections.get(projectId);
		if (computeConnection == null) {
			logger.warn("Cannot retrieve any regions since there is no Compute connection for project " + projectId);
			return null;
		}

		logger.debug("Retrieving all regions from project " + projectId);

		try {
			// Retrieve all regions for the given project
			RegionList regionList = computeConnection.regions().list(projectId).setMaxResults(maxQueryResults)
					.execute();

			if (regionList == null || regionList.getItems() == null) {
				logger.info("No regions found for project with id " + projectId);
				return null;
			}

			logger.debug("First poll of max " + maxQueryResults + " results returned a list of "
					+ regionList.getItems().size() + " regions found for project with id " + projectId + ": "
					+ jsonMapper.writeValueAsString(regionList));

			/* Create a result list that will hold all regions retrieved from all polls */
			List<Object> allRegions = new ArrayList<Object>();
			allRegions.addAll(regionList.getItems());

			/*
			 * If we received a "nextPageToken" it means that we have to page through all
			 * regions as there are more regions than we retrieved due to the configured
			 * "MaxResults" parameter
			 */
			int nrOfPolls = 2;

			while (regionList.getNextPageToken() != null && !regionList.getNextPageToken().isEmpty()) {

				regionList = computeConnection.regions().list(projectId).setMaxResults(maxQueryResults)
						.setPageToken(regionList.getNextPageToken()).execute();

				if (regionList == null || regionList.getItems() == null) {
					logger.warn("Error retrieving regions from GCE on poll page " + nrOfPolls
							+ ". Not returning an incomplete list of " + allRegions.size()
							+ " regions retrieved so far!");
					return null;
				}

				logger.debug("Received next list of regions from GCE (page nr " + nrOfPolls + "): "
						+ jsonMapper.writeValueAsString(regionList));
				allRegions.addAll(regionList.getItems());
				nrOfPolls++;
			}

			// To report the correct number of page polls
			nrOfPolls--;

			logger.debug("Finished retrieving the full list of " + allRegions.size() + " regions using " + nrOfPolls
					+ " page polls");
			return allRegions;

		} catch (Exception e) {
			logger.error("Error while trying to retrieve all regions for project with id " + projectId, e);
			return null;
		}
	}

	public List<Object> retrieveAllNetworks(String projectId) {

		Compute computeConnection = computeConnections.get(projectId);
		if (computeConnection == null) {
			logger.warn("Cannot retrieve any networks since there is no Compute connection for project " + projectId);
			return null;
		}

		logger.debug("Retrieving all networks from project " + projectId);

		try {
			// Retrieve all networks for the given project
			NetworkList networkList = computeConnection.networks().list(projectId).setMaxResults(maxQueryResults)
					.execute();

			if (networkList == null || networkList.getItems() == null) {
				logger.info("No network found for project with id " + projectId);
				return null;
			}

			logger.debug("First poll of max " + maxQueryResults + " results returned a list of "
					+ networkList.getItems().size() + " networks found for project with id " + projectId + ": "
					+ jsonMapper.writeValueAsString(networkList));

			/* Create a result list that will hold all networks retrieved from all polls */
			List<Object> allNetworks = new ArrayList<Object>();
			allNetworks.addAll(networkList.getItems());

			/*
			 * If we received a "nextPageToken" it means that we have to page through all
			 * networks as there are more networks than we retrieved due to the configured
			 * "MaxResults" parameter
			 */
			int nrOfPolls = 2;

			while (networkList.getNextPageToken() != null && !networkList.getNextPageToken().isEmpty()) {

				networkList = computeConnection.networks().list(projectId).setMaxResults(maxQueryResults)
						.setPageToken(networkList.getNextPageToken()).execute();

				if (networkList == null || networkList.getItems() == null) {
					logger.warn("Error retrieving networks from GCE on poll page " + nrOfPolls
							+ ". Not returning an incomplete list of " + allNetworks.size()
							+ " networks retrieved so far!");
					return null;
				}

				logger.debug("Received next list of networks from GCE (page nr " + nrOfPolls + "): "
						+ jsonMapper.writeValueAsString(networkList));
				allNetworks.addAll(networkList.getItems());
				nrOfPolls++;
			}

			// To report the correct number of page polls
			nrOfPolls--;

			logger.debug("Finished retrieving the full list of " + allNetworks.size() + " networks using " + nrOfPolls
					+ " page polls");
			return allNetworks;

		} catch (Exception e) {
			logger.error("Error while trying to retrieve all networks for project with id " + projectId, e);
			return null;
		}
	}

	public List<Object> retrieveInstancesForZone(String projectId, String zoneName) {

		Compute computeConnection = computeConnections.get(projectId);
		if (computeConnection == null) {
			logger.warn("Cannot retrieve any instances since there is no Compute connection for project " + projectId);
			return null;
		}

		logger.debug("Retrieving all instances from zone " + zoneName + " and project " + projectId);

		try {
			// Retrieve instances for the given project and zone
			InstanceList instanceList = computeConnection.instances().list(projectId, zoneName)
					.setMaxResults(maxQueryResults).execute();

			/*
			 * A null value for the overall instanceList indicates an error to the caller.
			 * If just the items within the instanceList is null we create an empty list and
			 * return that empty list --> not an error --> simply no instances in that zone
			 */
			if (instanceList == null) {
				logger.warn("Retrieving the list of instances for project with id " + projectId + " within zone "
						+ zoneName + " returned a null object!");
				return null;
			}

			if (instanceList.getItems() == null) {
				logger.debug("No instances found for project with id " + projectId + " within zone " + zoneName);
				return new ArrayList<Object>();
			}

			logger.debug("First poll of max " + maxQueryResults + " results returned a list of "
					+ instanceList.getItems().size() + " instances found for project with id " + projectId
					+ " within zone " + zoneName + ": " + jsonMapper.writeValueAsString(instanceList));

			/* Create a result list that will hold all instances retrieved from all polls */
			List<Object> allInstances = new ArrayList<Object>();
			allInstances.addAll(instanceList.getItems());

			/*
			 * If we received a "nextPageToken" it means that we have to page through all
			 * instances as there are more instances than we retrieved due to the configured
			 * "MaxResults" parameter
			 */
			int nrOfPolls = 2;

			while (instanceList.getNextPageToken() != null && !instanceList.getNextPageToken().isEmpty()) {

				instanceList = computeConnection.instances().list(projectId, zoneName).setMaxResults(maxQueryResults)
						.setPageToken(instanceList.getNextPageToken()).execute();

				if (instanceList == null || instanceList.getItems() == null) {
					logger.warn("Error retrieving instances from GCE on poll page " + nrOfPolls
							+ ". Not returning an incomplete list of " + allInstances.size()
							+ " instances retrieved so far!");
					return null;
				}

				logger.debug("Received next list of instances from GCE (page nr " + nrOfPolls + "): "
						+ jsonMapper.writeValueAsString(instanceList));
				allInstances.addAll(instanceList.getItems());
				nrOfPolls++;
			}

			// To report the correct number of page polls
			nrOfPolls--;

			logger.debug("Finished retrieving the full list of " + allInstances.size() + " instances using " + nrOfPolls
					+ " page polls");
			return allInstances;

		} catch (Exception e) {
			logger.error("Error while trying to retrieve all instances for project with id " + projectId
					+ " within zone " + zoneName, e);
			return null;
		}
	}

	public List<Object> retrieveSubnetworksForRegion(String projectId, String regionName) {

		Compute computeConnection = computeConnections.get(projectId);
		if (computeConnection == null) {
			logger.warn("Cannot retrieve any subnets since there is no Compute connection for project " + projectId);
			return null;
		}

		logger.debug("Retrieving all subnets from region " + regionName + " from project " + projectId);

		try {
			// Retrieve subnetworks for the given project and region
			SubnetworkList subnetworksList = computeConnection.subnetworks().list(projectId, regionName)
					.setMaxResults(maxQueryResults).execute();

			if (subnetworksList == null || subnetworksList.getItems() == null) {
				logger.debug("No subnetworks found for project with id " + projectId + " within region " + regionName);
				return null;
			}

			logger.debug("First poll of max " + maxQueryResults + " results returned a list of "
					+ subnetworksList.getItems().size() + " subnetworks found for project with id " + projectId
					+ " within region " + regionName + ": " + jsonMapper.writeValueAsString(subnetworksList));

			/*
			 * Create a result list that will hold all subnetworks retrieved from all polls
			 */
			List<Object> allSubnetworks = new ArrayList<Object>();
			allSubnetworks.addAll(subnetworksList.getItems());

			/*
			 * If we received a "nextPageToken" it means that we have to page through all
			 * subnetworks as there are more subnetworks than we retrieved due to the
			 * configured "MaxResults" parameter
			 */
			int nrOfPolls = 2;

			while (subnetworksList.getNextPageToken() != null && !subnetworksList.getNextPageToken().isEmpty()) {

				subnetworksList = computeConnection.subnetworks().list(projectId, regionName)
						.setMaxResults(maxQueryResults).setPageToken(subnetworksList.getNextPageToken()).execute();

				if (subnetworksList == null || subnetworksList.getItems() == null) {
					logger.warn("Error retrieving subnetworks from GCE on poll page " + nrOfPolls
							+ ". Not returning an incomplete list of " + allSubnetworks.size()
							+ " subnetworks retrieved so far!");
					return null;
				}

				logger.debug("Received next list of subnetworks from GCE (page nr " + nrOfPolls + "): "
						+ jsonMapper.writeValueAsString(subnetworksList));
				allSubnetworks.addAll(subnetworksList.getItems());
				nrOfPolls++;
			}

			// To report the correct number of page polls
			nrOfPolls--;

			logger.debug("Finished retrieving the full list of " + allSubnetworks.size() + " subnetworks using "
					+ nrOfPolls + " page polls");
			return allSubnetworks;

		} catch (Exception e) {
			logger.error("Error while trying to retrieve all subnetworks for project with id " + projectId
					+ " within region " + regionName, e);
			return null;
		}
	}

	/**
	 * Retrieves a list of firewalls from GCE.
	 * 
	 * @param projectId                The project ID to connect to
	 * @param vpcName                  The name of the VPC network to filter for.
	 *                                 The name must be given as it appears on the
	 *                                 UI. For example, to filter for a network with
	 *                                 ID
	 *                                 "https://www.googleapis.com/compute/v1/projects/snappy-bucksaw-168120/global/networks/datalab-network"
	 *                                 you specify "datalab-network" (only the last
	 *                                 part after the last slash). If no vpcName is
	 *                                 provided, all firewalls will be retrieved.
	 * @param onlyRetrieveManagedRules If set to true: will add an additional filter
	 *                                 to the query to only retrieve firewall rules
	 *                                 that contain the 'ExtremePolicyId=' tag
	 *                                 within the rule's description --> managed
	 *                                 rules. If set to false: will retrieve all
	 *                                 rules (managed or not)
	 * @return
	 */
	public List<Object> retrieveFirewalls(String projectId, String vpcName, boolean onlyRetrieveManagedRules) {

		Compute computeConnection = computeConnections.get(projectId);
		if (computeConnection == null) {
			logger.warn("Cannot retrieve any firewalls since there is no Compute connection for project " + projectId);
			return null;
		}

		if (onlyRetrieveManagedRules) {
			logger.debug("Retrieving only managed firewalls for VPC network " + vpcName + " from project " + projectId);
		} else {
			logger.debug("Retrieving all firewalls for VPC network " + vpcName + " from project " + projectId);
		}

		try {
			/*
			 * If no vpc network name provided --> retrieve all firewalls If vpc network
			 * name provided --> retrieve only firewalls for this VPC
			 */
			FirewallList firewallList = null;
			if (vpcName == null || vpcName.isEmpty()) {

				if (onlyRetrieveManagedRules) {
					logger.debug(
							"Retrieving all managed firewall rules for all VPC networks from project " + projectId);

					firewallList = computeConnection.firewalls().list(projectId)
							.setFilter("description eq .*ExtremePolicyId='.+'.*").setMaxResults(maxQueryResults)
							.execute();
				} else {
					logger.debug("Retrieving all firewall rules for all VPC networks from project " + projectId);

					firewallList = computeConnection.firewalls().list(projectId).setMaxResults(maxQueryResults)
							.execute();
				}

			} else {
				/*
				 * Example network name/id: "network":
				 * "https://www.googleapis.com/compute/v1/projects/snappy-bucksaw-168120/global/networks/datalab-network"
				 * The actual name of the network is only found in the last part of the name
				 * after the last slash
				 */
				if (onlyRetrieveManagedRules) {
					logger.debug("Retrieving all managed firewall rules for VPC network " + vpcName + " from project "
							+ projectId);

					firewallList = computeConnection.firewalls().list(projectId)
							.setFilter("(network eq .+/" + vpcName + ") (description eq .*ExtremePolicyId='.+'.*)")
							.setMaxResults(maxQueryResults).execute();
				} else {
					logger.debug(
							"Retrieving all firewall rules for VPC network " + vpcName + " from project " + projectId);

					firewallList = computeConnection.firewalls().list(projectId).setFilter("network eq .+/" + vpcName)
							.setMaxResults(maxQueryResults).execute();
				}
			}

			/*
			 * A null value at this point doesn't indicate an error but simply an empty list
			 */
			if (firewallList == null || firewallList.getItems() == null) {
				logger.info("No firewalls found for project with id " + projectId + " - VPC name filter: " + vpcName);
				firewallList = new FirewallList();
				firewallList.setItems(new ArrayList<Firewall>());
				return new ArrayList<Object>();
			}

			logger.debug("First poll of max " + maxQueryResults + " results returned a list of "
					+ firewallList.getItems().size() + " firewalls found for project with id " + projectId + ": "
					+ jsonMapper.writeValueAsString(firewallList));

			/* Create a result list that will hold all firewalls retrieved from all polls */
			List<Object> allFirewalls = new ArrayList<Object>();
			allFirewalls.addAll(firewallList.getItems());

			/*
			 * If we received a "nextPageToken" it means that we have to page through all
			 * firewalls as there are more firewalls than we retrieved due to the configured
			 * "MaxResults" parameter
			 */
			int nrOfPolls = 2;

			while (firewallList.getNextPageToken() != null && !firewallList.getNextPageToken().isEmpty()) {

				firewallList = computeConnection.firewalls().list(projectId).setMaxResults(maxQueryResults)
						.setPageToken(firewallList.getNextPageToken()).execute();

				if (firewallList == null || firewallList.getItems() == null) {
					logger.warn("Error retrieving firewalls from GCE on poll page " + nrOfPolls
							+ ". Not returning an incomplete list of " + allFirewalls.size()
							+ " firewalls retrieved so far!");
					return null;
				}

				logger.debug("Received next list of firewalls from GCE (page nr " + nrOfPolls + "): "
						+ jsonMapper.writeValueAsString(allFirewalls));
				allFirewalls.addAll(firewallList.getItems());
				nrOfPolls++;
			}

			// To report the correct number of page polls
			nrOfPolls--;

			logger.debug("Finished retrieving the full list of " + allFirewalls.size() + " firewalls using " + nrOfPolls
					+ " page polls");
			return allFirewalls;

		} catch (Exception e) {
			logger.error("Error while trying to retrieve all firewalls for project with id " + projectId, e);
			return null;
		}
	}

	/**
	 * Tries to create a new firewall rule on the GCE cloud.
	 * 
	 * @param projectId Project ID where to create the new firewall rule
	 * @param fwRule    The actual rule to create. Minimal requirements: - name -
	 *                  network - direction - at least one allowed or one denied
	 *                  rule
	 * @return True on success, false on any error
	 */
	public boolean createFirewallRule(String projectId, Firewall fwRule) {

		Compute computeConnection = computeConnections.get(projectId);
		if (computeConnection == null) {
			logger.warn("Cannot create firewall rule since there is no Compute connection for project " + projectId);
			return false;
		}

		if (projectId == null || projectId.isEmpty()) {
			logger.error("Cannot create new firewall rule since no project id was provided");
			return false;
		}
		if (fwRule == null) {
			logger.error("Cannot create new firewall rule for project " + projectId
					+ " since no firewall rule was provided");
			return false;
		}
		if (fwRule.getName() == null || fwRule.getName().isEmpty()) {
			logger.error("Cannot create new firewall rule for project " + projectId
					+ " since the provided firewall rule is missing a name");
			return false;
		}
		if (fwRule.getNetwork() == null || fwRule.getNetwork().isEmpty()) {
			logger.error("Cannot create new firewall rule for project " + projectId + " with name " + fwRule.getName()
					+ " since the provided firewall rule is missing the network id");
			return false;
		}
		if (fwRule.getDirection() == null || fwRule.getDirection().isEmpty()) {
			logger.error("Cannot create new firewall rule for project " + projectId + " with name " + fwRule.getName()
					+ " since the provided firewall rule is missing the direction");
			return false;
		}
		if ((fwRule.getAllowed() == null && fwRule.getDenied() == null)
				|| (fwRule.getAllowed().size() == 0 && fwRule.getDenied().size() == 0)) {
			logger.error("Cannot create new firewall rule for project " + projectId + " with name " + fwRule.getName()
					+ " since the provided firewall rule is missing rules");
			return false;
		}

		try {
			Operation fwInsertOperation = computeConnection.firewalls().insert(projectId, fwRule).execute();
			if (fwInsertOperation == null) {
				logger.error("Failed to request the creation of a new firewall rule for project " + projectId
						+ " with name " + fwRule.getName() + " and network " + fwRule.getNetwork()
						+ " initial request returned null");
				return false;
			}

			// Cache the reference to the operation we have kicked off
			String fwInsertOperationId = fwInsertOperation.getName();
			int timeout = timeoutForFwOperations;

			while (timeout > 0) {

				// Wait a second before retrieving the latest status on the operation
				timeout--;
				Thread.sleep(1000);

				fwInsertOperation = computeConnection.globalOperations().get(projectId, fwInsertOperationId).execute();
				if (fwInsertOperation == null) {
					logger.error(
							"Error while requesting the current status of the operation to create a new firewall rule for project "
									+ projectId + " with name " + fwRule.getName() + " and network "
									+ fwRule.getNetwork() + " - request returned null");
					return false;
				}

				// Assumption: operation was successful if progress == 100% and status == DONE
				if (fwInsertOperation.getProgress() == 100) {
					if (fwInsertOperation.getStatus().equals("DONE")) {
						logger.info("Successfully created new firewall rule: " + jsonMapper.writeValueAsString(fwRule));
						return true;
					} else {
						logger.error("Error creating a new firewall rule for project " + projectId + " with name "
								+ fwRule.getName() + " and network " + fwRule.getNetwork()
								+ " - progress is at 100% but the status doesn't equal 'DONE'"
								+ jsonMapper.writeValueAsString(fwInsertOperation));
						return false;
					}
				}
			}

			// At this point the timeout has been reached - giving up - counting as creation
			// error
			logger.error("Timeout while trying to create a new firewall rule for project " + projectId + " with name "
					+ fwRule.getName() + " and network " + fwRule.getNetwork());
			return false;

		} catch (Exception ex) {
			logger.error("Error while trying to create a new firewall rule for project " + projectId + " with name "
					+ fwRule.getName() + " and network " + fwRule.getNetwork(), ex);
			return false;
		}
	}

	/**
	 * Tries to update an existing firewall rule on the GCE cloud.
	 * 
	 * @param projectId Project ID where to update the provided firewall rule
	 * @param fwRule    The actual rule to update. Must have the name attribute set
	 *                  --> used as identifier to know which rule to update. Cannot
	 *                  update/modify the associated network nor direction!
	 * @return True on success, false on any error
	 */
	public boolean updateFirewallRule(String projectId, Firewall fwRule) {

		Compute computeConnection = computeConnections.get(projectId);
		if (computeConnection == null) {
			logger.warn("Cannot update firewall rule since there is no Compute connection for project " + projectId);
			return false;
		}

		if (projectId == null || projectId.isEmpty()) {
			logger.error("Cannot update firewall rule since no project id was provided");
			return false;
		}
		if (fwRule == null) {
			logger.error(
					"Cannot update firewall rule for project " + projectId + " since no firewall rule was provided");
			return false;
		}
		if (fwRule.getName() == null || fwRule.getName().isEmpty()) {
			logger.error("Cannot update firewall rule for project " + projectId
					+ " since the provided firewall rule is missing a name");
			return false;
		}

		try {
			Operation fwUpdateOperation = computeConnection.firewalls().update(projectId, fwRule.getName(), fwRule)
					.execute();
			if (fwUpdateOperation == null) {
				logger.error("Failed to request the update of a firewall rule for project " + projectId + " with name "
						+ fwRule.getName() + " - initial request returned null");
				return false;
			}

			// Cache the reference to the operation we have kicked off
			String fwUpdateOperationId = fwUpdateOperation.getName();
			int timeout = timeoutForFwOperations;

			while (timeout > 0) {

				// Wait a second before retrieving the latest status on the operation
				timeout--;
				Thread.sleep(1000);

				fwUpdateOperation = computeConnection.globalOperations().get(projectId, fwUpdateOperationId).execute();
				if (fwUpdateOperation == null) {
					logger.error(
							"Error while requesting the current status of the operation to update a irewall rule for project "
									+ projectId + " with name " + fwRule.getName() + " - request returned null");
					return false;
				}

				// Assumption: operation was successful if progress == 100% and status == DONE
				if (fwUpdateOperation.getProgress() == 100) {
					if (fwUpdateOperation.getStatus().equals("DONE")) {
						logger.info("Successfully updated firewall rule: " + jsonMapper.writeValueAsString(fwRule));
						return true;
					} else {
						logger.error("Error updating a firewall rule for project " + projectId + " with name "
								+ fwRule.getName() + " - progress is at 100% but the status doesn't equal 'DONE'"
								+ jsonMapper.writeValueAsString(fwUpdateOperation));
						return false;
					}
				}
			}

			// At this point the timeout has been reached - giving up - counting as update
			// error
			logger.error("Timeout while trying to update a firewall rule for project " + projectId + " with name "
					+ fwRule.getName());
			return false;

		} catch (Exception ex) {
			logger.error("Error while trying to update a firewall rule for project " + projectId + " with name "
					+ fwRule.getName(), ex);
			return false;
		}
	}

	/**
	 * Tries to delete an existing firewall rule from the GCE cloud.
	 * 
	 * @param projectId  Project ID where to delete the firewall rule from
	 * @param fwRuleName The firewall rule name is used to identify the firewall
	 *                   rule to delete
	 * @return True on success, false on any error
	 */
	public boolean deleteFirewallRule(String projectId, String fwRuleName) {

		Compute computeConnection = computeConnections.get(projectId);
		if (computeConnection == null) {
			logger.warn("Cannot delete firewall rule since there is no Compute connection for project " + projectId);
			return false;
		}

		if (projectId == null || projectId.isEmpty()) {
			logger.error("Cannot delete a firewall rule since no project id was provided");
			return false;
		}
		if (fwRuleName == null || fwRuleName.isEmpty()) {
			logger.error("Cannot delete a firewall rule from project " + projectId
					+ " since no firewall rule name was provided");
			return false;
		}

		try {
			Operation fwDeleteOperation = computeConnection.firewalls().delete(projectId, fwRuleName).execute();
			if (fwDeleteOperation == null) {
				logger.error("Failed to request the deletion of a firewall rule for project " + projectId
						+ " with name " + fwRuleName + " - initial request returned null");
				return false;
			}

			// Cache the reference to the operation we have kicked off
			String fwDeleteOperationId = fwDeleteOperation.getName();
			int timeout = timeoutForFwOperations;

			while (timeout > 0) {

				// Wait a second before retrieving the latest status on the operation
				timeout--;
				Thread.sleep(1000);

				fwDeleteOperation = computeConnection.globalOperations().get(projectId, fwDeleteOperationId).execute();
				if (fwDeleteOperation == null) {
					logger.error(
							"Error while requesting the current status of the operation to delete a firewall rule for project "
									+ projectId + " with name " + fwRuleName + " - request returned null");
					return false;
				}

				// Assumption: operation was successful if progress == 100% and status == DONE
				if (fwDeleteOperation.getProgress() == 100) {
					if (fwDeleteOperation.getStatus().equals("DONE")) {
						logger.info("Successfully deleted firewall rule: " + fwRuleName + " from project " + projectId);
						return true;
					} else {
						logger.error("Error deleting a firewall rule from project " + projectId + " with name "
								+ fwRuleName + " - progress is at 100% but the status doesn't equal 'DONE'"
								+ jsonMapper.writeValueAsString(fwDeleteOperation));
						return false;
					}
				}
			}

			// At this point the timeout has been reached - giving up - counting as deletion
			// error
			logger.error("Timeout while trying to delete a firewall rule from project " + projectId + " with name "
					+ fwRuleName);
			return false;

		} catch (Exception ex) {
			logger.error("Error while trying to delete a firewall rule from project " + projectId + " with name "
					+ fwRuleName, ex);
			return false;
		}
	}

	/**
	 * Tries to set the network tags for a given instance on the GCE cloud. This
	 * will overwrite any existing tags on that instance. If an empty list of tags
	 * is provided, any existing tags on the given instance will be removed.
	 * 
	 * @param projectId    Project ID where to find the instance to modify
	 * @param zone         Zone which contains the instance to modify
	 * @param instanceName Name of the instance to modify
	 * @param tags         List of tags to set for the instance
	 * @return True on success, false on any error
	 */
	public boolean setInstanceTags(String projectId, String zone, String instanceName, ArrayList<String> tags) {

		Compute computeConnection = computeConnections.get(projectId);
		if (computeConnection == null) {
			logger.warn("Cannot update instance tags since there is no Compute connection for project " + projectId);
			return false;
		}

		if (projectId == null || projectId.isEmpty()) {
			logger.error("Cannot set the network tags on an instance since no project id was provided");
			return false;
		}
		if (zone == null || zone.isEmpty()) {
			logger.error("Cannot set the network tags on an instance for project " + projectId
					+ " since no zone was provided");
			return false;
		}
		if (instanceName == null || instanceName.isEmpty()) {
			logger.error("Cannot set the network tags on an instance for project " + projectId + " and zone " + zone
					+ " since no instance name was provided");
			return false;
		}
		if (tags == null) { // an empty list of tags is a valid use case --> would clear the list of tags
			logger.error("Cannot set the network tags on instance " + instanceName + " for project " + projectId
					+ " and zone " + zone + " since the list of tags is missing");
			return false;
		}

		try {
			/*
			 * First: pull the latest data on this instance to get the current tags'
			 * 'fingerprint' value --> this will be needed when trying to modify those tags
			 */
			Instance instance = computeConnection.instances().get(projectId, zone, instanceName).execute();

			if (instance == null) {
				logger.warn("Could not retrieve data on instance " + instanceName + " from project " + projectId
						+ " and zone " + zone + " --> won't be able to update its tags");
				return false;
			}

			logger.debug("Retrieved details on instance " + instanceName + " from project " + projectId + " and zone "
					+ zone + " to prepare updating its tags: " + jsonMapper.writeValueAsString(instance));

			/*
			 * Second: build the new Tags object that contains the current fingerprint value
			 * just retrieved from this instance
			 */
			Tags tagsToSet = new Tags();
			tagsToSet.setFingerprint(instance.getTags().getFingerprint());
			tagsToSet.setItems(new ArrayList<String>());

			for (String tag : tags) {
				tagsToSet.getItems().add(tag);
			}

			/* Third: try to set/update the tags */
			Operation setTagsOperation = computeConnection.instances().setTags(projectId, zone, instanceName, tagsToSet)
					.execute();

			if (setTagsOperation == null) {
				logger.warn("No feedback from GCE when trying to set the tags on instance " + instanceName
						+ " from project " + projectId + " and zone " + zone + ". Instance details: "
						+ jsonMapper.writeValueAsString(instance));
				return false;
			}

			// Cache the reference to the operation we have kicked off
			String setTagsOperationId = setTagsOperation.getName();
			int timeout = timeoutForFwOperations;

			while (timeout > 0) {

				// Wait a second before retrieving the latest status on the operation
				timeout--;
				Thread.sleep(1000);

				setTagsOperation = computeConnection.zoneOperations().get(projectId, zone, setTagsOperationId)
						.execute();
				if (setTagsOperation == null) {
					logger.error("Error while requesting the current status of the operation to set new tags (" + tags
							+ ") for project " + projectId + ", zone " + zone + " and for instance " + instanceName
							+ " - request returned null");
					return false;
				}

				// Assumption: operation was successful if progress == 100% and status == DONE
				if (setTagsOperation.getProgress() == 100) {
					if (setTagsOperation.getStatus().equals("DONE")) {
						logger.info("Successfully updated tags on instance " + instanceName + " from project "
								+ projectId + " in zone " + zone + ": " + tags);
						return true;
					} else {
						logger.error("Error updating the network tags (" + tags + ") for project " + projectId
								+ ", zone " + zone + " and for instance " + instanceName
								+ " - progress is at 100% but the status doesn't equal 'DONE'"
								+ jsonMapper.writeValueAsString(setTagsOperation));
						return false;
					}
				}
			}

			// At this point the timeout has been reached - giving up - counting as creation
			// error
			logger.error("Timeout while trying to update the network tags for instance " + instanceName
					+ " within project " + projectId + ", zone " + zone + ": " + tags);
			return false;

		} catch (Exception ex) {
			logger.error("Error while trying to set the tags on instance " + instanceName + " from project " + projectId
					+ " and zone " + zone, ex);
			return false;
		}
	}

	public ProjectBillingInfo retrieveBillingInfo(String projectId) {

		Cloudbilling billingConnection = billingConnections.get(projectId);
		if (billingConnection == null) {
			logger.warn(
					"Cannot retrieve any billing info since there is no billing connection for project " + projectId);
			return null;
		}

		logger.debug("Retrieving billing info from project " + projectId);

		try {

			Cloudbilling.Projects.GetBillingInfo request = billingConnection.projects()
					.getBillingInfo("projects/" + projectId);
			ProjectBillingInfo response = request.execute();

			if (response == null) {
				logger.info("No billing info found for project with id " + projectId);
				return null;
			}

			// logger.debug("Successfully retrieved billing info: " +
			// response.toPrettyString());
			logger.debug("Successfully retrieved billing info: " + jsonMapper.writeValueAsString(response));

			// Retrieve all regions for the given project
			Cloudbilling.Services.List cloudServicesRequest = billingConnection.services().list();
			ListServicesResponse responseCloudServices = cloudServicesRequest.execute();

			if (responseCloudServices == null) {
				logger.info("No billing info found on any service for project with id " + projectId);
				return null;
			}

			Service computeEngineService = null;

			for (Map.Entry<String, Object> entry : responseCloudServices.entrySet()) {
				if (entry.getKey().equalsIgnoreCase("services")) {
					logger.debug("Next set of services: " + jsonMapper.writeValueAsString(entry));
					List<Service> services = (List<Service>) entry.getValue();

					for (Service service : services) {
						if (service.getDisplayName().equalsIgnoreCase("Compute Engine"))
							;
						computeEngineService = service;
						break;
					}

				}
			}

			if (computeEngineService == null) {
				logger.warn("Could not retrieve the service object for the 'Compute Engine' service");
				return null;
			}

			ListSkusResponse responseComputeEngineSKUs = billingConnection.services().skus()
					.list(computeEngineService.getName()).execute();

			if (responseComputeEngineSKUs == null) {
				logger.warn("Could not retrieve the SKUs for the 'Compute Engine' service");
				return null;
			}

			for (Map.Entry<String, Object> entry : responseComputeEngineSKUs.entrySet()) {
				logger.debug("Next compute engine SKU: " + jsonMapper.writeValueAsString(entry));
			}

			// if (cloudServices != null) {
			// cloudServices.
			// while( cloudServices.iter)
			// for (service: cloudServices) {

			// }
			// logger.debug("Retrieving all SKUs for service " + );
			// }

			return response;

		} catch (Exception e) {
			logger.error("Error while trying to retrieve billing info for project with id " + projectId, e);
			return null;
		}
	}

	public ListTimeSeriesPagedResponse retrieveMetrics(String projectId) {

		MetricServiceClient metricsConnection = metricsConnections.get(projectId);
		if (metricsConnection == null) {
			logger.warn(
					"Cannot retrieve any metric info since there is no billing connection for project " + projectId);
			return null;
		}

		logger.debug("Retrieving metric info from project " + projectId);

		try {
			// Restrict time to last 20 minutes
			long startMillis = System.currentTimeMillis() - ((60 * 20) * 1000);
			TimeInterval interval = TimeInterval.newBuilder().setStartTime(Timestamps.fromMillis(startMillis))
					.setEndTime(Timestamps.fromMillis(System.currentTimeMillis())).build();

			ListTimeSeriesRequest.Builder requestBuilder = ListTimeSeriesRequest.newBuilder()
					.setName(ProjectName.of(projectId).toString())
					.setFilter("metric.type=\"compute.googleapis.com/instance/cpu/utilization\"").setInterval(interval);

			ListTimeSeriesRequest request = requestBuilder.build();

			ListTimeSeriesPagedResponse response = metricsConnection.listTimeSeries(request);

			if (response == null) {
				logger.info("No metric info found for project with id " + projectId);
				return null;
			}

			// logger.debug("Successfully retrieved billing info: " +
			// response.toPrettyString());
			logger.debug("Successfully retrieved metric info: ");
			for (TimeSeries ts : response.iterateAll()) {
				logger.debug(ts);
			}

			return response;

		} catch (Exception e) {
			logger.error("Error while trying to retrieve metric info for project with id " + projectId, e);
			return null;
		}
	}

	public Long getMaxQueryResults() {
		return maxQueryResults;
	}

	public void setMaxQueryResults(Long maxQueryResults) {
		this.maxQueryResults = maxQueryResults;
	}

	public int getTimeoutForFwOperations() {
		return timeoutForFwOperations;
	}

	public void setTimeoutForFwOperations(int timeoutForFwOperations) {
		this.timeoutForFwOperations = timeoutForFwOperations;
	}
}
