package com.babcock.inspire.openfire.services;

// Proposed split of ChatService.java - see ChatService.java (facade) for rationale.
// Owns sending/deleting/reading messages. Delegates room-membership validation
// to RoomService.checkParticipantJoinedTheRoom(...) rather than touching the
// joinedRooms map itself.

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smackx.muc.MultiUserChat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class MessagingService {

    private final RoomService roomService;
    private final MessageListenerService messageListenerService;
    private final MucRestApiService mucRestApiService;

    /** This method send message to the room */
    public String sendMessageToRoom(String roomName, String participantName, String message) {
        try {
            MultiUserChat muc = roomService.checkParticipantJoinedTheRoom(roomName, participantName);

            String messageId = UUID.randomUUID().toString();
            MessageBuilder msgBuilder =
                    muc.buildMessage().setBody(message).addExtension(new OriginIdElement(messageId));

            Message msg = msgBuilder.build();
            muc.sendMessage(msg);

            log.info("Sent message to [{}] from [{}]", roomName, participantName);

            return msg.getStanzaId();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (SmackException.NotConnectedException e) {
            log.error("Error when connecting the room [{}]", e.getMessage());
            throw new IllegalArgumentException("Error when connecting the room ", e);
        } catch (InterruptedException e) {
            log.error("Error when sending message [{}]", e.getMessage());
            throw new IllegalArgumentException("Error when sending message ", e);
        }
    }

    /** This method deletes a message from a room */
    public void deleteMessageFromRoom(String roomName, String participantName, String messageId) {
        try {
            // validate participant in the room before delete
            roomService.checkParticipantJoinedTheRoom(roomName, participantName);

            DeleteMessageResponse deleteResponse =
                    mucRestApiService.deleteMessageByRoomNameAndMessageId(roomName, messageId);
            messageListenerService.processRoomMessageRetraction(roomName, deleteResponse.getMessageId());

            log.info("Delete message [{}] from room [{}]", messageId, roomName);

        } catch (IllegalArgumentException e) {
            log.error("Error when delete message [{}] from room [{}]", messageId, roomName, e);
            throw e;
        }
    }

    /**
     * Reads messages from a MUC room direct query from database
     */
    public List<MessageResponse> readMessageByParticipantAndRoom(String roomName, List<String> messageIds)
            throws IllegalArgumentException {

        ListMessageResponse messageResponse = mucRestApiService.readMessagesOfRoom(roomName);
        List<MessageRow> messageRows = messageResponse.getMessages();

        if (!messageIds.isEmpty()) {
            messageRows = messageRows.stream()
                    .filter(messageRow -> messageIds.stream()
                            .anyMatch(messageId ->
                                    messageRow.getMessageId().toLowerCase().contains(messageId.toLowerCase())))
                    .toList();
        }

        return mapToMessageResponse(messageRows, roomName);
    }

    /**
     * Maps raw Smack Message objects to MessageResponse DTOs.
     * Extracts the original timestamp from DelayInformation for history messages.
     */
    List<MessageResponse> mapToMessageResponse(List<MessageRow> messageRows, String roomName) {
        return messageRows.stream()
                .map(messageRow -> MessageResponse.builder()
                        .messageId(messageRow.getMessageId())
                        .stanzaId(messageRow.getStanzaId())
                        .message(messageRow.getBody())
                        .sender(messageRow.getSender())
                        .roomName(roomName)
                        .timestamp(Instant.ofEpochMilli(messageRow.getSentDate()))
                        .build())
                .collect(Collectors.toList());
    }
}
