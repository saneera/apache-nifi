package com.babcock.inspire.openfire.services;

// Proposed split of ChatService.java - see ChatService.java (facade) for rationale.
// Owns the "wipe everything back to clean state" flow.
//
// ARCHITECTURAL NOTE / circular-dependency heads-up:
// removeDeletedUsersFromParticipants() needs readProperty(PARTICIPANTS), and in
// the original code that goes through ChatService.readProperty(), which builds
// a PropertyHandler via propertyHandlerFactory.createPropertyHandler(propertyId,
// this, propertyService, commandHandlerFactory) - i.e. property handlers are
// handed the *whole* ChatService as their callback context, the same way
// command handlers are. That means ResetAllService calling back into the
// ChatService facade to read a property, while the facade also delegates
// resetAll() down into ResetAllService, is a genuine circular dependency -
// it's not an artifact of this split, it's inherent in how PropertyHandler/
// CommandHandler were designed (they want "the one big service" as context).
//
// Two ways to resolve it, your call:
//   1. (what's drafted below) inject the ChatService facade here as @Lazy, so
//      Spring breaks the cycle with a lazy proxy. Simplest change, keeps
//      PropertyHandler/CommandHandler untouched.
//   2. Change PropertyHandlerFactory/CommandHandlerFactory to take a narrower
//      interface (only the methods handlers actually call - getManager(),
//      getChatGatewayProperties(), createAndSentNotification(), etc.) instead
//      of the concrete ChatService. That removes the cycle for good but is a
//      bigger change touching every *Command/*PropertyHandler class.

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jivesoftware.smackx.muc.MultiUserChat;
import org.jivesoftware.smackx.muc.MultiUserChatManager;
import org.jxmpp.jid.EntityBareJid;
import org.jxmpp.jid.impl.JidCreate;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@RequiredArgsConstructor
public class ResetAllService {

    private final ChatGatewayProperties chatGatewayProperties;
    private final XmppConnectionManager xmppConnectionManager;
    private final PropertyService propertyService;
    private final RoomService roomService;
    private final MessagingService messagingService;
    private final MucRestApiService mucRestApiService;
    private final MessageListenerService messageListenerService;

    // See the class-level note above - this is the one place ResetAllService
    // needs the full facade back, purely to reuse ChatService.readProperty().
    @Lazy
    private final ChatService chatService;

    /**
     * Wipes the chat gateway back to a clean state by deleting all non-admin users, clears
     * all rooms and messages and returns back what was removed. Only runs if the reset-all is
     * enabled within the configuration.
     *
     * @return summary of what was deleted
     */
    public ResetAllResponse resetAll() {
        if (!chatGatewayProperties.isResetAllEnabled()) {
            throw new IllegalArgumentException("Reset all is not enabled");
        }

        log.info("Start reset-all endpoint");

        RestClient restClient = RestClient.builder()
                .baseUrl(String.format(
                        "http://%s:%d/plugins/restapi/v1",
                        chatGatewayProperties.getHost(), chatGatewayProperties.getPluginPort()))
                .defaultHeaders(headers -> {
                    headers.setBasicAuth(chatGatewayProperties.getUsername(), chatGatewayProperties.getPassword());
                    headers.setAccept(List.of(MediaType.APPLICATION_JSON));
                })
                .build();

        Set<String> adminUsernames = resolveAdminUsernames(restClient);
        log.info(
                "System connection username: [{}], resolved admins: {}",
                chatGatewayProperties.getUsername(),
                adminUsernames);

        int usersDeleted = deleteAllUsers(restClient, adminUsernames);
        RoomsAndMessagesDeletedResponse roomAndMessagesDeleted = deleteAllRoomsAndMessages(restClient);
        int roomsDeleted = roomAndMessagesDeleted.getRoomsDeleted();
        int messagesDeleted = roomAndMessagesDeleted.getMessagesDeleted();

        propertyService.sendObjectToPropService(PropertyConstants.ROOMS_MESSAGE_COUNT, new ArrayList<>());

        ResetAllResponse response = new ResetAllResponse();
        response.setUsersDeleted(usersDeleted);
        response.setRoomsDeleted(roomsDeleted);
        response.setPreservedAdmins(new ArrayList<>(adminUsernames));
        response.setMessagesDeleted(messagesDeleted);

        roomService.clearAllRoomTracking();

        log.info(
                "Reset all complete - usersDeleted={}, roomsDeleted={}, messagesDeleted={}",
                usersDeleted,
                roomsDeleted,
                messagesDeleted);

        return response;
    }

    /**
     * Reads the admin.authorizedJIDs system property and extracts the usernames of the admin
     * accounts that should be preserved during reset-all command
     */
    private Set<String> resolveAdminUsernames(RestClient restClient) {
        Set<String> admins = new HashSet<>();

        Map<String, Object> property = restClient
                .get()
                .uri("/system/properties/admin.authorizedJIDs")
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});

        String raw = property != null ? String.valueOf(property.get("value")) : null;

        if (StringUtils.isBlank(raw) || raw.equals("null")) {
            return admins;
        }

        for (String entry : raw.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;

            String username = trimmed.contains("@") ? trimmed.substring(0, trimmed.indexOf('@')) : trimmed;
            admins.add(username);
        }

        return admins;
    }

    /**
     * Deletes every registered user from Openfire except the given admins,
     * then removes the deleted users from the stored participants list.
     */
    private int deleteAllUsers(RestClient restClient, Set<String> adminUsernames) {
        UserList users = restClient.get().uri("/users").retrieve().body(UserList.class);
        int usersDeleted = 0;

        if (users == null || users.getUsers() == null) {
            return usersDeleted;
        }

        for (RestApiUser user : users.getUsers()) {
            if (adminUsernames.contains(user.getUsername())) {
                continue;
            }

            try {
                xmppConnectionManager.disconnectParticipant(user.getUsername());
            } catch (Exception e) {
                log.error("Error clearing cache for participants ", e);
            }

            restClient
                    .delete()
                    .uri("/users/{username}", user.getUsername())
                    .retrieve()
                    .toBodilessEntity();
            usersDeleted++;
        }

        removeDeletedUsersFromParticipants(adminUsernames);

        return usersDeleted;
    }

    /**
     * Removes any participant not in the adminUsernames from the stored participants list.
     */
    private void removeDeletedUsersFromParticipants(Set<String> adminUsernames) {
        ChatAssetResponse chatAssetResponse = chatService.readProperty(PropertyConstants.PARTICIPANTS);
        List<String> participants = new ArrayList<>();
        if (chatAssetResponse.getData() != null
                && chatAssetResponse.getData() instanceof ParticipantsPropResponse participantsPropResponse) {
            participants = participantsPropResponse.getParticipants();
        }

        participants.removeIf(p -> !adminUsernames.contains(p));
        propertyService.sendObjectToPropService(PropertyConstants.PARTICIPANTS, participants);
    }

    /**
     * Clears every chat room by joining each chat room as a system user, deletes all its messages,
     * and then deletes the room.
     */
    private RoomsAndMessagesDeletedResponse deleteAllRoomsAndMessages(RestClient restClient) {
        Rooms rooms = roomService.listRoomFromServer();

        int roomsDeleted = 0;
        int messagesDeleted = 0;

        if (rooms == null || rooms.getRooms() == null) {
            return new RoomsAndMessagesDeletedResponse();
        }

        for (Room room : rooms.getRooms()) {
            String roomKey = room.getRoomName();

            try {
                List<MessageResponse> messages = messagingService.readMessageByParticipantAndRoom(roomKey, new ArrayList<>());
                for (MessageResponse msg : messages) {
                    try {
                        DeleteMessageResponse deleteResponse =
                                mucRestApiService.deleteMessageByRoomNameAndMessageId(roomKey, msg.getStanzaId());
                        messageListenerService.processRoomMessageRetraction(roomKey, deleteResponse.getMessageId());

                        messagesDeleted++;
                    } catch (IllegalArgumentException e) {
                        log.error("Error deleting message [{}] from room [{}]", msg.getMessageId(), roomKey, e);
                    }
                }

            } catch (IllegalArgumentException e) {
                log.error("Error reading messages for room [{}]", roomKey, e);
            } catch (Exception e) {
                log.error("Error clearing messages for room [{}]", roomKey, e);
            }

            try {
                String roomJidString = roomKey + "@" + chatGatewayProperties.getServiceName()
                        + "." + chatGatewayProperties.getDomain();
                MultiUserChatManager manager = roomService.getManager();
                EntityBareJid roomJid = JidCreate.entityBareFrom(roomJidString);
                MultiUserChat muc = manager.getMultiUserChat(roomJid);

                roomService.leaveAndUnregister(roomKey, muc);
            } catch (Exception e) {
                log.error("Error clearing cache for rooms [{}]", roomKey, e);
            }

            restClient
                    .delete()
                    .uri("/chatrooms/{roomName}?servicename={service}",
                            room.getRoomName(), chatGatewayProperties.getServiceName())
                    .retrieve()
                    .toBodilessEntity();
            roomsDeleted++;
        }

        Rooms remainingRooms = roomService.listRoomFromServer();
        propertyService.sendObjectToPropService(PropertyConstants.ROOMS, remainingRooms.getRooms());

        return new RoomsAndMessagesDeletedResponse(roomsDeleted, messagesDeleted);
    }
}
