Story 1 – Design Frequency Gateway API

Summary
Design REST API for Frequency Gateway

Description
Define the REST API that allows the Frequency Gateway to retrieve frequency allocation data from Spectrum XXI. The API should include request/response models, authentication, error handling, and documentation.

Acceptance Criteria

* API endpoints are defined.
* Request and response schemas are documented.
* Authentication method is defined.
* Error responses are documented.
* API reviewed by the team.

⸻

Story 2 – Implement Internal Streaming

Summary
Implement internal event streaming

Description
Implement the internal streaming mechanism used to publish retrieved frequency data to downstream services.

Acceptance Criteria

* Streaming component implemented.
* Events published successfully.
* Failed events are retried or logged.
* Unit tests completed.

⸻

Story 3 – Implement Periodic Data Scraper

Summary
Implement scheduled Spectrum XXI scraper

Description
Create a scheduler that periodically retrieves frequency information from Spectrum XXI.

Acceptance Criteria

* Configurable schedule.
* Secure connection established.
* Data retrieved successfully.
* Failed executions logged.
* Retry mechanism implemented.

⸻

Story 4 – Parse and Transform Frequency Data

Summary
Transform Spectrum XXI data

Description
Parse the retrieved data into the format required by Frequency Manager while preserving metadata.

Acceptance Criteria

* Input parsed successfully.
* Output matches Frequency Manager schema.
* Metadata preserved.
* Invalid records handled gracefully.

⸻

Story 5 – Publish Frequency Data

Summary
Publish transformed frequency data

Description
Publish transformed frequency data to the Frequency Manager service.

Acceptance Criteria

* Data successfully published.
* Failed publishes retried.
* Response handling implemented.
* Audit logging available.

⸻

Story 6 – Development Data Seeding

Summary
Seed development environment with sample data

Description
Load available sample frequency data directly into the Frequency Manager for development purposes.

Acceptance Criteria

* Sample dataset imported.
* Seed scripts documented.
* Data verified after import.
* Repeatable execution.

⸻

Story 7 – Development Profile Configuration

Summary
Configure development profile

Description
Create a development profile that allows developers to work without Oracle database access by using seeded/sample data.

Acceptance Criteria

* Dev profile created.
* Oracle dependency removed.
* Sample data available.
* Configuration documented.

⸻

Story 8 – Gateway Security

Summary
Implement secure authentication

Description
Implement secure authentication and encrypted communication with Spectrum XXI.

Acceptance Criteria

* Authentication implemented.
* TLS enabled.
* Credentials managed securely.
* Connection verified.

⸻

Story 9 – Monitoring and Health Checks

Summary
Implement health endpoints and metrics

Description
Expose health endpoints and application metrics for monitoring systems such as Prometheus.

Acceptance Criteria

* /health endpoint available.
* /metrics endpoint available.
* Scheduler status exposed.
* Stream status exposed.

⸻

Story 10 – Logging and Error Handling

Summary
Implement logging and error management

Description
Provide structured logging, meaningful error messages, and status codes for all gateway operations.

Acceptance Criteria

* Retrieval attempts logged.
* Success/failure logged.
* Error messages standardized.
* Correlation IDs included.

⸻

Story 11 – Configuration Management

Summary
Externalize Frequency Gateway configuration

Description
Move scheduling, endpoints, authentication, and retry settings into external configuration.

Acceptance Criteria

* Configuration externalized.
* Environment-specific profiles supported.
* Secrets excluded from source control.
* Default configuration documented.

⸻

Story 12 – Alerting

Summary
Implement alerting for gateway failures

Description
Publish metrics and alerts when synchronization fails or the gateway becomes unavailable.

Acceptance Criteria

* Sync failure metric exposed.
* Gateway availability metric exposed.
* Alert rules documented.
* Alert thresholds configurable.

⸻

Suggested Epics/Subtasks

For each story, create technical subtasks such as:

* Design
* Development
* Unit Testing
* Integration Testing
* Documentation
* Code Review
* Deployment Verification

This breakdown aligns well with the Epic acceptance criteria and the task list shown in your screenshot, making it suitable for sprint planning and progress tracking.


package com.babcock.openfire.plugin.rest.controller;

import com.babcock.openfire.plugin.rest.dto.ListMessageResponse;
import com.babcock.openfire.plugin.rest.dto.DeleteMessageResponse;
import com.babcock.openfire.plugin.rest.dto.ErrorResponse;
import com.babcock.openfire.plugin.rest.dto.MessageRow;
import com.babcock.openfire.plugin.rest.exception.ServiceException;
import com.babcock.openfire.plugin.rest.service.ListMessageService;
import com.babcock.openfire.plugin.rest.service.DeleteMessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;

@Path("/rooms/{roomName}/messages")
@Produces(MediaType.APPLICATION_JSON)
public class RoomsController {

    private static final Logger log = LoggerFactory.getLogger(RoomsController.class);

    private final ListMessageService listMessageService = ListMessageService.getInstance();
    private final DeleteMessageService deleteMessageService = DeleteMessageService.getInstance();

    @GET
    public Response listMessages(
            @PathParam("roomName") String roomName,
            @QueryParam("limit") Integer limit,
            @QueryParam("offset") Integer offset) {

        log.info("GET /rooms/{}/messages?limit={}&offset={}", roomName, limit, offset);

        try {
            if (roomName == null || roomName.isBlank()) {
                throw new ServiceException("roomName must not be blank", Response.Status.BAD_REQUEST);
            }
            if ((limit != null && limit < 0) || (offset != null && offset < 0)) {
                throw new ServiceException("limit and offset must not be negative", Response.Status.BAD_REQUEST);
            }

            List<MessageRow> messages = listMessageService.listMessages(roomName, limit, offset);
            return Response.ok(new ListMessageResponse(roomName, messages.size(), messages))
                    .build();

        } catch (ServiceException e) {
            log.error("Failed to list messages for room {}", roomName, e);
            return Response.status(e.getStatus())
                    .entity(new ErrorResponse(e.getMessage(), roomName))
                    .build();
        }
    }

    @DELETE
    @Path("/{messageId}")
    public Response deleteMessage(
            @PathParam("roomName") String roomName,
            @PathParam("messageId") String messageId) {

        log.info("DELETE /rooms/{}/messages/{}", roomName, messageId);

        try {
            if (messageId == null || messageId.isBlank()) {
                throw new ServiceException("messageId must not be blank", Response.Status.BAD_REQUEST);
            }

            String roomJid = deleteMessageService.deleteMessage(messageId);
            return Response.ok(new DeleteMessageResponse(messageId, roomJid)).build();

        } catch (ServiceException e) {
            log.error("Failed to delete message {} in room {}", messageId, roomName, e);
            return Response.status(e.getStatus())
                    .entity(new ErrorResponse(e.getMessage(), messageId))
                    .build();
        }
    }
}


public JerseyWrapper() {
registerClasses(RoomsController.class, JacksonJaxbJsonProvider.class);
// register(AuthFilter.class);
log.debug("JerseyWrapper: ResourceConfig built.");
}



package com.babcock.openfire.plugin.rest.service;

import com.babcock.openfire.plugin.rest.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.Response;

public class DeleteMessageService {

    private static final Logger log = LoggerFactory.getLogger(DeleteMessageService.class);

    private static final DeleteMessageService INSTANCE = new DeleteMessageService();

    public static DeleteMessageService getInstance() {
        return INSTANCE;
    }

    /**
     * Deletes a message, enforcing that it actually belongs to the given room.
     *
     * @param roomName  the room the caller expects this message to belong to
     * @param messageId the message to delete
     * @return the room JID the message was deleted from
     * @throws ServiceException if the message doesn't exist, doesn't belong
     *                          to {@code roomName}, or deletion otherwise fails
     */
    public String deleteMessage(String roomName, String messageId) throws ServiceException {

        // TODO: replace with your actual lookup — this should fetch the
        // message's owning room JID from the archive/DB *before* deleting,
        // e.g. via ArchiveInterface / MonitoringPlugin's archive index.
        String actualRoomJid = lookupRoomForMessage(messageId);

        if (actualRoomJid == null) {
            throw new ServiceException("Message not found: " + messageId, Response.Status.NOT_FOUND);
        }

        if (!roomMatches(roomName, actualRoomJid)) {
            log.warn("Room mismatch on delete: path room='{}' but message {} belongs to room '{}'",
                    roomName, messageId, actualRoomJid);
            throw new ServiceException(
                    "Message " + messageId + " does not belong to room " + roomName,
                    Response.Status.NOT_FOUND); // 404 rather than 403 avoids confirming message existence to a caller probing the wrong room
        }

        // TODO: your existing deletion logic goes here (unchanged)
        performDelete(messageId);

        return actualRoomJid;
    }

    /**
     * Compares the roomName path segment against the message's actual room JID.
     * Adjust this if roomName in your URLs is a bare name (e.g. "team-chat")
     * rather than a full JID (e.g. "team-chat@conference.example.com") —
     * you likely need to compare only the JID's node/local part.
     */
    private boolean roomMatches(String roomName, String actualRoomJid) {
        if (actualRoomJid.equals(roomName)) {
            return true;
        }
        // fallback: compare just the local part before '@', in case roomName
        // is unqualified and actualRoomJid is a full JID
        int at = actualRoomJid.indexOf('@');
        if (at > 0) {
            return actualRoomJid.substring(0, at).equals(roomName);
        }
        return false;
    }

    private String lookupRoomForMessage(String messageId) {
        // fill in with your existing archive lookup
        throw new UnsupportedOperationException("wire up to your archive index lookup");
    }

    private void performDelete(String messageId) {
        // fill in with your existing delete logic
        throw new UnsupportedOperationException("wire up to your existing delete implementation");
    }
}

@DELETE
@Path("/{messageId}")
public Response deleteMessage(
@PathParam("roomName") String roomName,
@PathParam("messageId") String messageId) {

    log.info("DELETE /rooms/{}/messages/{}", roomName, messageId);

    try {
        if (roomName == null || roomName.isBlank()) {
            throw new ServiceException("roomName must not be blank", Response.Status.BAD_REQUEST);
        }
        if (messageId == null || messageId.isBlank()) {
            throw new ServiceException("messageId must not be blank", Response.Status.BAD_REQUEST);
        }

        String roomJid = deleteMessageService.deleteMessage(roomName, messageId);
        return Response.ok(new DeleteMessageResponse(messageId, roomJid)).build();

    } catch (ServiceException e) {
        log.error("Failed to delete message {} in room {}", messageId, roomName, e);
        return Response.status(e.getStatus())
                .entity(new ErrorResponse(e.getMessage(), messageId))
                .build();
    }
}




package com.babcock.openfire.plugin.rest.service;

import com.babcock.openfire.plugin.rest.exception.ServiceException;
import com.babcock.openfire.plugin.rest.dto.MessageRow;
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

public class RoomsService {

    private static final Logger log = LoggerFactory.getLogger(RoomsService.class);

    private static final RoomsService INSTANCE = new RoomsService();
    public static RoomsService getInstance() { return INSTANCE; }
    private RoomsService() {}

    private static final Pattern STANZA_ID_PATTERN = Pattern.compile("\\bid=\"([^\"]+)\"");

    private static final String CHECK_ROOM_SQL =
            "SELECT roomID FROM ofMucRoom WHERE name = ?";

    private static final String SELECT_ALL_SQL =
            "SELECT l.messageID, l.logTime, l.sender, l.nickname, l.body, l.stanza "
            + "FROM ofMucConversationLog l "
            + "JOIN ofMucRoom r ON r.roomID = l.roomID "
            + "WHERE r.name = ? "
            + "ORDER BY l.logTime ASC";

    private static final String SELECT_PAGED_SQL =
            "SELECT l.messageID, l.logTime, l.sender, l.nickname, l.body, l.stanza "
            + "FROM ofMucConversationLog l "
            + "JOIN ofMucRoom r ON r.roomID = l.roomID "
            + "WHERE r.name = ? "
            + "ORDER BY l.logTime ASC "
            + "LIMIT ? OFFSET ?";

    private static final String CHECK_MESSAGE_SQL =
            "SELECT messageID, roomID FROM ofMucConversationLog WHERE messageID = ?";

    private static final String DELETE_SQL =
            "DELETE FROM ofMucConversationLog WHERE messageID = ?";

    // ---------- LIST ----------

    public List<MessageRow> listMessages(String roomName, Integer limit, Integer offset) throws ServiceException {
        Connection conn = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;

        try {
            conn = DbConnectionManager.getConnection();

            // Check room exists
            stmt = conn.prepareStatement(CHECK_ROOM_SQL);
            stmt.setString(1, roomName);
            rs = stmt.executeQuery();
            if (!rs.next()) {
                throw new ServiceException("Room not found: " + roomName, Response.Status.NOT_FOUND);
            }
            DbConnectionManager.closeResultSet(rs);
            DbConnectionManager.closeStatement(stmt);
            rs = null;
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
                        extractStanzaId(rs.getString("stanza"))));
            }

            log.info("Listed {} message(s) for room [{}] (limit={}, offset={})",
                    messages.size(), roomName, limit, offset);

            return messages;

        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error listing messages from room {}", roomName, e);
            throw new ServiceException("Internal error: " + e.getMessage(), Response.Status.INTERNAL_SERVER_ERROR);
        } finally {
            DbConnectionManager.closeConnection(rs, stmt, conn);
        }
    }

    // ---------- DELETE (now room-aware) ----------

    public String deleteMessage(String roomName, String messageId) throws ServiceException {
        Connection conn = null;
        PreparedStatement roomStmt = null;
        PreparedStatement checkStmt = null;
        PreparedStatement deleteStmt = null;
        ResultSet roomRs = null;
        ResultSet rs = null;

        try {
            conn = DbConnectionManager.getConnection();

            // Resolve roomName -> roomID
            roomStmt = conn.prepareStatement(CHECK_ROOM_SQL);
            roomStmt.setString(1, roomName);
            roomRs = roomStmt.executeQuery();
            if (!roomRs.next()) {
                throw new ServiceException("Room not found: " + roomName, Response.Status.NOT_FOUND);
            }
            long expectedRoomId = roomRs.getLong("roomID");
            DbConnectionManager.closeResultSet(roomRs);
            DbConnectionManager.closeStatement(roomStmt);
            roomRs = null;
            roomStmt = null;

            // Check message exists and capture its actual roomID
            checkStmt = conn.prepareStatement(CHECK_MESSAGE_SQL);
            checkStmt.setString(1, messageId);
            rs = checkStmt.executeQuery();

            if (!rs.next()) {
                throw new ServiceException("Message not found for message ID: " + messageId, Response.Status.NOT_FOUND);
            }

            long messageIdExists = rs.getLong("messageID");
            long actualRoomId = rs.getLong("roomID");

            DbConnectionManager.closeResultSet(rs);
            DbConnectionManager.closeStatement(checkStmt);
            rs = null;
            checkStmt = null;

            // Enforce the message actually belongs to the room in the URL
            if (actualRoomId != expectedRoomId) {
                log.warn("Room mismatch on delete: path room='{}' (roomID={}) but message {} belongs to roomID={}",
                        roomName, expectedRoomId, messageId, actualRoomId);
                throw new ServiceException(
                        "Message " + messageId + " does not belong to room " + roomName,
                        Response.Status.NOT_FOUND); // 404 avoids confirming the message exists elsewhere
            }

            // Delete by numeric messageID
            deleteStmt = conn.prepareStatement(DELETE_SQL);
            deleteStmt.setLong(1, messageIdExists);
            int rows = deleteStmt.executeUpdate();

            if (rows == 0) {
                throw new ServiceException(
                        "Message not found or already deleted: " + messageIdExists, Response.Status.NOT_FOUND);
            }

            log.info("Deleted message stanzaId=[{}] messageID=[{}] room=[{}]",
                    messageIdExists, messageIdExists, roomName);

            return roomName;

        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error deleting message stanzaId=[{}]", messageId, e);
            throw new ServiceException("Internal error: " + e.getMessage(), Response.Status.INTERNAL_SERVER_ERROR);
        } finally {
            DbConnectionManager.closeConnection(rs, checkStmt, conn);
            if (roomRs != null) DbConnectionManager.closeResultSet(roomRs);
            if (roomStmt != null) DbConnectionManager.closeStatement(roomStmt);
            if (deleteStmt != null) {
                try { deleteStmt.close(); } catch (Exception ignored) {}
            }
        }
    }

    // ---------- helpers ----------

    private String extractStanzaId(String stanza) {
        if (stanza == null || stanza.isBlank()) return "";
        Matcher m = STANZA_ID_PATTERN.matcher(stanza);
        return m.find() ? m.group(1) : "";
    }

    private long parseLogTime(String raw) {
        if (raw == null || raw.isBlank()) return 0L;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("Unparseable logTime value [{}]", raw);
            return 0L;
        }
    }
}



package com.babcock.openfire.plugin.rest.controller;

import com.babcock.openfire.plugin.rest.dto.DeleteMessageResponse;
import com.babcock.openfire.plugin.rest.dto.ErrorResponse;
import com.babcock.openfire.plugin.rest.dto.ListMessageResponse;
import com.babcock.openfire.plugin.rest.dto.MessageRow;
import com.babcock.openfire.plugin.rest.exception.ServiceException;
import com.babcock.openfire.plugin.rest.service.RoomsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.ws.rs.core.Response;
import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoomsControllerTest {

    private RoomsController controller;
    private RoomsService mockService;

    @BeforeEach
    void setUp() throws Exception {
        controller = new RoomsController();
        mockService = mock(RoomsService.class);

        // RoomsController currently wires its service via
        // RoomsService.getInstance() in a field initializer, so tests
        // inject the mock via reflection. Switching to constructor
        // injection would let this go away — see note at bottom of file.
        Field f = RoomsController.class.getDeclaredField("roomsService");
        f.setAccessible(true);
        f.set(controller, mockService);
    }

    // ---------------------------------------------------------------
    // listMessages
    // ---------------------------------------------------------------

    @Test
    void listMessages_returnsOkWithMessages_whenServiceSucceeds() throws Exception {
        List<MessageRow> rows = List.of(
                new MessageRow("101", 1699999999000L, "alice@example.com", "Alice", "hello", "stanza-1")
        );
        when(mockService.listMessages("room1", 10, 0)).thenReturn(rows);

        Response response = controller.listMessages("room1", 10, 0);

        assertEquals(200, response.getStatus());
        Object entity = response.getEntity();
        assertInstanceOf(ListMessageResponse.class, entity);
        ListMessageResponse body = (ListMessageResponse) entity;
        assertEquals("room1", body.room);
        assertEquals(1, body.count);
        assertEquals(rows, body.messages);

        verify(mockService).listMessages("room1", 10, 0);
    }

    @Test
    void listMessages_allowsNullLimitAndOffset() throws Exception {
        when(mockService.listMessages("room1", null, null)).thenReturn(List.of());

        Response response = controller.listMessages("room1", null, null);

        assertEquals(200, response.getStatus());
        verify(mockService).listMessages("room1", null, null);
    }

    @Test
    void listMessages_returnsBadRequest_whenRoomNameIsNull() throws Exception {
        Response response = controller.listMessages(null, 10, 0);

        assertEquals(400, response.getStatus());
        assertInstanceOf(ErrorResponse.class, response.getEntity());
        verifyNoInteractions(mockService);
    }

    @Test
    void listMessages_returnsBadRequest_whenRoomNameIsBlank() throws Exception {
        Response response = controller.listMessages("   ", 10, 0);

        assertEquals(400, response.getStatus());
        assertInstanceOf(ErrorResponse.class, response.getEntity());
        verifyNoInteractions(mockService);
    }

    @Test
    void listMessages_returnsBadRequest_whenLimitIsNegative() throws Exception {
        Response response = controller.listMessages("room1", -1, 0);

        assertEquals(400, response.getStatus());
        verifyNoInteractions(mockService);
    }

    @Test
    void listMessages_returnsBadRequest_whenOffsetIsNegative() throws Exception {
        Response response = controller.listMessages("room1", 10, -1);

        assertEquals(400, response.getStatus());
        verifyNoInteractions(mockService);
    }

    @Test
    void listMessages_returns404_whenRoomNotFound() throws Exception {
        when(mockService.listMessages("ghost-room", 10, 0))
                .thenThrow(new ServiceException("Room not found: ghost-room", Response.Status.NOT_FOUND));

        Response response = controller.listMessages("ghost-room", 10, 0);

        assertEquals(404, response.getStatus());
        ErrorResponse error = (ErrorResponse) response.getEntity();
        assertEquals("Room not found: ghost-room", error.error);
        assertEquals("ghost-room", error.messageId); // roomName passed as the identifier field
    }

    @Test
    void listMessages_returns500_whenServiceThrowsInternalError() throws Exception {
        when(mockService.listMessages("room1", 10, 0))
                .thenThrow(new ServiceException("Internal error: db down", Response.Status.INTERNAL_SERVER_ERROR));

        Response response = controller.listMessages("room1", 10, 0);

        assertEquals(500, response.getStatus());
    }

    // ---------------------------------------------------------------
    // deleteMessage
    // ---------------------------------------------------------------

    @Test
    void deleteMessage_returnsOk_whenServiceSucceeds() throws Exception {
        when(mockService.deleteMessage("room1", "msg-123")).thenReturn("room1");

        Response response = controller.deleteMessage("room1", "msg-123");

        assertEquals(200, response.getStatus());
        Object entity = response.getEntity();
        assertInstanceOf(DeleteMessageResponse.class, entity);
        DeleteMessageResponse body = (DeleteMessageResponse) entity;
        assertEquals("msg-123", body.messageId);
        assertEquals("room1", body.room);

        verify(mockService).deleteMessage("room1", "msg-123");
    }

    @Test
    void deleteMessage_returnsBadRequest_whenRoomNameIsBlank() throws Exception {
        Response response = controller.deleteMessage("  ", "msg-123");

        assertEquals(400, response.getStatus());
        verifyNoInteractions(mockService);
    }

    @Test
    void deleteMessage_returnsBadRequest_whenMessageIdIsNull() throws Exception {
        Response response = controller.deleteMessage("room1", null);

        assertEquals(400, response.getStatus());
        verifyNoInteractions(mockService);
    }

    @Test
    void deleteMessage_returnsBadRequest_whenMessageIdIsBlank() throws Exception {
        Response response = controller.deleteMessage("room1", "   ");

        assertEquals(400, response.getStatus());
        verifyNoInteractions(mockService);
    }

    @Test
    void deleteMessage_returns404_whenMessageNotFound() throws Exception {
        when(mockService.deleteMessage("room1", "missing-id"))
                .thenThrow(new ServiceException("Message not found for message ID: missing-id",
                        Response.Status.NOT_FOUND));

        Response response = controller.deleteMessage("room1", "missing-id");

        assertEquals(404, response.getStatus());
        ErrorResponse error = (ErrorResponse) response.getEntity();
        assertEquals("Message not found for message ID: missing-id", error.error);
        assertEquals("missing-id", error.messageId);
    }

    @Test
    void deleteMessage_returns404_whenMessageBelongsToDifferentRoom() throws Exception {
        // Regression test for the room-ownership check added to RoomsService:
        // deleting a real message via the wrong room's URL must not succeed.
        when(mockService.deleteMessage("wrong-room", "msg-123"))
                .thenThrow(new ServiceException(
                        "Message msg-123 does not belong to room wrong-room",
                        Response.Status.NOT_FOUND));

        Response response = controller.deleteMessage("wrong-room", "msg-123");

        assertEquals(404, response.getStatus());
        ErrorResponse error = (ErrorResponse) response.getEntity();
        assertEquals("Message msg-123 does not belong to room wrong-room", error.error);
    }

    @Test
    void deleteMessage_returns404_whenRoomDoesNotExist() throws Exception {
        when(mockService.deleteMessage("ghost-room", "msg-123"))
                .thenThrow(new ServiceException("Room not found: ghost-room", Response.Status.NOT_FOUND));

        Response response = controller.deleteMessage("ghost-room", "msg-123");

        assertEquals(404, response.getStatus());
    }

    @Test
    void deleteMessage_returns500_whenServiceThrowsInternalError() throws Exception {
        when(mockService.deleteMessage("room1", "msg-123"))
                .thenThrow(new ServiceException("Internal error: db down", Response.Status.INTERNAL_SERVER_ERROR));

        Response response = controller.deleteMessage("room1", "msg-123");

        assertEquals(500, response.getStatus());
    }
}

/*
* NOTE ON TEST DESIGN
* --------------------
* RoomsController currently obtains its RoomsService via
* RoomsService.getInstance() in a field initializer:
*
*     private final RoomsService roomsService = RoomsService.getInstance();
*
* That's why these tests use reflection to swap in a mock after
* construction. If RoomsController is refactored to accept the
* service via constructor injection, e.g.:
*
*     public RoomsController(RoomsService roomsService) { ... }
*     public RoomsController() { this(RoomsService.getInstance()); }
*
* then setUp() simplifies to:
*
*     mockService = mock(RoomsService.class);
*     controller = new RoomsController(mockService);
*
* and the reflection helper + field lookup can be deleted entirely.
  */
