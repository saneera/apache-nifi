package com.babcock.openfire.plugin.rest;

import org.jivesoftware.database.DbConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Plain HttpServlet REST endpoint for MUC message operations.
 *
 * No Jersey, no JAX-RS — just a standard HttpServlet.
 * Auth is handled upstream by MucRestApiAuthenticator.
 *
 * URL: DELETE /plugins/muc-rest-api/messages/{messageId}
 */
public class MucMessageServlet extends HttpServlet {

    private static final Logger log =
        LoggerFactory.getLogger(MucMessageServlet.class);

    private static final String CHECK_SQL =
        "SELECT messageID, toJID FROM ofMessageArchive WHERE messageID = ?";

    private static final String DELETE_SQL =
        "DELETE FROM ofMessageArchive WHERE messageID = ?";

    /**
     * DELETE /plugins/muc-rest-api/messages/{messageId}
     */
    @Override
    protected void doDelete(HttpServletRequest request,
                            HttpServletResponse response)
            throws IOException {

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        // Extract messageId from URL path
        // URL pattern: /messages/{messageId}
        // pathInfo = /{messageId}
        String pathInfo = request.getPathInfo();

        if (pathInfo == null || pathInfo.equals("/")) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            write(response, "{\"error\":\"messageId is required\"}");
            return;
        }

        // Strip leading /
        String messageId = pathInfo.substring(1);

        if (messageId.isBlank()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            write(response, "{\"error\":\"messageId is required\"}");
            return;
        }

        log.info("DELETE request for messageId [{}]", messageId);

        Connection        conn       = null;
        PreparedStatement checkStmt  = null;
        PreparedStatement deleteStmt = null;
        ResultSet         rs         = null;

        try {
            conn = DbConnectionManager.getConnection();

            // ── Check message exists ──────────────────────────────────
            checkStmt = conn.prepareStatement(CHECK_SQL);
            checkStmt.setString(1, messageId);
            rs = checkStmt.executeQuery();

            if (!rs.next()) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                write(response, String.format(
                    "{\"error\":\"Message not found: %s\"}", messageId));
                return;
            }

            String roomJid = rs.getString("toJID");
            DbConnectionManager.closeResultSet(rs);
            DbConnectionManager.closeStatement(checkStmt);
            rs        = null;
            checkStmt = null;

            // ── Delete ────────────────────────────────────────────────
            deleteStmt = conn.prepareStatement(DELETE_SQL);
            deleteStmt.setString(1, messageId);
            int rows = deleteStmt.executeUpdate();

            if (rows == 0) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                write(response, String.format(
                    "{\"error\":\"Message not found or already deleted: %s\"}",
                    messageId));
                return;
            }

            log.info("Deleted message [{}] from room [{}]", messageId, roomJid);
            response.setStatus(HttpServletResponse.SC_OK);
            write(response, String.format(
                "{\"success\":true,\"messageId\":\"%s\",\"room\":\"%s\"}",
                messageId, roomJid));

        } catch (Exception e) {
            log.error("Error deleting message [{}]", messageId, e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            write(response, String.format(
                "{\"error\":\"Internal error: %s\"}", e.getMessage()));
        } finally {
            DbConnectionManager.closeConnection(rs, checkStmt, conn);
            if (deleteStmt != null) {
                try { deleteStmt.close(); } catch (Exception ignored) {}
            }
        }
    }

    private void write(HttpServletResponse response, String json)
            throws IOException {
        PrintWriter out = response.getWriter();
        out.print(json);
        out.flush();
    }
}
