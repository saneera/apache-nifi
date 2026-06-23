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

    private static final String CHECK_SQL =
            "SELECT messageID, roomID, stanza FROM ofMucConversationLog WHERE stanza LIKE ?";

    private static final String DELETE_SQL =
            "DELETE FROM ofMucConversationLog WHERE messageID = ?";

    public String deleteMessage(String stanzaId) throws ServiceException {

        if (stanzaId == null || stanzaId.isBlank()) {
            throw new ServiceException("stanzaId must not be blank", Response.Status.BAD_REQUEST);
        }

        Connection        conn       = null;
        PreparedStatement checkStmt  = null;
        PreparedStatement deleteStmt = null;
        ResultSet         rs         = null;

        try {
            conn = DbConnectionManager.getConnection();

            // Search for the stanza containing this ID attribute
            // e.g. id="MPI1M-13" anywhere in the stanza XML
            checkStmt = conn.prepareStatement(CHECK_SQL);
            checkStmt.setString(1, "%id=\"" + stanzaId + "\"%");
            rs = checkStmt.executeQuery();

            if (!rs.next()) {
                throw new ServiceException(
                        "Message not found for stanza ID: " + stanzaId,
                        Response.Status.NOT_FOUND);
            }

            long   messageId = rs.getLong("messageID");
            String roomId    = rs.getString("roomID");

            DbConnectionManager.closeResultSet(rs);
            DbConnectionManager.closeStatement(checkStmt);
            rs        = null;
            checkStmt = null;

            // Delete by numeric messageID
            deleteStmt = conn.prepareStatement(DELETE_SQL);
            deleteStmt.setLong(1, messageId);
            int rows = deleteStmt.executeUpdate();

            if (rows == 0) {
                throw new ServiceException(
                        "Message not found or already deleted: " + stanzaId,
                        Response.Status.NOT_FOUND);
            }

            log.info("Deleted message stanzaId=[{}] messageID=[{}] room=[{}]",
                    stanzaId, messageId, roomId);
            return roomId;

        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error deleting message stanzaId=[{}]", stanzaId, e);
            throw new ServiceException(
                    "Internal error: " + e.getMessage(),
                    Response.Status.INTERNAL_SERVER_ERROR);
        } finally {
            DbConnectionManager.closeConnection(rs, checkStmt, conn);
            if (deleteStmt != null) {
                try { deleteStmt.close(); } catch (Exception ignored) {}
            }
        }
    }
}
