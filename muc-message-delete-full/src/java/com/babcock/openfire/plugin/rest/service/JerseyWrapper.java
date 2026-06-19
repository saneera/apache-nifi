package com.babcock.openfire.plugin.rest.service;

import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;

/**
 * Bridges Openfire's Jetty servlet infrastructure with Jersey 2.x.
 *
 * Auth is now handled by MucRestApiAuthenticator at the AuthCheckFilter level
 * before the request even reaches Jersey. AuthFilter is not needed here.
 */
public class JerseyWrapper extends ServletContainer {

    private static final Logger log =
        LoggerFactory.getLogger(JerseyWrapper.class);

    public static final String SERVLET_URL = "plugins/muc-rest-api/*";

    private static final ResourceConfig config;

    static {
        config = new ResourceConfig();
        config.register(DeleteMessageService.class);
        // Auth handled by MucRestApiAuthenticator — not needed here
        // config.register(AuthFilter.class);
        log.debug("JerseyWrapper: ResourceConfig built.");
    }

    public JerseyWrapper() {
        super(config);
    }

    @Override
    public void init(ServletConfig servletConfig) throws ServletException {
        log.info("JerseyWrapper initialising...");
        super.init(servletConfig);
        log.info("JerseyWrapper ready.");
    }

    @Override
    public void destroy() {
        super.destroy();
        log.info("JerseyWrapper destroyed.");
    }
}
