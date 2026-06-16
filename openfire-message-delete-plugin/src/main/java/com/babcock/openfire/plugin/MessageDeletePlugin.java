package com.babcock.openfire.plugin;

import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * Openfire plugin entry point.
 *
 * Openfire loads this class when the plugin JAR is placed in
 * the /plugins directory. The REST endpoint is registered via
 * web.xml — Openfire's embedded Jetty picks it up automatically.
 */
public class MessageDeletePlugin implements Plugin {

    private static final Logger log = LoggerFactory.getLogger(MessageDeletePlugin.class);

    @Override
    public void initializePlugin(PluginManager manager, File pluginDirectory) {
        log.info("MessageDeletePlugin initialized — REST endpoint available at " +
            "/plugins/message-delete/api/v1/messages/{messageId}");
    }

    @Override
    public void destroyPlugin() {
        log.info("MessageDeletePlugin destroyed.");
    }
}
