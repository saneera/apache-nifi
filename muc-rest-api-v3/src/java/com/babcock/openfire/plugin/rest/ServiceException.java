package com.babcock.openfire.plugin.rest;

import javax.ws.rs.core.Response;

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
