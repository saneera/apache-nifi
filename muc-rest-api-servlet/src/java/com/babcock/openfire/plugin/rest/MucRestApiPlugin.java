package com.babcock.openfire.plugin.rest;

import org.jivesoftware.admin.AuthCheckFilter;
import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginManager;
import org.jivesoftware.util.JiveGlobals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Plugin entry point.
 *
 * Uses addExclude() with multiple patterns to bypass AuthCheckFilter.
 * Also persists the exclude in ofProperty via JiveGlobals so it
 * survives restarts.
 */
public class MucRestApiPlugin implements Plugin {

    private static final Logger log =
        LoggerFactory.getLogger(MucRestApiPlugin.class);

    // Try multiple patterns — one of them will match
    private static final String[] EXCLUDES = {
        "plugins/muc-rest-api/*",
        "plugins/muc-rest-api/messages/*",
        "muc-rest-api/*",
        "muc-rest-api"
    };

    private static final String EXCLUDE_PROPERTY  = "adminConsole.excludeFromAuth";
    private static final String WILDCARD_PROPERTY  = "adminConsole.allowWildcardsInExcludes";

    @Override
    public void initializePlugin(PluginManager manager, File pluginDirectory) {

        // Enable wildcards
        JiveGlobals.setProperty(WILDCARD_PROPERTY, "true");

        // Add all patterns via addExclude
        for (String exclude : EXCLUDES) {
            AuthCheckFilter.addExclude(exclude);
            log.info("Added exclude: [{}]", exclude);
        }

        // Also persist in ofProperty
        String existing = JiveGlobals.getProperty(EXCLUDE_PROPERTY, "");
        StringBuilder updated = new StringBuilder(existing);
        for (String exclude : EXCLUDES) {
            if (!existing.contains(exclude)) {
                if (updated.length() > 0) updated.append(",");
                updated.append(exclude);
            }
        }
        JiveGlobals.setProperty(EXCLUDE_PROPERTY, updated.toString());

        log.info("MucRestApiPlugin initialised. " +
            "REST endpoint: DELETE /plugins/muc-rest-api/messages/{{messageId}}");
    }

    @Override
    public void destroyPlugin() {
        for (String exclude : EXCLUDES) {
            AuthCheckFilter.removeExclude(exclude);
        }

        // Clean up ofProperty
        String existing = JiveGlobals.getProperty(EXCLUDE_PROPERTY, "");
        String cleaned = Arrays.stream(existing.split(","))
            .map(String::trim)
            .filter(e -> {
                for (String ex : EXCLUDES) {
                    if (e.equals(ex)) return false;
                }
                return true;
            })
            .collect(Collectors.joining(","));
        JiveGlobals.setProperty(EXCLUDE_PROPERTY, cleaned);

        log.info("MucRestApiPlugin destroyed.");
    }
}
