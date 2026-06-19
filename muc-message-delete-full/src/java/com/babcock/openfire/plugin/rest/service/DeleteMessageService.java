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
 * JAX-RS resource — DELETE endpoint for MUC message deletion.
 *
 * Full URL:
 *   DELETE http://{host}:9090/plugins/muc-rest-api/messages/{messageId}
 *
 * Path breakdown:
 *   /plugins/muc-rest-api   ← Openfire plugin base path
 *   /messages               ← web-custom.xml servlet url-pattern
 *   /{messageId}            ← @Path on this class
 *
 * Auth: handled by AuthFilter before this method is called.
 */
@Path("/{messageId}")
@Produces(MediaType.APPLICATION_JSON)
public class DeleteMessageService {

    private static final Logger log =
        LoggerFactory.getLogger(DeleteMessageService.class);

    private final DeleteMessageController controller =
        DeleteMessageController.getInstance();

    @DELETE
    public Response deleteMessage(@PathParam("messageId") String messageId) {
        log.info("DELETE /messages/{}", messageId);

        try {
            String roomJid = controller.deleteMessage(messageId);
            return Response.ok(String.format(
                "{\"success\":true,\"messageId\":\"%s\",\"room\":\"%s\"}",
                messageId, roomJid)).build();

        } catch (ServiceException e) {
            return Response.status(e.getStatus())
                .entity(String.format(
                    "{\"error\":\"%s\",\"messageId\":\"%s\"}",
                    e.getMessage(), messageId))
                .build();
        }
    }
}
