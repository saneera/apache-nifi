package com.babcock.openfire.plugin.rest;

import org.jivesoftware.admin.ServletRequestAuthenticator;
import org.jivesoftware.util.JiveGlobals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;

/**
 * Authenticates requests to this plugin via Authorization header.
 * Registered with AuthCheckFilter to bypass the admin login redirect.
 *
 * Openfire 5.x uses authenticateRequest() — not isAuthenticated().
 */
public class MucRestApiAuthenticator implements ServletRequestAuthenticator {

    private static final Logger log =
        LoggerFactory.getLogger(MucRestApiAuthenticator.class);

    @Override
    public boolean authenticateRequest(HttpServletRequest request) {
        String path = request.getRequestURI();

        // Only handle our plugin URLs
        if (!path.contains("/plugins/muc-rest-api/")) {
            return false;
        }

        // Validate Authorization header
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || authHeader.isBlank()) {
            log.warn("Missing Authorization header for [{}]", path);
            return false;
        }

        String token = authHeader.startsWith("Bearer ")
            ? authHeader.substring(7).trim()
            : authHeader.trim();

        String secret = JiveGlobals.getProperty("plugin.restapi.secret", "");

        if (secret.isBlank()) {
            log.warn("plugin.restapi.secret not set — denying [{}]", path);
            return false;
        }

        boolean valid = token.equals(secret);
        log.debug("Auth [{}]: {}", path, valid ? "PASS" : "FAIL");
        return valid;
    }
}
