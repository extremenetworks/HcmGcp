package com.extremenetworks.hcm.gcp.mgr;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.services.compute.model.Firewall;
import com.google.api.services.compute.model.FirewallList;
import com.google.api.services.compute.model.InstanceList;
import com.google.api.services.compute.model.NetworkList;
import com.google.api.services.compute.model.RegionList;
import com.google.api.services.compute.model.SubnetworkList;
import com.google.api.services.compute.model.ZoneList;
import com.google.cloud.monitoring.v3.MetricServiceClient.ListTimeSeriesPagedResponse;
import com.google.api.services.cloudbilling.model.ProjectBillingInfo;

public class GoogleComputeEngineManager {

	private static final Logger logger = LogManager.getLogger(GoogleComputeEngineManager.class);

	ObjectMapper jsonMapper = new ObjectMapper();

	private GoogleComputeEngineApi computeApi;

	public enum AreRulesEqualResult {
		EQUAL, NOT_EQUAL_NAME, NOT_EQUAL_NETWORK, NOT_EQUAL_DIRECTION, NOT_EQUAL_ALLOWED_FILTERS,
		NOT_EQUAL_DENIED_FILTERS, NOT_EQUAL_SOURCE_RANGES, NOT_EQUAL_DESTINATON_RANGES, NOT_EQUAL_NULL, NOT_EQUAL_ERROR
	}

	public GoogleComputeEngineManager() {

		computeApi = new GoogleComputeEngineApi();
	}

	public boolean createComputeConnection(String projectId, String authFileContent) {
		return computeApi.createComputeConnection(projectId, authFileContent);
	}

	public List<Object> retrieveAllZones(String projectId) {
		return computeApi.retrieveAllZones(projectId);
	}

	public List<Object> retrieveAllRegions(String projectId) {
		return computeApi.retrieveAllRegions(projectId);
	}

	public List<Object> retrieveAllNetworks(String projectId) {
		return computeApi.retrieveAllNetworks(projectId);
	}

	public List<Object> retrieveInstancesForZone(String projectId, String zoneName) {
		return computeApi.retrieveInstancesForZone(projectId, zoneName);
	}

	public List<Object> retrieveSubnetworksForRegion(String projectId, String regionName) {
		return computeApi.retrieveSubnetworksForRegion(projectId, regionName);
	}

	public ProjectBillingInfo retrieveBillingInfo(String projectId) {
		return computeApi.retrieveBillingInfo(projectId);
	}

	public ListTimeSeriesPagedResponse retrieveMetrics(String projectId) {
		return computeApi.retrieveMetrics(projectId);
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
		return computeApi.retrieveFirewalls(projectId, vpcName, onlyRetrieveManagedRules);
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
		return computeApi.createFirewallRule(projectId, fwRule);
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
		return computeApi.updateFirewallRule(projectId, fwRule);
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
		return computeApi.deleteFirewallRule(projectId, fwRuleName);
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
		return computeApi.setInstanceTags(projectId, zone, instanceName, tags);
	}

	public Long getMaxQueryResults() {
		return computeApi.getMaxQueryResults();
	}

	public void setMaxQueryResults(Long maxQueryResults) {
		computeApi.setMaxQueryResults(maxQueryResults);
	}

	public int getTimeoutForFwOperations() {
		return computeApi.getTimeoutForFwOperations();
	}

	public void setTimeoutForFwOperations(int timeoutForFwOperations) {
		computeApi.setTimeoutForFwOperations(timeoutForFwOperations);
	}
}
