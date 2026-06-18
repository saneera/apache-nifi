package com.babcock.openfire.plugin.rest.controller;

import com.babcock.openfire.plugin.rest.ServiceException;
import org.jivesoftware.database.DbConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Business logic layer for deleting MUC messages from ofMessageArchive.
 *
 * Uses Openfire's {@link DbConnectionManager} to obtain a JDBC connection
 * from Openfire's connection pool — no separate datasource configuration needed.
 *
 * Singleton pattern follows the official REST API plugin controllers.
 *
 * Why we delete from ofMessageArchive directly:
 *   Openfire does not update ofMessageArchive when a retraction stanza
 *   is received — it only stores the retraction as a new row (with no body).
 *   Deleting via this endpoint ensures the message is truly gone from the
 *   archive so it does not appear in history replay.
 */
public class DeleteMessageController {

    private static final Logger log =
        LoggerFactory.getLogger(DeleteMessageController.class);

    // ── Singleton ─────────────────────────────────────────────────────────
    private static final DeleteMessageController INSTANCE =
        new DeleteMessageController();

    public static DeleteMessageController getInstance() {
        return INSTANCE;
    }

    private DeleteMessageController() {}

    // ── SQL ───────────────────────────────────────────────────────────────

    /** Fetch message to verify existence and capture room JID. */
    private static final String CHECK_SQL =
        "SELECT messageID, toJID FROM ofMessageArchive WHERE messageID = ?";

    /** Delete the message row. */
    private static final String DELETE_SQL =
        "DELETE FROM ofMessageArchive WHERE messageID = ?";

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Deletes a message from ofMessageArchive.
     *
     * @param messageId the stanza ID of the message to delete
     * @return the room JID (toJID) the message belonged to
     * @throws ServiceException with appropriate HTTP status on failure
     */
    public String deleteMessage(String messageId) throws ServiceException {

        if (messageId == null || messageId.isBlank()) {
            throw new ServiceException(
                "messageId must not be blank",
                Response.Status.BAD_REQUEST);
        }

        Connection         conn       = null;
        PreparedStatement  checkStmt  = null;
        PreparedStatement  deleteStmt = null;
        ResultSet          rs         = null;

        try {
            conn = DbConnectionManager.getConnection();

            // ── Check message exists ──────────────────────────────────
            checkStmt = conn.prepareStatement(CHECK_SQL);
            checkStmt.setString(1, messageId);
            rs = checkStmt.executeQuery();

            if (!rs.next()) {
                throw new ServiceException(
                    "Message not found in ofMessageArchive: " + messageId,
                    Response.Status.NOT_FOUND);
            }

            String roomJid = rs.getString("toJID");
            log.debug("Found message [{}] in room [{}]", messageId, roomJid);

            // Close check resources before opening delete statement
            DbConnectionManager.closeResultSet(rs);
            DbConnectionManager.closeStatement(checkStmt);
            rs        = null;
            checkStmt = null;

            // ── Delete ────────────────────────────────────────────────
            deleteStmt = conn.prepareStatement(DELETE_SQL);
            deleteStmt.setString(1, messageId);
            int rows = deleteStmt.executeUpdate();

            if (rows == 0) {
                // Concurrent delete — treat as not found
                throw new ServiceException(
                    "Message not found or already deleted: " + messageId,
                    Response.Status.NOT_FOUND);
            }

            log.info("Deleted message [{}] from room [{}] ({} row(s) affected)",
                messageId, roomJid, rows);

            return roomJid;

        } catch (ServiceException e) {
            throw e;    // re-throw as-is, already has correct status
        } catch (Exception e) {
            log.error("Unexpected error deleting message [{}]", messageId, e);
            throw new ServiceException(
                "Internal server error: " + e.getMessage(),
                Response.Status.INTERNAL_SERVER_ERROR);
        } finally {
            // Always close in correct order
            DbConnectionManager.closeConnection(rs, checkStmt, conn);
            if (deleteStmt != null) {
                try { deleteStmt.close(); } catch (Exception ignored) {}
            }
        }
    }
}
