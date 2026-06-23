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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ListMessagesController {

    private static final Logger log =
            LoggerFactory.getLogger(ListMessagesController.class);

    private static final ListMessagesController INSTANCE =
            new ListMessagesController();

    public static ListMessagesController getInstance() {
        return INSTANCE;
    }

    private ListMessagesController() {}

    private static final Pattern STANZA_ID_PATTERN =
            Pattern.compile("\\bid=\"([^\"]+)\"");

    private static final String CHECK_ROOM_SQL =
            "SELECT roomID FROM ofMucRoom WHERE name = ?";

    private static final String SELECT_ALL_SQL =
            "SELECT l.messageID, l.logTime, l.sender, l.nickname, l.body, l.stanza " +
                    "FROM ofMucConversationLog l " +
                    "JOIN ofMucRoom r ON r.roomID = l.roomID " +
                    "WHERE r.name = ? " +
                    "ORDER BY l.logTime ASC";

    private static final String SELECT_PAGED_SQL =
            "SELECT l.messageID, l.logTime, l.sender, l.nickname, l.body, l.stanza " +
                    "FROM ofMucConversationLog l " +
                    "JOIN ofMucRoom r ON r.roomID = l.roomID " +
                    "WHERE r.name = ? " +
                    "ORDER BY l.logTime ASC " +
                    "LIMIT ? OFFSET ?";

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

        try {
            conn = DbConnectionManager.getConnection();

            // Check room exists
            stmt = conn.prepareStatement(CHECK_ROOM_SQL);
            stmt.setString(1, roomName);
            rs = stmt.executeQuery();
            if (!rs.next()) {
                throw new ServiceException(
                        "Room not found: " + roomName,
                        Response.Status.NOT_FOUND);
            }
            DbConnectionManager.closeResultSet(rs);
            DbConnectionManager.closeStatement(stmt);
            rs   = null;
            stmt = null;

            // Query messages
            boolean paged = limit != null;
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
                        rs.getString("body"),
                        extractStanzaId(rs.getString("stanza"))
                ));
            }

            log.info("Listed {} message(s) for room [{}] (limit={}, offset={})",
                    messages.size(), roomName, limit, offset);

            return messages;

        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error listing messages for room [{}]", roomName, e);
            throw new ServiceException(
                    "Internal error: " + e.getMessage(),
                    Response.Status.INTERNAL_SERVER_ERROR);
        } finally {
            DbConnectionManager.closeConnection(rs, stmt, conn);
        }
    }

    private String extractStanzaId(String stanza) {
        if (stanza == null || stanza.isBlank()) {
            return "";
        }
        Matcher m = STANZA_ID_PATTERN.matcher(stanza);
        return m.find() ? m.group(1) : "";
    }

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

    public static class MessageRow {
        public final String messageId;
        public final long   sentDate;
        public final String sender;
        public final String nickname;
        public final String body;
        public final String stanzaId;

        public MessageRow(String messageId, long sentDate, String sender,
                          String nickname, String body, String stanzaId) {
            this.messageId = messageId;
            this.sentDate  = sentDate;
            this.sender    = sender;
            this.nickname  = nickname;
            this.body      = body;
            this.stanzaId  = stanzaId;
        }
    }
}
