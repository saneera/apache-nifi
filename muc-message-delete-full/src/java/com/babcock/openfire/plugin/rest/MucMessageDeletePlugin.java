package com.babcock.openfire.plugin.rest;

import com.babcock.openfire.plugin.rest.service.JerseyWrapper;
import org.jivesoftware.admin.AuthCheckFilter;
import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * Openfire plugin entry point.
 *
 * Loaded by Openfire's PluginManager when the JAR is placed in the
 * plugins/ directory.
 *
 * Responsibilities:
 *   - Exclude the plugin URL from Openfire's admin auth check filter
 *     so that the plugin can handle its own auth via Authorization header.
 *   - Register the URL exclusion on init and remove on destroy.
 */
public class MucMessageDeletePlugin implements Plugin {

    private static final Logger log =
        LoggerFactory.getLogger(MucMessageDeletePlugin.class);

    @Override
    public void initializePlugin(PluginManager manager, File pluginDirectory) {
        // Allow requests to this plugin URL without admin session —
        // auth is handled by AuthFilter using plugin.restapi.secret
        AuthCheckFilter.addExclude(JerseyWrapper.SERVLET_URL);

        log.info("MucMessageDeletePlugin initialised. " +
            "REST endpoint: DELETE /plugins/muc-message-delete/messages/{{messageId}}");
    }

    @Override
    public void destroyPlugin() {
        AuthCheckFilter.removeExclude(JerseyWrapper.SERVLET_URL);
        log.info("MucMessageDeletePlugin destroyed.");
    }
}
