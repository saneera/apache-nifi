package com.babcock.openfire.plugin.rest.controller;

import com.babcock.openfire.plugin.rest.ServiceException;
import org.jivesoftware.database.DbConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;


public class DeleteMessageController {

    private static final Logger log = LoggerFactory.getLogger(DeleteMessageController.class);

    private static final DeleteMessageController INSTANCE = new DeleteMessageController();

    public static DeleteMessageController getInstance() {
        return INSTANCE;
    }

    private DeleteMessageController() {}

    private static final String CHECK_SQL ="SELECT messageID, roomID FROM ofMucConversationLog WHERE stanza = ?";

    private static final String DELETE_SQL = "DELETE FROM ofMucConversationLog WHERE stanza = ?";

    /**
     * Deletes a message from ofMessageArchive.
     *
     * @param messageId the stanza ID stored in ofMessageArchive.messageID
     * @return the room JID (toJID) the message belonged to
     * @throws ServiceException with HTTP status on failure
     */
    public String deleteMessage(String messageId) throws ServiceException {

        if (messageId == null || messageId.isBlank()) {
            throw new ServiceException("messageId must not be blank", Response.Status.BAD_REQUEST);
        }

        Connection        conn       = null;
        PreparedStatement checkStmt  = null;
        PreparedStatement deleteStmt = null;
        ResultSet         rs         = null;

        try {
            conn = DbConnectionManager.getConnection();

            // ── Check exists ──────────────────────────────────────────
            checkStmt = conn.prepareStatement(CHECK_SQL);
            checkStmt.setString(1, messageId);
            rs = checkStmt.executeQuery();

            if (!rs.next()) {
                throw new ServiceException("Message not found: " + messageId, Response.Status.NOT_FOUND);
            }

            String roomJid = rs.getString("roomID");

            DbConnectionManager.closeResultSet(rs);
            DbConnectionManager.closeStatement(checkStmt);
            rs        = null;
            checkStmt = null;

            // ── Delete ────────────────────────────────────────────────
            deleteStmt = conn.prepareStatement(DELETE_SQL);
            deleteStmt.setString(1, messageId);
            int rows = deleteStmt.executeUpdate();

            if (rows == 0) {
                throw new ServiceException("Message not found or already deleted: " + messageId, Response.Status.NOT_FOUND);
            }

            log.info("Deleted message [{}] from room [{}]", messageId, roomJid);
            return roomJid;

        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error deleting message [{}]", messageId, e);
            throw new ServiceException("Internal error: " + e.getMessage(), Response.Status.INTERNAL_SERVER_ERROR);
        } finally {
            DbConnectionManager.closeConnection(rs, checkStmt, conn);
            if (deleteStmt != null) {
                try { deleteStmt.close(); } catch (Exception ignored) {}
            }
        }
    }
}
