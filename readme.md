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
