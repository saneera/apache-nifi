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
 * Uses getInstance().setServletRequestAuthenticator() — the correct
 * Openfire 5.x instance method (not static).
 */
public class MucRestApiPlugin implements Plugin {

    private static final Logger log =
        LoggerFactory.getLogger(MucRestApiPlugin.class);

    private MucRestApiAuthenticator authenticator;

    @Override
    public void initializePlugin(PluginManager manager, File pluginDirectory) {
        authenticator = new MucRestApiAuthenticator();

        // Openfire 5.x — instance method via getInstance()
        AuthCheckFilter.getInstance().setServletRequestAuthenticator(authenticator);

        log.info("MucRestApi initialised — MucRestApiAuthenticator registered. " +
            "REST endpoint: DELETE /plugins/muc-rest-api/messages/{{messageId}}");
    }

    @Override
    public void destroyPlugin() {
        // Only unregister if we are the registered one
        if (AuthCheckFilter.isServletRequestAuthenticatorInstanceOf(
                MucRestApiAuthenticator.class)) {
            AuthCheckFilter.getInstance().setServletRequestAuthenticator(null);
            log.info("MucRestApiAuthenticator unregistered.");
        }
        log.info("MucRestApiPlugin destroyed.");
    }
}
