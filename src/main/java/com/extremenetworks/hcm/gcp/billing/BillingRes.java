package com.extremenetworks.hcm.gcp.billing;

import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

import com.extremenetworks.hcm.gcp.AccountConfig;
import com.extremenetworks.hcm.gcp.Main;
import com.extremenetworks.hcm.gcp.utils.Utilities;
import com.extremenetworks.hcm.gcp.utils.WebResponse;
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

@Path("billing")
public class BillingRes {

	private static final Logger logger = LogManager.getLogger(BillingRes.class);
	private static ObjectMapper jsonMapper = new ObjectMapper();
	private static final JsonFactory jsonFactory = new JsonFactory();

	private static Channel rabbitChannel;
	private HashSet<String> declaredRabbitQueues = new HashSet<String>();

	// Datastore connection
	private Datastore datastore;

	private ExecutorService executor;

	private final SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

	public BillingRes() {

		try {
			ConnectionFactory factory = new ConnectionFactory();
			factory.setHost(Main.RABBIT_SERVER);

			Connection connection = factory.newConnection();
			rabbitChannel = connection.createChannel();

			executor = Executors.newCachedThreadPool();

			datastore = DatastoreOptions.getDefaultInstance().getService();

		} catch (Exception ex) {
			logger.error("Error setting up the 'Resources' resource", ex);
		}
	}

	/**
	 * Retrieves all billing data for the given tenant and account from the DB.
	 * Generate a JSON-formated string.
	 * 
	 * @param tenantId
	 * @param accountId
	 * @return
	 */
	@GET
	@Produces(MediaType.APPLICATION_JSON)
	@Path("all")
	public String retrieveAllBilling(@QueryParam("tenantId") String tenantId,
			@QueryParam("accountId") String accountId) {

		try {
			/* Retrieve the config for the given tenant & account from Datastore */
			AccountConfig accountConfig = new AccountConfig();
			String accountValidationMsg = Utilities.retrieveAccountConfigFromDb(tenantId, accountId, accountConfig,
					datastore, Main.DS_ENTITY_KIND_CONFIG_ACCOUNT);

			if (!accountValidationMsg.isEmpty()) {
				return accountValidationMsg;
			}

			logger.debug("Retrieving all billing data for tenant " + tenantId + " and configured AWS account "
					+ accountId + " from GCP Datastore");

			// Retrieve all types of billing data from GCP Datastore - daily costs, etc.
			Query<Entity> queryBilling = Query.newEntityQueryBuilder().setNamespace(tenantId)
					.setKind(Main.DS_ENTITY_KIND_DATA_BILLING).build();

			QueryResults<Entity> queryBillingResults = datastore.run(queryBilling);

			/*
			 * Start building the JSON string which contains some meta data. Example:
			 * 
			 * "dataType": "billing", "sourceSystemType": "aws", "sourceSystemAccountId":
			 * "418454969983",
			 */
			ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
			JsonGenerator jsonGen = jsonFactory.createGenerator(outputStream, JsonEncoding.UTF8);

			jsonGen.writeStartObject();

			jsonGen.writeStringField("dataType", "billing");
			jsonGen.writeStringField("sourceSystemType", Main.SRC_SYS_TYPE);
			jsonGen.writeStringField("sourceSystemTenantId", tenantId);
			jsonGen.writeStringField("sourceSystemAccountId", accountId);

			/*
			 * The "data" field will contain an array of objects. Each object will contain
			 * all data on a particular billing type
			 */
			jsonGen.writeArrayFieldStart("data");

			while (queryBillingResults.hasNext()) {

				Entity billingDataEntity = queryBillingResults.next();
				String billingType = billingDataEntity.getString("billingType");

				if (billingType != null && !billingType.isEmpty()) {

					jsonGen.writeStartObject();

					/*
					 * Per billing type, the following meta data will be written (example):
					 * "lastUpdated": "2019-04-05 15:22:38", "billingType": "Subnet", "billingData":
					 * [ ... list of subnets ... ]
					 */
					jsonGen.writeStringField("lastUpdated",
							dateFormatter.format(billingDataEntity.getTimestamp("lastUpdated").toDate()));
					jsonGen.writeStringField("billingType", billingDataEntity.getString("billingType"));

					// The list of subnets is already stored as a JSON string in the DB
					jsonGen.writeFieldName("billingData");
					jsonGen.writeRawValue(billingDataEntity.getString("billingData"));

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
			String msg = "General Error";
			logger.error(msg, ex);
			String returnValue;
			try {
				returnValue = jsonMapper.writeValueAsString(new WebResponse(6, msg));
				return returnValue;
			} catch (Exception ex2) {
				return msg;
			}
		}
	}

	/**
	 * Starts a background worker that pulls all resources from the given account.
	 * This is a non-blocking REST call that just starts that worker in a separate
	 * thread and immediately responds to the caller. Once the background worker is
	 * done retrieving all data from AWS it will - update the DB - publish the data
	 * to RabbitMQ
	 * 
	 * @param accountId
	 * @param accessKeyId
	 * @param accessKeySecret
	 * @return
	 */
	@GET
	@Produces(MediaType.APPLICATION_JSON)
	@Path("triggerUpdate")
	public String triggerUpdateAll(@QueryParam("tenantId") String tenantId, @QueryParam("accountId") String accountId,
			@QueryParam("startDate") String startDate, @QueryParam("endDate") String endDate) {

		try {
			/* Retrieve the config for the given tenant & account from Datastore */
			AccountConfig accountConfig = new AccountConfig();
			String accountValidationMsg = Utilities.retrieveAccountConfigFromDb(tenantId, accountId, accountConfig,
					datastore, Main.DS_ENTITY_KIND_CONFIG_ACCOUNT);

			if (!accountValidationMsg.isEmpty()) {
				return accountValidationMsg;
			}

			String rabbitQueueName = tenantId + Main.RABBIT_QUEUE_POSTFIX_BILLING;
			if (!declaredRabbitQueues.contains(rabbitQueueName)) {
				logger.info("Declaring new Rabbit MQ queue name: " + rabbitQueueName);
				rabbitChannel.queueDeclare(rabbitQueueName, false, false, false, null);
				declaredRabbitQueues.add(rabbitQueueName);
			}

			if (startDate == null || startDate.isEmpty() || endDate == null || endDate.isEmpty()) {
				String msg = "Missing startDate and / or endDate parameters";
				logger.warn(msg);
				return jsonMapper.writeValueAsString(new WebResponse(1, msg));
			}

			/* Config and start the background worker */
			logger.debug(
					"Creating background worker to import billing data from AWS account: " + accountConfig.toString());

			executor.execute(
					new BillingWorker(accountConfig, startDate, endDate, rabbitQueueName, rabbitChannel, datastore));

			return jsonMapper
					.writeValueAsString(new WebResponse(0, "Successfully triggered an update of all billing data"));

		} catch (Exception ex) {
			logger.error("General error triggering billing data update", ex);
			return "";
		}
	}

}