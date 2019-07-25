package com.extremenetworks.hcm.gcp;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.glassfish.jersey.servlet.ServletContainer;

public class Main {

    private static final Logger logger = LogManager.getLogger(Main.class);

    // Rabbit MQ config
    public static final String RABBIT_SERVER = "rabbit-mq";
    public static final String RABBIT_QUEUE_POSTFIX_RESOURCES = ".gcp.data.resources";
    public static final String RABBIT_QUEUE_POSTFIX_BILLING = ".gcp.data.billing";

    // GCP Datastore config
    public static final String DS_ENTITY_KIND_DATA_RESOURCES = "Gcp_Data_Resources";
    public static final String DS_ENTITY_KIND_DATA_BILLING = "Gcp_Data_Billing";
    public static final String DS_ENTITY_KIND_CONFIG_ACCOUNT = "Gcp_Config_Account";
    public static final String SRC_SYS_TYPE = "gcp";

    public static void main(String[] args) {

        Server server = new Server(80);

        ServletContextHandler ctx = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);

        ctx.setContextPath("/");
        server.setHandler(ctx);

        ServletHolder serHol = ctx.addServlet(ServletContainer.class, "/gcp/*");
        serHol.setInitOrder(1);
        serHol.setInitParameter("jersey.config.server.provider.packages", "com.extremenetworks.hcm.gcp");

        try {
            server.start();
            server.join();
        } catch (Exception ex) {
            logger.error(ex);
        } finally {

            server.destroy();
        }
    }

}
