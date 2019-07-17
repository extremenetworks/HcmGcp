package com.extremenetworks.hcm.gcp.mgr;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
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

	private final String GCP_DS_ENTITY_KIND_GCP_RESOURCES = "GCP_Resources";

	public ResourceRes() {

		try {
			ConnectionFactory factory = new ConnectionFactory();
			factory.setHost(rabbitServer);

			Connection connection = factory.newConnection();
			rabbitChannel = connection.createChannel();
			rabbitChannel.queueDeclare(RABBIT_QUEUE_NAME, false, false, false, null);

			executor = Executors.newCachedThreadPool();

		} catch (Exception ex) {
			logger.error("Error setting up the 'Resources' resource", ex);
		}
	}

	/**
	 * Retrieves all resources (VMs, subnets, networks, etc.) for the given project
	 * ID from the DB
	 */
	@POST
	@Path("all")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public String retrieveAllResources(String authFileContent, @QueryParam("projectId") String projectId) {

		String dbResourceData = retrieveDataFromDb(projectId, authFileContent);

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
	@POST
	@Path("triggerUpdate")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public String triggerUpdateAllResources(String authFileContent, @QueryParam("projectId") String projectId) {

		try {
			if (projectId == null || projectId.isEmpty()) {

				String msg = "The projectId query parameter is not provided - not triggering an update!";
				logger.warn(msg);
				return jsonMapper.writeValueAsString(new ResourcesWebResponse(1, msg));
			}

			/* Config and start the background worker */
			logger.debug("Creating background worker to import compute data from GPC project " + projectId);

			executor.execute(new ResourcesWorker(projectId, authFileContent, RABBIT_QUEUE_NAME, rabbitChannel));

			return jsonMapper.writeValueAsString(
					new ResourcesWebResponse(0, "Successfully triggered an update of all resource data"));

		} catch (Exception ex) {
			logger.error(
					"Error parsing parameters and trying to setup the background worker to trigger an update on all resources",
					ex);
			return "";
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
	private String retrieveDataFromDb(String projectId, String authFileContent) {

		logger.debug("Retrieving all resource data for GCP project " + projectId + " from the DB");

		try {
			/* Load the JSON credentials file content */
			GoogleCredentials gcpCredentials = null;

			try {
				InputStream credFileInputStream = new ByteArrayInputStream(
						authFileContent.getBytes(StandardCharsets.UTF_8));
				gcpCredentials = GoogleCredentials.fromStream(credFileInputStream);

			} catch (Exception ex) {
				logger.error("Error loading the credentials JSON file content for authorizing against the GCP project "
						+ projectId, ex);
				return "";
			}

			Datastore datastore;

			try {
				datastore = DatastoreOptions.newBuilder().setCredentials(gcpCredentials).setProjectId(projectId).build()
						.getService();

			} catch (Exception e) {
				logger.error("Error while trying to setup the 'compute engine' connection for project " + projectId, e);
				return "";
			}

			// Query to retrieve all types of entities from GCP Datastore - Firewalls, VMs,
			// etc.
			Query<Entity> query = Query.newEntityQueryBuilder().setKind(GCP_DS_ENTITY_KIND_GCP_RESOURCES)
					// .setFilter(CompositeFilter.and(PropertyFilter.eq("done", false),
					// PropertyFilter.eq("priority", 4)))
					.build();

			// Retrieve all resource data
			QueryResults<Entity> queryResults = datastore.run(query);

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

			while (queryResults.hasNext()) {

				Entity resourceDataEntity = queryResults.next();
				String resourceType = resourceDataEntity.getString("resourceType");

				// logger.debug("Retrieved next DB entity: " + ", lastUpdated: "
				// + resourceDataEntity.getTimestamp("lastUpdated") + ", resourceType: " +
				// resourceType
				// + ", resourceData: " +
				// resourceDataEntity.getString("resourceData").substring(0, 200) + "...");

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