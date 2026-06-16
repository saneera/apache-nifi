package com.babcock.openfire.plugin.rest;

import org.jivesoftware.database.DbConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * REST resource for message deletion.
 *
 * Endpoints:
 *
 *   DELETE /plugins/message-delete/api/v1/messages/{messageId}
 *          Delete a message by its messageID from ofMessageArchive.
 *
 *   DELETE /plugins/message-delete/api/v1/messages/{messageId}/room/{roomJid}
 *          Delete a message scoped to a specific room (safer).
 *
 * Authentication:
 *   Pass the Openfire admin secret in the Authorization header:
 *   Authorization: Bearer <secret>
 *   or
 *   Authorization: <secret>
 *
 * Usage from your Spring Boot service:
 *   DELETE http://<node-ip>:9090/plugins/message-delete/api/v1/messages/NJ5E1-13
 *   Authorization: yourAdminSecret
 */
@Path("/v1/messages")
@Produces(MediaType.APPLICATION_JSON)
public class MessageDeleteResource {

    private static final Logger log = LoggerFactory.getLogger(MessageDeleteResource.class);

    // ── SQL ──────────────────────────────────────────────────────────────────

    private static final String CHECK_MESSAGE_SQL =
        "SELECT messageID, toJID, body FROM ofMessageArchive " +
        "WHERE messageID = ?";

    private static final String DELETE_MESSAGE_SQL =
        "DELETE FROM ofMessageArchive WHERE messageID = ?";

    private static final String DELETE_MESSAGE_BY_ROOM_SQL =
        "DELETE FROM ofMessageArchive " +
        "WHERE messageID = ? AND toJID = ?";

    // ── Endpoints ─────────────────────────────────────────────────────────────

    /**
     * Delete a message by messageId.
     *
     * DELETE /plugins/message-delete/api/v1/messages/{messageId}
     */
    @DELETE
    @Path("/{messageId}")
    public Response deleteMessage(
            @PathParam("messageId") String messageId,
            @HeaderParam("Authorization") String authHeader) {

        if (!isAuthorized(authHeader)) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity("{\"error\": \"Unauthorized\"}")
                .build();
        }

        if (messageId == null || messageId.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity("{\"error\": \"messageId is required\"}")
                .build();
        }

        log.info("Delete message request for messageId [{}]", messageId);

        Connection conn = null;
        PreparedStatement checkStmt = null;
        PreparedStatement deleteStmt = null;
        ResultSet rs = null;

        try {
            conn = DbConnectionManager.getConnection();

            // ── Check message exists ──────────────────────────────────────
            checkStmt = conn.prepareStatement(CHECK_MESSAGE_SQL);
            checkStmt.setString(1, messageId);
            rs = checkStmt.executeQuery();

            if (!rs.next()) {
                log.warn("Message not found: [{}]", messageId);
                return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\": \"Message not found: " + messageId + "\"}")
                    .build();
            }

            String roomJid = rs.getString("toJID");
            String body    = rs.getString("body");
            log.info("Found message [{}] in room [{}] body [{}]",
                messageId, roomJid, body);

            // ── Delete ────────────────────────────────────────────────────
            deleteStmt = conn.prepareStatement(DELETE_MESSAGE_SQL);
            deleteStmt.setString(1, messageId);
            int rows = deleteStmt.executeUpdate();

            if (rows == 0) {
                return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\": \"Message not found or already deleted\"}")
                    .build();
            }

            log.info("Deleted message [{}] from room [{}]", messageId, roomJid);

            return Response.ok()
                .entity("{\"success\": true, " +
                        "\"messageId\": \"" + messageId + "\", " +
                        "\"room\": \"" + roomJid + "\"}")
                .build();

        } catch (Exception e) {
            log.error("Error deleting message [{}]", messageId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}")
                .build();
        } finally {
            DbConnectionManager.closeConnection(rs, checkStmt, conn);
            if (deleteStmt != null) {
                try { deleteStmt.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Delete a message scoped to a specific room.
     * Safer — prevents deleting a message from the wrong room.
     *
     * DELETE /plugins/message-delete/api/v1/messages/{messageId}/room/{roomJid}
     */
    @DELETE
    @Path("/{messageId}/room/{roomJid}")
    public Response deleteMessageByRoom(
            @PathParam("messageId") String messageId,
            @PathParam("roomJid") String roomJid,
            @HeaderParam("Authorization") String authHeader) {

        if (!isAuthorized(authHeader)) {
            return Response.status(Response.Status.UNAUTHORIZED)
                .entity("{\"error\": \"Unauthorized\"}")
                .build();
        }

        log.info("Delete message [{}] from room [{}]", messageId, roomJid);

        Connection conn = null;
        PreparedStatement stmt = null;

        try {
            conn = DbConnectionManager.getConnection();
            stmt = conn.prepareStatement(DELETE_MESSAGE_BY_ROOM_SQL);
            stmt.setString(1, messageId);
            stmt.setString(2, roomJid);
            int rows = stmt.executeUpdate();

            if (rows == 0) {
                return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\": \"Message not found in room\"}")
                    .build();
            }

            log.info("Deleted message [{}] from room [{}]", messageId, roomJid);

            return Response.ok()
                .entity("{\"success\": true, " +
                        "\"messageId\": \"" + messageId + "\", " +
                        "\"room\": \"" + roomJid + "\"}")
                .build();

        } catch (Exception e) {
            log.error("Error deleting message [{}] from room [{}]",
                messageId, roomJid, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity("{\"error\": \"" + e.getMessage() + "\"}")
                .build();
        } finally {
            DbConnectionManager.closeConnection(null, stmt, conn);
        }
    }

    // ── Auth helper ───────────────────────────────────────────────────────────

    /**
     * Validates the Authorization header against the Openfire REST API secret.
     * Reads the secret from ofProperty — same secret as the REST API plugin.
     */
    private boolean isAuthorized(String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            return false;
        }

        // Strip "Bearer " prefix if present
        String token = authHeader.startsWith("Bearer ")
            ? authHeader.substring(7)
            : authHeader;

        // Read secret from Openfire properties
        // Same property used by the REST API plugin
        String secret = org.jivesoftware.openfire.XMPPServer
            .getInstance()
            .getServerInfo()
            .getXMPPDomain();  // fallback — replace with actual property read below

        // Read the REST API secret from ofProperty
        String restSecret = org.jivesoftware.util.JiveGlobals
            .getProperty("plugin.restapi.secret", "");

        return token.equals(restSecret);
    }
}
