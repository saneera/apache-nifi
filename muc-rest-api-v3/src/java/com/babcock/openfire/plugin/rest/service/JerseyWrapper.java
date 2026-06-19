package com.babcock.openfire.plugin.rest.service;

import org.glassfish.jersey.server.ResourceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JerseyWrapper extends ResourceConfig {

    private static final Logger log = LoggerFactory.getLogger(JerseyWrapper.class);


    public JerseyWrapper() {
        register(DeleteMessageService.class);
        // register(AuthFilter.class); // re-enable once 302 resolved
        log.debug("JerseyWrapper: ResourceConfig built.");
    }
}
