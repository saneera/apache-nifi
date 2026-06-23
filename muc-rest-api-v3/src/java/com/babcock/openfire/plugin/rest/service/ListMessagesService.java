package com.babcock.openfire.plugin.rest.service;

import com.babcock.openfire.plugin.rest.ServiceException;
import com.babcock.openfire.plugin.rest.controller.ListMessagesController;
import com.babcock.openfire.plugin.rest.controller.ListMessagesController.MessageRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;

@Path("/room/{roomName}")
@Produces(MediaType.APPLICATION_JSON)
public class ListMessagesService {

    private static final Logger log =
            LoggerFactory.getLogger(ListMessagesService.class);

    private final ListMessagesController controller =
            ListMessagesController.getInstance();

    @GET
    public Response listMessages(
            @PathParam("roomName") String roomName,
            @QueryParam("limit") Integer limit,
            @QueryParam("offset") Integer offset) {

        log.info("GET /room/{}?limit={}&offset={}", roomName, limit, offset);

        try {
            List<MessageRow> messages = controller.listMessages(roomName, limit, offset);

            StringBuilder json = new StringBuilder();
            json.append("{\"room\":\"").append(escape(roomName)).append("\",");
            json.append("\"count\":").append(messages.size()).append(",");
            json.append("\"messages\":[");

            for (int i = 0; i < messages.size(); i++) {
                MessageRow m = messages.get(i);
                if (i > 0) json.append(",");
                json.append("{")
                        .append("\"messageId\":\"").append(escape(m.messageId)).append("\",")
                        .append("\"stanzaId\":\"").append(escape(m.stanzaId)).append("\",")
                        .append("\"sentDate\":").append(m.sentDate).append(",")
                        .append("\"sender\":\"").append(escape(m.sender)).append("\",")
                        .append("\"nickname\":\"").append(escape(m.nickname)).append("\",")
                        .append("\"body\":\"").append(escape(m.body)).append("\"")
                        .append("}");
            }

            json.append("]}");

            return Response.ok(json.toString()).build();

        } catch (ServiceException e) {
            return Response.status(e.getStatus())
                    .entity(String.format(
                            "{\"error\":\"%s\",\"room\":\"%s\"}",
                            escape(e.getMessage()), escape(roomName)))
                    .build();
        }
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
