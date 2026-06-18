package com.babcock.openfire.plugin.rest;

import javax.ws.rs.core.Response;

/**
 * Exception thrown by the controller layer.
 *
 * Carries an HTTP {@link Response.Status} so the service layer can
 * return the correct HTTP response without coupling the controller
 * to JAX-RS specifics.
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
