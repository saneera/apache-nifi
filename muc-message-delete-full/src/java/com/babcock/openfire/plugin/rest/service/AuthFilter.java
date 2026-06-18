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
 * JAX-RS container request filter that validates the Authorization header.
 *
 * Runs before every request hits the resource class.
 * Returns 401 Unauthorized if the secret does not match or is absent.
 *
 * Secret is read from Openfire's ofProperty table:
 *   name  = plugin.restapi.secret
 *   value = yourSecret
 *
 * This is the same property used by the official REST API plugin, so
 * no separate configuration is needed if that plugin is also installed.
 *
 * Header format accepted:
 *   Authorization: yourSecret
 *   Authorization: Bearer yourSecret
 */
@PreMatching
@Priority(Priorities.AUTHORIZATION)
public class AuthFilter implements ContainerRequestFilter {

    private static final Logger log =
        LoggerFactory.getLogger(AuthFilter.class);

    private static final String AUTH_PROPERTY = "plugin.restapi.secret";

    @Override
    public void filter(ContainerRequestContext ctx) throws IOException {
        String authHeader = ctx.getHeaderString("Authorization");

        if (!isAuthorized(authHeader)) {
            log.warn("Unauthorized request rejected by muc-message-delete plugin.");
            ctx.abortWith(
                Response.status(Response.Status.UNAUTHORIZED)
                    .type(MediaType.APPLICATION_JSON)
                    .entity("{\"error\": \"Unauthorized — " +
                        "provide Authorization header matching " +
                        AUTH_PROPERTY + "\"}")
                    .build()
            );
        }
    }

    /**
     * Validates the Authorization header value against the stored secret.
     *
     * @param authHeader raw value of the Authorization header
     * @return true if valid, false otherwise
     */
    private boolean isAuthorized(String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            return false;
        }

        // Accept both "Bearer <token>" and raw "<token>"
        String token = authHeader.startsWith("Bearer ")
            ? authHeader.substring(7).trim()
            : authHeader.trim();

        String secret = JiveGlobals.getProperty(AUTH_PROPERTY, "");

        if (secret.isBlank()) {
            log.warn("{} is not set in ofProperty — all requests denied.", AUTH_PROPERTY);
            return false;
        }

        return token.equals(secret);
    }
}
