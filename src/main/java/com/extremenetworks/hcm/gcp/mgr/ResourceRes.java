package com.extremenetworks.hcm.gcp.mgr;

import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.datastore.Datastore;
import com.google.cloud.datastore.DatastoreOptions;
import com.google.cloud.datastore.Entity;
import com.google.cloud.datastore.Query;
import com.google.cloud.datastore.QueryResults;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Path("resources")
public class ResourceRes {

	private static final Logger logger = LogManager.getLogger(ResourceRes.class);
	private static ObjectMapper jsonMapper = new ObjectMapper();
	private static final JsonFactory jsonFactory = new JsonFactory();

	private final String rabbitServer = "rabbit-mq";
	private final static String RABBIT_QUEUE_NAME = "gcp.resources";
	private static Channel rabbitChannel;

	// private final String dbConnString =
	// "jdbc:mysql://hcm-mysql:3306/Resources?useSSL=false";
	// private final String dbUser = "root";
	// private final String dbPassword = "password";

	private ExecutorService executor;

	private final SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

	private final String DS_ENTITY_KIND_GCP_RESOURCES = "GCP_Resources";
	private final String DS_ENTITY_KIND_SRC_SYS_GCP = "SourceSystemGcp";

	private Datastore datastore;

	public ResourceRes() {

		try {
			ConnectionFactory factory = new ConnectionFactory();
			factory.setHost(rabbitServer);

			Connection connection = factory.newConnection();
			rabbitChannel = connection.createChannel();
			rabbitChannel.queueDeclare(RABBIT_QUEUE_NAME, false, false, false, null);

			executor = Executors.newCachedThreadPool();

			datastore = DatastoreOptions.getDefaultInstance().getService();

		} catch (Exception ex) {
			logger.error("Error setting up the 'Resources' resource", ex);
		}
	}

	/**
	 * Retrieves all resources (VMs, subnets, networks, etc.) for the given project
	 * ID from the DB
	 */
	@GET
	@Path("all")
	@Produces(MediaType.APPLICATION_JSON)
	public String retrieveAllResources(@QueryParam("tenantId") String tenantId,
			@QueryParam("projectId") String projectId) {

		String dbResourceData = retrieveDataFromDb(tenantId, projectId);

		return dbResourceData;
	}

	/**
	 * Starts a background worker that pulls all resources from the given account.
	 * This is a non-blocking REST call that just starts that worker in a separate
	 * thread and immediately responds to the caller. Once the background worker is
	 * done retrieving all data from GCP it will a) update the DB and b) publish the
	 * data to RabbitMQ
	 * 
	 * @param projectId Google cloud project ID
	 * @serialData authFileContent Content of the JSON auth file for the service
	 *             account to use
	 * @return
	 */
	@GET
	@Path("triggerUpdate")
	@Produces(MediaType.APPLICATION_JSON)
	public String triggerUpdateAllResources(@QueryParam("tenantId") String tenantId,
			@QueryParam("projectId") String projectId) {

		try {
			if (tenantId == null || tenantId.isEmpty()) {

				String msg = "Missing tenantId";
				logger.warn(msg);
				return jsonMapper.writeValueAsString(new ResourcesWebResponse(1, msg));
			}

			if (projectId == null || projectId.isEmpty()) {

				String msg = "Missing projectId";
				logger.warn(msg);
				return jsonMapper.writeValueAsString(new ResourcesWebResponse(2, msg));
			}

			// Retrieve all configured GCP source systems for this tenant
			logger.debug("Trying to retrieve the GCP source system config for tenant id " + tenantId
					+ " and project id " + projectId);

			Query<Entity> query = Query.newEntityQueryBuilder().setNamespace(tenantId)
					.setKind(DS_ENTITY_KIND_SRC_SYS_GCP).build();

			QueryResults<Entity> queryResults = datastore.run(query);

			String authFileContent = "";

			while (queryResults.hasNext()) {

				Entity srcSysEntity = queryResults.next();
				String srcSysProjectId = srcSysEntity.getString("projectId");

				if (projectId.equals(srcSysProjectId)) {
					authFileContent = srcSysEntity.getString("authFileContent");
					logger.debug("Found configured GCP source system with authFileContent" + authFileContent);
					break;
				}
			}

			if (authFileContent.isEmpty()) {

				String msg = "Could not find a configured GCP source system for tenant id " + tenantId
						+ " and project id " + projectId;
				logger.warn(msg);
				return jsonMapper.writeValueAsString(new ResourcesWebResponse(3, msg));
			}

			/* Config and start the background worker */
			logger.debug("Creating background worker to import resource data from GPC project " + projectId
					+ " for tenant " + tenantId);

			executor.execute(new ResourcesWorker(tenantId, projectId, authFileContent, RABBIT_QUEUE_NAME, rabbitChannel,
					datastore));

			return jsonMapper.writeValueAsString(
					new ResourcesWebResponse(0, "Successfully triggered an update of all resource data"));

		} catch (Exception ex) {
			String msg = "General Error";
			logger.error(msg, ex);
			String returnValue;
			try {
				returnValue = jsonMapper.writeValueAsString(new ResourcesWebResponse(4, msg));
				return returnValue;
			} catch (Exception ex2) {
				return msg;
			}
		}
	}

	/**
	 * Retrieves all resource data for the given account from the DB. Generate a
	 * JSON-formated string. Example: { "dataType": "resources", "sourceSystemType":
	 * "gcp", "sourceSystemProjectId": "418454969983", "data": [ { "lastUpdated":
	 * "2019-04-05 15:22:38", "resourceType": "Subnet", "resourceData": [ { "tags":
	 * [], "state": "available", "vpcId": "vpc-d3358ab6", ... }, ...
	 * 
	 * @param projectId
	 * @return
	 */
	private String retrieveDataFromDb(String tenantId, String projectId) {

		logger.debug("Retrieving all resource data for tenant id " + tenantId + " from GCP project " + projectId
				+ " from the DB");

		try {
			if (tenantId == null || tenantId.isEmpty()) {

				String msg = "Missing URL parameter tenantId";
				logger.warn(msg);
				return jsonMapper.writeValueAsString(new ResourcesWebResponse(1, msg));
			}

			if (projectId == null || projectId.isEmpty()) {

				String msg = "Missing URL parameter projectId";
				logger.warn(msg);
				return jsonMapper.writeValueAsString(new ResourcesWebResponse(2, msg));
			}

			// Retrieve all configured GCP source systems for this tenant
			logger.debug("Trying to retrieve the GCP source system config for tenant id " + tenantId
					+ " and project id " + projectId);

			Query<Entity> query = Query.newEntityQueryBuilder().setNamespace(tenantId)
					.setKind(DS_ENTITY_KIND_SRC_SYS_GCP).build();

			QueryResults<Entity> queryResults = datastore.run(query);

			String authFileContent = "";

			while (queryResults.hasNext()) {

				Entity srcSysEntity = queryResults.next();
				String srcSysProjectId = srcSysEntity.getString("projectId");

				if (projectId.equals(srcSysProjectId)) {
					authFileContent = srcSysEntity.getString("authFileContent");
					logger.debug("Found configured GCP source system with authFileContent" + authFileContent);
					break;
				}
			}

			if (authFileContent.isEmpty()) {

				String msg = "Could not find a configured GCP source system for tenant id " + tenantId
						+ " and project id " + projectId;
				logger.warn(msg);
				return jsonMapper.writeValueAsString(new ResourcesWebResponse(3, msg));
			}

			// Retrieve all types of resources from GCP Datastore - Firewalls, VMs, etc.
			Query<Entity> queryResources = Query.newEntityQueryBuilder().setNamespace(tenantId)
					.setKind(DS_ENTITY_KIND_GCP_RESOURCES)
					// .setFilter(CompositeFilter.and(PropertyFilter.eq("done", false),
					// PropertyFilter.eq("priority", 4)))
					.build();

			QueryResults<Entity> queryResourcesResults = datastore.run(queryResources);

			/*
			 * Start building the JSON string which contains some meta data. Example:
			 * 
			 * "dataType": "resources", "sourceSystemType": "gcp", "sourceSystemProjectId":
			 * "418454969983",
			 */
			ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
			JsonGenerator jsonGen = jsonFactory.createGenerator(outputStream, JsonEncoding.UTF8);

			jsonGen.writeStartObject();

			jsonGen.writeStringField("dataType", "resources");
			jsonGen.writeStringField("sourceSystemType", "gcp");
			jsonGen.writeStringField("sourceSystemProjectId", projectId);

			/*
			 * The "data" field will contain an array of objects. Each object will contain
			 * all data on a particular resource type
			 */
			jsonGen.writeArrayFieldStart("data");

			while (queryResourcesResults.hasNext()) {

				Entity resourceDataEntity = queryResourcesResults.next();
				String resourceType = resourceDataEntity.getString("resourceType");

				if (resourceType != null && !resourceType.isEmpty()) {

					jsonGen.writeStartObject();

					/*
					 * Per resource type, the following meta data will be written (example):
					 * "lastUpdated": "2019-04-05 15:22:38", "resourceType": "Subnet",
					 * "resourceData": [ ... list of subnets ... ]
					 */
					jsonGen.writeStringField("lastUpdated",
							dateFormatter.format(resourceDataEntity.getTimestamp("lastUpdated").toDate()));
					jsonGen.writeStringField("resourceType", resourceType);

					// The list of subnets is already stored as a JSON string in the DB
					jsonGen.writeFieldName("resourceData");
					jsonGen.writeRawValue(resourceDataEntity.getString("resourceData"));

					jsonGen.writeEndObject();
				}

			}

			// Finalize the JSON string and output stream
			jsonGen.writeEndArray();
			jsonGen.writeEndObject();

			jsonGen.close();
			outputStream.close();

			return outputStream.toString();

		} catch (Exception ex) {
			logger.error("Error retrieving all resource data from GCP Datastore", ex);
		}

		return "";
	}

	// @POST
	// @Path("triggerUpdate")
	// @Consumes(MediaType.APPLICATION_JSON)
	// @Produces(MediaType.APPLICATION_JSON)
	// public String triggerUpdateAllResources(String authFileContent,
	// @QueryParam("projectId") String projectId) {

	// try {
	// if (projectId == null || projectId.isEmpty()) {

	// String msg = "The projectId query parameter is not provided - not triggering
	// an update!";
	// logger.warn(msg);
	// return jsonMapper.writeValueAsString(new ResourcesWebResponse(1, msg));
	// }

	// /* Config and start the background worker */
	// logger.debug("Creating background worker to import compute data from GPC
	// project " + projectId);

	// executor.execute(new ResourcesWorker(projectId, authFileContent,
	// RABBIT_QUEUE_NAME, rabbitChannel));

	// return jsonMapper.writeValueAsString(
	// new ResourcesWebResponse(0, "Successfully triggered an update of all resource
	// data"));

	// } catch (Exception ex) {
	// logger.error(
	// "Error parsing parameters and trying to setup the background worker to
	// trigger an update on all resources",
	// ex);
	// return "";
	// }
	// }

	// private String retrieveDataFromDb(String projectId) {

	// logger.debug("Retrieving all resource data for GCP project " + projectId + "
	// from the DB");

	// try {
	// java.sql.Connection con = DriverManager.getConnection(dbConnString, dbUser,
	// dbPassword);

	// // Query the DB for all resource data for the given account ID
	// String query = "SELECT lastUpdated, resourceType, resourceData FROM gcp WHERE
	// projectId = '" + projectId
	// + "'";

	// Statement st = con.createStatement();
	// ResultSet rs = st.executeQuery(query);

	// /*
	// * Start building the JSON string which contains some meta data. Example:
	// *
	// * "dataType": "resources", "sourceSystemType": "gcp",
	// "sourceSystemProjectId":
	// * "418454969983",
	// */
	// ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
	// JsonGenerator jsonGen = jsonFactory.createGenerator(outputStream,
	// JsonEncoding.UTF8);

	// jsonGen.writeStartObject();

	// jsonGen.writeStringField("dataType", "resources");
	// jsonGen.writeStringField("sourceSystemType", "gcp");
	// jsonGen.writeStringField("sourceSystemProjectId", projectId);

	// /*
	// * The "data" field will contain an array of objects. Each object will contain
	// * all data on a particular resource type
	// */
	// jsonGen.writeArrayFieldStart("data");

	// while (rs.next()) {

	// String resourceType = rs.getString("resourceType");
	// logger.debug("Retrieved next DB row: " + ", lastUpdated: " +
	// rs.getString("lastUpdated")
	// + ", resourceType: " + rs.getString("resourceType") + ", resourceData: "
	// + rs.getString("resourceData").substring(0, 200) + "...");

	// if (resourceType != null && !resourceType.isEmpty()) {

	// jsonGen.writeStartObject();

	// /*
	// * Per resource type, the following meta data will be written (example):
	// * "lastUpdated": "2019-04-05 15:22:38", "resourceType": "Subnet",
	// * "resourceData": [ ... list of subnets ... ]
	// */
	// jsonGen.writeStringField("lastUpdated",
	// dateFormatter.format(rs.getTimestamp("lastUpdated")));
	// jsonGen.writeStringField("resourceType", rs.getString("resourceType"));

	// // The list of subnets is already stored as a JSON string in the DB
	// jsonGen.writeFieldName("resourceData");
	// jsonGen.writeRawValue(rs.getString("resourceData"));

	// jsonGen.writeEndObject();
	// }

	// }

	// // Finalize the JSON string and output stream
	// jsonGen.writeEndArray();
	// jsonGen.writeEndObject();

	// jsonGen.close();
	// outputStream.close();

	// return outputStream.toString();

	// } catch (Exception ex) {
	// logger.error(ex);
	// }

	// return "";
	// }

}