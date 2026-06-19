package com.babcock.openfire.plugin.rest;

import javax.ws.rs.core.Response;

/**
 * Exception thrown by the controller layer.
 * Carries an HTTP status so the service layer can return
 * the correct response without coupling controller to JAX-RS.
 */
public class ServiceException extends Exception {

    private final Response.Status status;

    public ServiceException(String message, Response.Status status) {
        super(message);
        this.status = status;
    }

    public Response.Status getStatus() {
        return status;
    }
}
