package com.babcock.openfire.plugin.rest.service;

import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;

/**
 * Bridges Openfire's plugin servlet infrastructure with Jersey 2.x.
 *
 * Why this class exists:
 *   Openfire's PluginServlet loads servlets registered in web-custom.xml.
 *   It requires them to be instances of {@code javax.servlet.GenericServlet}.
 *   Jersey's {@link ServletContainer} extends GenericServlet, so this works.
 *
 *   We subclass ServletContainer rather than listing it directly in
 *   web-custom.xml so that we can register resource classes programmatically
 *   via {@link ResourceConfig} — this avoids classpath scanning issues that
 *   occur in Openfire's plugin classloader environment.
 *
 * Jersey is bundled inside this plugin's lib/ directory (compile scope in
 * pom.xml), so it runs independently of whatever Jersey version Openfire
 * ships with. This is the same approach used by the official REST API plugin.
 *
 * Pattern: follows openfire-restAPI-plugin JerseyWrapper exactly.
 */
public class JerseyWrapper extends ServletContainer {

    private static final Logger log =
        LoggerFactory.getLogger(JerseyWrapper.class);

    /**
     * URL pattern used by:
     *   - web-custom.xml servlet mapping
     *   - MucMessageDeletePlugin to exclude from admin auth check
     *
     * Resolves to: /plugins/muc-message-delete/messages/*
     */
    public static final String SERVLET_URL = "muc-message-delete/messages/*";

    /** ResourceConfig built once at class load — thread safe. */
    private static final ResourceConfig config;

    static {
        config = new ResourceConfig();

        // ── Register JAX-RS resource classes ─────────────────────────
        config.register(DeleteMessageService.class);

        // ── Register request filter for Authorization header check ────
        config.register(AuthFilter.class);

        log.debug("JerseyWrapper: ResourceConfig built with " +
            "DeleteMessageService + AuthFilter.");
    }

    /**
     * Constructs the wrapper with the pre-built ResourceConfig.
     * Called by Openfire's PluginServlet via reflection.
     */
    public JerseyWrapper() {
        super(config);
    }

    @Override
    public void init(ServletConfig servletConfig) throws ServletException {
        log.info("JerseyWrapper initialising...");
        super.init(servletConfig);
        log.info("JerseyWrapper ready — " +
            "DELETE /plugins/muc-message-delete/messages/{{messageId}}");
    }

    @Override
    public void destroy() {
        super.destroy();
        log.info("JerseyWrapper destroyed.");
    }
}
