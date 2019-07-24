package com.extremenetworks.hcm.gcp.utils;

import com.extremenetworks.hcm.gcp.AccountConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.datastore.Datastore;
import com.google.cloud.datastore.Entity;
import com.google.cloud.datastore.Query;
import com.google.cloud.datastore.QueryResults;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Utilities {

    private static final Logger logger = LogManager.getLogger(Utilities.class);
    private static ObjectMapper jsonMapper = new ObjectMapper();

    /**
     * Retrieves the account config for the given tenant and account from Datastore.
     * Stores the matching account config in the provided accountConfig parameter.
     * Also validates the given tenantId and accountId params.
     * 
     * @param tenantId      Extreme Networks configured tenant id
     * @param accountId     Extreme Networks configured account id
     * @param accountConfig Empty, instantiated AccountConfig object that will be
     *                      populated with the account config if found in Datastore
     * @return An empty string if no error occured. If there was a problem, it
     *         returns a JSON-configured string that can be used as an HTTP reponse.
     */
    public static String retrieveAccountConfigFromDb(String tenantId, String accountId, AccountConfig accountConfig,
            Datastore datastore, String dsEntityKind) {

        try {
            if (tenantId == null || tenantId.isEmpty()) {

                String msg = "Missing URL parameter tenantId";
                logger.warn(msg);
                return jsonMapper.writeValueAsString(new WebResponse(1, msg));
            }

            if (accountId == null || accountId.isEmpty()) {

                String msg = "Missing URL parameter accountId";
                logger.warn(msg);
                return jsonMapper.writeValueAsString(new WebResponse(2, msg));
            }

            // Retrieve all configured GCP source systems for this tenant
            logger.debug("Retrieving config for tenant id " + tenantId + " and account id " + accountId);

            Query<Entity> query = Query.newEntityQueryBuilder().setNamespace(tenantId).setKind(dsEntityKind).build();

            QueryResults<Entity> queryResults = datastore.run(query);

            while (queryResults.hasNext()) {

                Entity srcSysEntity = queryResults.next();
                String srcSysAccountId = srcSysEntity.getKey().getName();

                // Try to match the given account id with the configured account (Entity key
                // name == accountId)
                if (accountId.equals(srcSysAccountId)) {

                    if (srcSysEntity.isNull("projectId") || srcSysEntity.isNull("credentialsFileContent")) {
                        String msg = "Found account config but it is missing property projectId and/or credentialsFileContent";
                        logger.warn(msg);
                        return jsonMapper.writeValueAsString(new WebResponse(3, msg));
                    }

                    if (srcSysEntity.getString("projectId").isEmpty()
                            || srcSysEntity.getString("credentialsFileContent").isEmpty()) {
                        String msg = "Found account config but property projectId and/or credentialsFileContent is empty";
                        logger.warn(msg);
                        return jsonMapper.writeValueAsString(new WebResponse(4, msg));
                    }

                    accountConfig.setTenantId(tenantId);
                    accountConfig.setAccountId(accountId);
                    accountConfig.setProjectId(srcSysEntity.getString("projectId"));
                    accountConfig.setCredentialsFileContent(srcSysEntity.getString("credentialsFileContent"));

                    logger.debug("Found configured source system: " + accountConfig.toString());
                    return "";
                }
            }

            String msg = "Could not find a configured source system for tenant id " + tenantId + ", account id "
                    + accountId + " and entity kind " + dsEntityKind;
            logger.warn(msg);
            return jsonMapper.writeValueAsString(new WebResponse(6, msg));

        } catch (Exception ex) {
            String msg = "General Error";
            logger.error(msg, ex);
            String returnValue;
            try {
                returnValue = jsonMapper.writeValueAsString(new WebResponse(7, msg));
                return returnValue;
            } catch (Exception ex2) {
                return msg;
            }
        }
    }
}