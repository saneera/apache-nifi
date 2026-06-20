package com.babcock.openfire.plugin.rest.controller;

import com.babcock.openfire.plugin.rest.ServiceException;
import org.jivesoftware.database.DbConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public class ListMessagesController {

    private static final Logger log =
            LoggerFactory.getLogger(ListMessagesController.class);

    private static final ListMessagesController INSTANCE =
            new ListMessagesController();

    public static ListMessagesController getInstance() {
        return INSTANCE;
    }

    private ListMessagesController() {}

    private static final String SELECT_ALL_SQL =
            "SELECT l.messageID, l.logTime, l.sender, l.nickname, l.subject, l.body " +
                    "FROM ofMucConversationLog l " +
                    "JOIN ofMucRoom r ON r.roomID = l.roomID " +
                    "WHERE r.name = ? " +
                    "ORDER BY l.logTime ASC";

    private static final String SELECT_PAGED_SQL =
            "SELECT l.messageID, l.logTime, l.sender, l.nickname, l.subject, l.body " +
                    "FROM ofMucConversationLog l " +
                    "JOIN ofMucRoom r ON r.roomID = l.roomID " +
                    "WHERE r.name = ? " +
                    "ORDER BY l.logTime ASC " +
                    "LIMIT ? OFFSET ?";

    /**
     * Lists conversation log entries for a MUC room, matched by room name
     * only (ofMucRoom.name).
     *
     * @param roomName the unqualified room name, e.g. "myroom"
     * @param limit    max rows to return, or null for no limit
     * @param offset   rows to skip, or null to start from the beginning
     * @return list of message rows as simple value objects, oldest first
     * @throws ServiceException with HTTP status on failure
     */
    public List<MessageRow> listMessages(String roomName, Integer limit, Integer offset)
            throws ServiceException {

        if (roomName == null || roomName.isBlank()) {
            throw new ServiceException(
                    "roomName must not be blank",
                    Response.Status.BAD_REQUEST);
        }

        if ((limit != null && limit < 0) || (offset != null && offset < 0)) {
            throw new ServiceException(
                    "limit and offset must not be negative",
                    Response.Status.BAD_REQUEST);
        }

        Connection        conn = null;
        PreparedStatement stmt = null;
        ResultSet         rs   = null;

        boolean paged = limit != null;

        try {
            conn = DbConnectionManager.getConnection();

            if (paged) {
                stmt = conn.prepareStatement(SELECT_PAGED_SQL);
                stmt.setString(1, roomName);
                stmt.setInt(2, limit);
                stmt.setInt(3, offset != null ? offset : 0);
            } else {
                stmt = conn.prepareStatement(SELECT_ALL_SQL);
                stmt.setString(1, roomName);
            }

            rs = stmt.executeQuery();

            List<MessageRow> messages = new ArrayList<>();
            while (rs.next()) {
                messages.add(new MessageRow(
                        rs.getString("messageID"),
                        parseLogTime(rs.getString("logTime")),
                        rs.getString("sender"),
                        rs.getString("nickname"),
                        rs.getString("subject"),
                        rs.getString("body")
                ));
            }

            log.info("Listed {} message(s) for room [{}] (limit={}, offset={})",
                    messages.size(), roomName, limit, offset);

            return messages;

        } catch (Exception e) {
            log.error("Error listing messages for room [{}]", roomName, e);
            throw new ServiceException(
                    "Internal error: " + e.getMessage(),
                    Response.Status.INTERNAL_SERVER_ERROR);
        } finally {
            DbConnectionManager.closeConnection(rs, stmt, conn);
        }
    }

    /**
     * Parses Openfire's zero-padded 15-char epoch-millisecond date encoding.
     * Falls back to 0 if the value is missing or malformed rather than
     * failing the whole request over one bad row.
     */
    private long parseLogTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("Unparseable logTime value [{}]", raw);
            return 0L;
        }
    }

    /**
     * Plain data holder for a single conversation log row.
     */
    public static class MessageRow {
        public final String messageId;
        public final long   sentDate;
        public final String sender;
        public final String nickname;
        public final String subject;
        public final String body;

        public MessageRow(String messageId, long sentDate, String sender,
                          String nickname, String subject, String body) {
            this.messageId = messageId;
            this.sentDate  = sentDate;
            this.sender    = sender;
            this.nickname  = nickname;
            this.subject   = subject;
            this.body      = body;
        }
    }
}
