package com.babcock.openfire.plugin.rest;

import com.babcock.openfire.plugin.rest.service.JerseyWrapper;
import org.eclipse.jetty.ee8.servlet.ServletContextHandler;
import org.eclipse.jetty.ee8.servlet.ServletHolder;
import org.glassfish.jersey.servlet.ServletContainer;
import org.jivesoftware.admin.AuthCheckFilter;
import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginManager;
import org.jivesoftware.openfire.http.HttpBindManager;
import org.jivesoftware.util.JiveGlobals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Arrays;
import java.util.stream.Collectors;

public class MucRestApiPlugin implements Plugin {

    private static final Logger log = LoggerFactory.getLogger(MucRestApiPlugin.class);

    private static final String[] EXCLUDES = {
            "muc-rest-api/*",
            "muc-rest-api/messages/*",
    };

    private static final String EXCLUDE_PROPERTY  = "adminConsole.excludeFromAuth";
    private static final String WILDCARD_PROPERTY = "adminConsole.access.allow-wildcards-in-excludes";

    private ServletContextHandler context;

    @Override
    public void initializePlugin(PluginManager manager, File pluginDirectory) {

        // 1. Build a dedicated Jetty context for our plugin
        context = new ServletContextHandler(null, "/plugins/muc-rest-api", ServletContextHandler.NO_SESSIONS);
        ServletHolder holder = new ServletHolder(new ServletContainer(new JerseyWrapper()));
        context.addServlet(holder, "/messages/*");

        // 2. Register with Openfire's embedded Jetty (5.x API)
        HttpBindManager.getInstance().addJettyHandler(context);
        log.info("Registered Jersey servlet at /plugins/muc-rest-api/messages/*");

        // 3. Enable wildcard excludes (5.x property key)
        JiveGlobals.setProperty(WILDCARD_PROPERTY, "true");

        // 4. Persist and apply AuthCheckFilter excludes
        String existing = JiveGlobals.getProperty(EXCLUDE_PROPERTY, "");
        for (String exclude : EXCLUDES) {
            if (!existing.contains(exclude)) {
                existing = existing.isEmpty() ? exclude : existing + "," + exclude;
            }
        }
        JiveGlobals.setProperty(EXCLUDE_PROPERTY, existing);

        for (String exclude : EXCLUDES) {
            AuthCheckFilter.addExclude(exclude);
        }

        log.info("MucRestApi initialised.");
    }

    @Override
    public void destroyPlugin() {
        if (context != null) {
            HttpBindManager.getInstance().removeJettyHandler(context);
        }

        for (String exclude : EXCLUDES) {
            AuthCheckFilter.removeExclude(exclude);
        }

        String existing = JiveGlobals.getProperty(EXCLUDE_PROPERTY, "");
        String updated = Arrays.stream(existing.split(","))
                .map(String::trim)
                .filter(e -> !Arrays.asList(EXCLUDES).contains(e))
                .collect(Collectors.joining(","));
        JiveGlobals.setProperty(EXCLUDE_PROPERTY, updated);

        log.info("MucRestApiPlugin destroyed.");
    }
}
