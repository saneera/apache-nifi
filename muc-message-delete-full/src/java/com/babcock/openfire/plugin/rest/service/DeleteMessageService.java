package com.babcock.openfire.plugin.rest.service;

import com.babcock.openfire.plugin.rest.ServiceException;
import com.babcock.openfire.plugin.rest.controller.DeleteMessageController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.DELETE;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 * JAX-RS resource class — exposes the DELETE endpoint.
 *
 * Full URL:
 *   DELETE http://{host}:9090/plugins/muc-message-delete/messages/{messageId}
 *
 * Path breakdown:
 *   /plugins/muc-message-delete   ← Openfire plugin base path
 *   /messages                     ← web-custom.xml servlet mapping url-pattern
 *   /{messageId}                  ← @Path on this class
 *
 * Authentication:
 *   Handled by {@link AuthFilter} before this method is called.
 *   Pass secret in Authorization header.
 *
 * Responses:
 *   200 OK           — message deleted from ofMessageArchive
 *   401 Unauthorized — wrong or missing secret (from AuthFilter)
 *   404 Not Found    — messageId not in ofMessageArchive
 *   500 Server Error — unexpected database error
 */
@Path("/{messageId}")
@Produces(MediaType.APPLICATION_JSON)
public class DeleteMessageService {

    private static final Logger log =
        LoggerFactory.getLogger(DeleteMessageService.class);

    private final DeleteMessageController controller =
        DeleteMessageController.getInstance();

    /**
     * Deletes a MUC message from ofMessageArchive by its messageId.
     *
     * The messageId must be the stanza ID assigned by Openfire when it
     * broadcast the message to the room — NOT the client-side stanza ID.
     * Use the ID returned from {@code sendMessageToRoom} in your Smack service.
     *
     * @param messageId Openfire-assigned stanza ID / origin-id of the message
     * @return 200 with JSON body on success, 4xx/5xx on failure
     */
    @DELETE
    public Response deleteMessage(@PathParam("messageId") String messageId) {
        log.info("DELETE /messages/{}", messageId);

        try {
            String roomJid = controller.deleteMessage(messageId);

            String body = String.format(
                "{\"success\":true,\"messageId\":\"%s\",\"room\":\"%s\"}",
                messageId, roomJid);

            return Response.ok(body).build();

        } catch (ServiceException e) {
            log.warn("Delete failed [{}]: {} ({})",
                messageId, e.getMessage(), e.getStatus());

            String body = String.format(
                "{\"error\":\"%s\",\"messageId\":\"%s\"}",
                e.getMessage(), messageId);

            return Response.status(e.getStatus()).entity(body).build();
        }
    }
}
