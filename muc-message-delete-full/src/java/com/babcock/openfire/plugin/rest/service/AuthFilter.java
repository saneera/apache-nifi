package com.babcock.openfire.plugin.rest.service;

import org.jivesoftware.util.JiveGlobals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Priority;
import javax.ws.rs.Priorities;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.PreMatching;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;

/**
 * JAX-RS request filter — validates Authorization header before every request.
 *
 * Reads secret from ofProperty: plugin.restapi.secret
 * Same property used by the official REST API plugin.
 *
 * Header format:
 *   Authorization: yourSecret
 *   Authorization: Bearer yourSecret
 */
@PreMatching
@Priority(Priorities.AUTHORIZATION)
public class AuthFilter implements ContainerRequestFilter {

    private static final Logger log =
        LoggerFactory.getLogger(AuthFilter.class);

    @Override
    public void filter(ContainerRequestContext ctx) throws IOException {
        if (!isAuthorized(ctx.getHeaderString("Authorization"))) {
            log.warn("Unauthorized request to muc-rest-api plugin.");
            ctx.abortWith(
                Response.status(Response.Status.UNAUTHORIZED)
                    .type(MediaType.APPLICATION_JSON)
                    .entity("{\"error\":\"Unauthorized\"}")
                    .build()
            );
        }
    }

    private boolean isAuthorized(String authHeader) {
        if (authHeader == null || authHeader.isBlank()) return false;

        String token = authHeader.startsWith("Bearer ")
            ? authHeader.substring(7).trim()
            : authHeader.trim();

        String secret = JiveGlobals.getProperty("plugin.restapi.secret", "");

        if (secret.isBlank()) {
            log.warn("plugin.restapi.secret not set — denying all requests.");
            return false;
        }

        return token.equals(secret);
    }
}
