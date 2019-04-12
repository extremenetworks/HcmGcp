package com.extremenetworks.hcm.gcp.mgr;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.glassfish.jersey.servlet.ServletContainer;

public class Main {

	private static final Logger logger = LogManager.getLogger(Main.class);
	
	
	public static void main(String[] args) {
		
		Server server = new Server(80);

        ServletContextHandler ctx = 
                new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
                
        ctx.setContextPath("/");
        server.setHandler(ctx);

        ServletHolder serHol = ctx.addServlet(ServletContainer.class, "/gcp/*");
        serHol.setInitOrder(1);
        serHol.setInitParameter("jersey.config.server.provider.packages", 
                "com.extremenetworks.hcm.gcp.mgr");

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
