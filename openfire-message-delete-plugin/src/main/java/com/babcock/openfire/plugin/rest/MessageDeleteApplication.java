package com.babcock.openfire.plugin.rest;

import org.glassfish.jersey.server.ResourceConfig;

import javax.ws.rs.ApplicationPath;

/**
 * Registers all REST resources for this plugin.
 * Jersey scans this class and wires up the endpoints.
 */
@ApplicationPath("/api")
public class MessageDeleteApplication extends ResourceConfig {

    public MessageDeleteApplication() {
        // Register the REST resource
        register(MessageDeleteResource.class);

        // Enable JSON support
        register(org.glassfish.jersey.jackson.JacksonFeature.class);
    }
}
