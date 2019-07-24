package com.extremenetworks.hcm.gcp.billing;

import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import com.extremenetworks.hcm.gcp.AccountConfig;
import com.extremenetworks.hcm.gcp.GoogleComputeEngineManager;
import com.extremenetworks.hcm.gcp.Main;
import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.Timestamp;
import com.google.cloud.datastore.Datastore;
import com.google.cloud.datastore.Entity;
import com.google.cloud.datastore.Key;
import com.google.cloud.datastore.PathElement;
import com.google.cloud.datastore.StringValue;
import com.rabbitmq.client.Channel;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class BillingWorker implements Runnable {

	private static final Logger logger = LogManager.getLogger(BillingWorker.class);
	private static ObjectMapper jsonMapper = new ObjectMapper();

	private AccountConfig accountConfig;

	// Rabbit MQ config
	private String RABBIT_QUEUE_NAME;
	private Channel rabbitChannel;

	// Datastore connection
	private Datastore datastore;

	// Helpers / Utilities
	private static final JsonFactory jsonFactory = new JsonFactory();
	private final SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

	private enum BILLING_TYPES {
		DailyCosts
	}

	public BillingWorker(AccountConfig accountConfig, String startDate, String endDate, String RABBIT_QUEUE_NAME,
			Channel rabbitChannel, Datastore datastore) {

		this.accountConfig = accountConfig;

		this.RABBIT_QUEUE_NAME = RABBIT_QUEUE_NAME;
		this.rabbitChannel = rabbitChannel;

		// Datastore connection
		this.datastore = datastore;
	}

	@Override
	public void run() {

		logger.debug("Starting Background worker to import billing data from GCP for account: "
				+ accountConfig.toString() + ". Project id: " + accountConfig.getProjectId());

		try {
			GoogleComputeEngineManager computeManager = new GoogleComputeEngineManager();
			boolean connected = computeManager.createComputeConnection(accountConfig.getProjectId(),
					accountConfig.getCredentialsFileContent());

			if (!connected) {
				String msg = "Won't be able to retrieve any data from Google Compute Engine since no authentication/authorization/connection could be established";
				logger.error(msg);
				rabbitChannel.basicPublish("", RABBIT_QUEUE_NAME, null, msg.getBytes("UTF-8"));
				return;
			}

			computeManager.retrieveBillingInfo(accountConfig.getProjectId());

			// writeToDb(dbConn, "Firewall", allFirewalls);
			// publishToRabbitMQ("Firewall", allFirewalls);

			logger.debug("Finished retrieving all billing data from GCP project " + accountConfig.getProjectId());

		} catch (Exception ex) {
			logger.error(ex);
			return;
		}
	}

	/**
	 * Writes the given data (daily costs, etc.) to the DB
	 * 
	 * @param billingType Valid types: DailyCosts, etc.
	 * @param data        Map of billing data. The values can contain any type of
	 *                    object and will be written to JSON data and then stored in
	 *                    the DB
	 * @return
	 */
	private boolean writeToDb(BILLING_TYPES billingType, List<Object> data) {

		try {
			// The name/ID for the new entity
			String name = billingType.name();

			// The Cloud Datastore key for the new entity
			Key entityKey = datastore.newKeyFactory().setNamespace(accountConfig.getTenantId())
					.setKind(Main.DS_ENTITY_KIND_DATA_BILLING)
					.addAncestor(PathElement.of(Main.DS_ENTITY_KIND_CONFIG_ACCOUNT, accountConfig.getAccountId()))
					.newKey(name);

			Entity dataEntity = Entity.newBuilder(entityKey).set("lastUpdated", Timestamp.now())
					.set("billingType", billingType.name()).set("billingData", StringValue
							.newBuilder(jsonMapper.writeValueAsString(data)).setExcludeFromIndexes(true).build())
					.build();

			logger.debug("About to update / write this entity towards GCP datastore:"
					+ jsonMapper.writeValueAsString(dataEntity));

			// Saves the entity
			datastore.put(dataEntity);

			return true;

		} catch (Exception ex) {
			logger.error("Error trying to store billing data within GCP Datastore", ex);
			return false;
		}
	}

	private boolean publishToRabbitMQ(BILLING_TYPES billingType, List<Object> data) {

		try {
			Date now = new Date();

			ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
			JsonGenerator jsonGen = jsonFactory.createGenerator(outputStream, JsonEncoding.UTF8);

			jsonGen.writeStartObject();

			jsonGen.writeStringField("dataType", "billing");
			jsonGen.writeStringField("sourceSystemType", Main.SRC_SYS_TYPE);
			// jsonGen.writeStringField("sourceSystemProjectId", appId);

			jsonGen.writeArrayFieldStart("data");

			jsonGen.writeStartObject();

			jsonGen.writeStringField("lastUpdated", dateFormatter.format(now));
			jsonGen.writeStringField("billingType", billingType.name());
			jsonGen.writeFieldName("billingData");

			jsonGen.writeStartArray();
			jsonGen.writeRawValue(jsonMapper.writeValueAsString(data));
			jsonGen.writeEndArray();

			jsonGen.writeEndObject();

			jsonGen.writeEndArray();
			jsonGen.writeEndObject();

			jsonGen.close();
			outputStream.close();

			logger.debug("Forwarding updated list of " + billingType + "s to the message queue " + RABBIT_QUEUE_NAME);
			rabbitChannel.basicPublish("", RABBIT_QUEUE_NAME, null, outputStream.toString().getBytes("UTF-8"));

			return true;

		} catch (Exception ex) {
			logger.error("Error trying to publish billing data to RabbitMQ", ex);
			return false;
		}

	}

}
