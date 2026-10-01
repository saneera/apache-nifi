package com.babcock.inspire.openfire.services;

// Reconstructed from a screen-recording of the real ChatService.java (scrolled
// top to bottom) plus still screenshots shared earlier in this conversation.
// This supersedes the previous photo-based reconstruction - the video let actual
// method bodies be read directly rather than guessed from partial stills, so
// most of this should now match your source closely. Remaining gaps/uncertainties
// are flagged inline with "UNCERTAIN" or "NOT FULLY VISIBLE" comments - the video
// frames were often motion-blurred, so some identifier spellings are best-effort.
// Imports are inferred from usage; your real package paths will differ.

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smackx.muc.HostedRoom;
import org.jivesoftware.smackx.muc.MucEnterConfiguration;
import org.jivesoftware.smackx.muc.MultiUserChat;
import org.jivesoftware.smackx.muc.MultiUserChatException;
import org.jivesoftware.smackx.muc.MultiUserChatManager;
import org.jivesoftware.smackx.muc.RoomInfo;
import org.jxmpp.jid.DomainBareJid;
import org.jxmpp.jid.EntityBareJid;
import org.jxmpp.jid.impl.JidCreate;
import org.jxmpp.jid.parts.Resourcepart;
import org.jxmpp.stringprep.XmppStringprepException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    // === Fields - confirmed directly from the field-declaration block in the video ===
    private final AssetHandlerStateStoreConfig stateStoreConfig;
    private final AssetHandlerStateStore store;
    private final ChatGatewayProperties chatGatewayProperties;
    private final CommandHandlerFactory commandHandlerFactory;
    private final PropertyHandlerFactory propertyHandlerFactory;
    private final ChatManagerProvider chatManagerProvider;
    private final MessageListenerService messageListenerService;
    private final XmppConnectionManager xmppConnectionManager;
    private final PropertyService propertyService;

    private final Map<String, MultiUserChat> joinedRooms = new ConcurrentHashMap<>();

    /**
     * participants -> joined rooms names
     */
    private final Map<String, Set<String>> participantRooms = new ConcurrentHashMap<>();

    private final EventListenerService eventListenerService;
    private final NotificationsService notificationsService;
    private final MucRestApiService mucRestApiService;

    public MultiUserChatManager getManager() {
        return chatManagerProvider.getManager(xmppConnectionManager.getSystemConnection());
    }

    /**
     * Initialise basic properties within the properties service
     */
    public void init() {
        Map<String, String> chatGatewayConfig = store.getAsset().getConfig();

        List<TrafficConfig> configItems = chatGatewayConfig.entrySet().stream()
                .map(e -> new TrafficConfig(e.getKey(), e.getValue()))
                .collect(Collectors.toList());

        List<Capability> capabilities = Arrays.stream(
                        chatGatewayConfig.get("capabilities").split(",\\s*"))
                .map(s -> new Capability(s))
                .toList();

        AssetTypeTraffic traffic = new AssetTypeTraffic();
        traffic.description(chatGatewayConfig.get("description"));
        traffic.category(chatGatewayConfig.get("category"));
        traffic.manufacturer(chatGatewayConfig.get("manufacturer"));
        traffic.quantity(Integer.valueOf(chatGatewayConfig.get("quantity")));
        traffic.model(chatGatewayConfig.get("model"));
        traffic.capabilities(capabilities);
        traffic.name(store.getPropertyValue("name")); // UNCERTAIN - store accessor name partly obscured
        traffic.trafficType(TrafficType.valueOf(chatGatewayConfig.get("trafficType")));
        traffic.trafficSecurity(TrafficSecurity.SECURE);
        traffic.trafficPrecedenceCategory(TrafficPrecedenceCategory.DEFERRED);
        Map data = Helper.getMessageData(traffic);

        propertyService.sendEnumValToPropService("state", AssetState.class, AssetState.NON_OPERATIONAL);
        propertyService.sendStringToPropService("category", chatGatewayConfig.get("category"));
        propertyService.sendStringToPropService("url", stateStoreConfig.url);
        propertyService.sendBooleanToPropService("connected", false);
        propertyService.sendObjectToPropService("type", data);
        propertyService.sendStringToPropService("ip", chatGatewayProperties.getHost());
        propertyService.sendStringToPropService("name", chatGatewayConfig.get("name"));
        propertyService.sendIntegerToPropService("port", chatGatewayProperties.getPort());
        propertyService.sendObjectToPropService("config", configItems);
        xmppConnectionManager.connectSystem();
        // when restart the server add rooms listeners for existing rooms
        addRoomsListener();
    }

    @PreDestroy
    public void shutDown() {
        log.info("Shutting down chat services...");
        xmppConnectionManager.disconnect();
    }

    /**
     * Execute a command where the returned response message is returned as a JSON
     * String.
     *
     * @param commandName   Command name,
     * @param commandParams Command parameters,
     * @return Command Response Message
     * @throws CommandNotFoundException Thrown if the command is invalid.
     */
    public ChatAssetResponse executeCommand(String commandName, Object commandParams)
            throws CommandNotFoundException, XmppStringprepException {

        Map<String, Command> commands = store.getAsset().getCommands();
        if (commands.get(commandName) == null) {
            throw new CommandNotFoundException(commandName);
        }

        CommandHandler commandHandler =
                commandHandlerFactory.createCommandHandler(commandName, this, propertyService, propertyHandlerFactory);
        return commandHandler.executeCommand(commandName, commandParams);
    }

    public ChatAssetResponse readProperty(String propertyId) {
        PropertyHandler propertyHandler =
                propertyHandlerFactory.createPropertyHandler(propertyId, this, propertyService, commandHandlerFactory);
        return propertyHandler.getPropertyFromServer(propertyId);
    }

    /**
     * @param roomJidString
     * @return Room
     * @throws IllegalArgumentException
     */
    public Room getRoomFromServer(String roomJidString) throws IllegalArgumentException {

        MultiUserChatManager manager = chatManagerProvider.getManager(xmppConnectionManager.getSystemConnection());

        try {
            EntityBareJid roomJid = JidCreate.entityBareFrom(roomJidString);
            RoomInfo roomInfo = manager.getRoomInfo(roomJid);
            Room room = new Room();
            room.setRoomName(roomInfo.getName());
            room.setDescription(roomInfo.getDescription());
            return room;

        } catch (XMPPException.XMPPErrorException e) {
            log.error("Room does not exists, return null");
            return null;
        } catch (SmackException.NotConnectedException e) {
            throw new IllegalArgumentException("Unable to connect to server", e);
        } catch (XmppStringprepException e) {
            throw new IllegalArgumentException("Invalid room Jid", e);
        } catch (SmackException.NoResponseException e) {
            throw new IllegalArgumentException("Server timeout while retrieving room", e);
        } catch (InterruptedException e) {
            throw new IllegalArgumentException("Interrupted Exception", e);
        }
    }

    /**
     * @return Rooms Existing Rooms from server
     */
    public Rooms listRoomFromServer() {
        MultiUserChatManager manager = chatManagerProvider.getManager(xmppConnectionManager.getSystemConnection());
        String mucDomain = chatGatewayProperties.getServiceName() + "." + chatGatewayProperties.getDomain();
        Rooms rooms = new Rooms();
        try {
            DomainBareJid serviceJid = JidCreate.domainBareFrom(mucDomain);
            Map<EntityBareJid, HostedRoom> hostedRooms = manager.getRoomsHostedBy(serviceJid);
            hostedRooms.values().stream()
                    .map(room -> buildRoom(manager, room))
                    .filter(Objects::nonNull)
                    .forEach(r -> rooms.getRooms().add(r));

        } catch (XMPPException.XMPPErrorException e) {
            log.error("Room does not exists, return null");
            return rooms;
        } catch (SmackException.NotConnectedException e) {
            throw new IllegalArgumentException("Unable to connect to server", e);
        } catch (XmppStringprepException e) {
            throw new IllegalArgumentException("Invalid room Jid", e);
        } catch (SmackException.NoResponseException e) {
            throw new IllegalArgumentException("Server timeout while retrieving room", e);
        } catch (MultiUserChatException.NotAMucServiceException e) {
            throw new IllegalArgumentException("No Service Exists", e);
        } catch (InterruptedException e) {
            throw new IllegalArgumentException("Interrupted Exception", e);
        }
        return rooms;
    }

    /**
     * @param manager
     * @param hostedRoom
     * @return Room
     * @throws IllegalArgumentException
     */
    private Room buildRoom(MultiUserChatManager manager, HostedRoom hostedRoom) {
        try {
            EntityBareJid roomJid = JidCreate.entityBareFrom(hostedRoom.getJid().toString());
            RoomInfo roomInfo = manager.getRoomInfo(roomJid);
            Room room = new Room();
            room.setRoomName(hostedRoom.getJid().getLocalpartOrNull().toString());
            room.setDescription(roomInfo.getDescription());
            return room;
        } catch (XMPPException.XMPPErrorException e) {
            throw new IllegalArgumentException("Room does not exists", e);
        } catch (SmackException.NotConnectedException e) {
            throw new IllegalArgumentException("Unable to connect to server", e);
        } catch (XmppStringprepException e) {
            throw new IllegalArgumentException("Invalid room Jid", e);
        } catch (SmackException.NoResponseException e) {
            throw new IllegalArgumentException("Server timeout while retrieving room", e);
        } catch (InterruptedException e) {
            throw new IllegalArgumentException("Interrupted Exception", e);
        }
    }

    /**
     * Interface to update any given property value within the open-fire server.
     * @param propertyId Property that require update
     * @param params     New data that to be applied to the property
     * @return Handled openfire response.
     * @throws AssetMessageException
     */
    public ChatAssetResponse updateProperty(String propertyId, Map<String, Object> params)
            throws AssetMessageException {
        PropertyHandler propertyHandler =
                propertyHandlerFactory.createPropertyHandler(propertyId, this, propertyService, commandHandlerFactory);
        return propertyHandler.setProperty(propertyId, params);
    }

    /**
     * Create user for participant
     * @param participantName
     */
    public void createAccount(String participantName) {
        log.info("Creating participant on Openfire for [{}]", participantName);
        AccountManager accountManager = AccountManager.getInstance(xmppConnectionManager.getSystemConnection());
        accountManager.sensitiveOperationOverInsecureConnection(true);
        try {
            accountManager.createAccount(
                    Localpart.from(participantName), chatGatewayProperties.getParticipantPassword());
        } catch (XMPPException.XMPPErrorException
                 | SmackException.NoResponseException
                 | SmackException.NotConnectedException
                 | InterruptedException e) {
            log.error("Error when create Account [{}]", e.getMessage());
            throw new IllegalArgumentException("Error when create Account ", e);
        }
    }

    public void deleteAccount(String participantName) {
        log.info("Deleting participant on Openfire for [{}]", participantName);

        XMPPTCPConnection participantConnection = xmppConnectionManager.getParticipantConnection(
                participantName, chatGatewayProperties.getParticipantPassword());
        AccountManager accountManager = AccountManager.getInstance(participantConnection);
        accountManager.sensitiveOperationOverInsecureConnection(true);

        try {
            accountManager.deleteAccount();
            participantConnection.instantShutdown();
        } catch (XMPPException.XMPPErrorException
                 | SmackException.NoResponseException
                 | SmackException.NotConnectedException
                 | InterruptedException e) {
            log.error("Error when delete Account [{}]", e.getMessage());
            throw new IllegalArgumentException("Error when delete Account ", e);
        }
    }

    /** This method send message to the room
     * @param roomName
     * @param participantName
     * @param message
     */
    public String sendMessageToRoom(String roomName, String participantName, String message) {
        try {
            MultiUserChat muc = checkParticipantJoinedTheRoom(roomName, participantName);

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

    /** This method deletes a message from a room
     * @param roomName
     * @param participantName
     * @param messageId
     */
    public void deleteMessageFromRoom(String roomName, String participantName, String messageId) {
        try {
            // validate participant in the room before delete
            checkParticipantJoinedTheRoom(roomName, participantName);

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
     * Joined the participant to the room with default password provided on config properties
     * @param roomName
     * @param participantName
     * @return MultiUserChat
     */
    public MultiUserChat checkParticipantJoinedTheRoom(String roomName, String participantName)
            throws IllegalArgumentException {

        String roomKey = roomName + ":" + participantName;

        if (!joinedRooms.containsKey(roomKey)) {
            createAndSentNotification(
                    "Unauthorised",
                    "Participant [" + participantName + "] not authorized to send message to the room [" + roomName + "]");
            throw new IllegalArgumentException(participantName + " not joined to the room " + roomName);
        }

        return joinedRooms.get(roomKey);
    }

    /**
     * Joined the participant to the room with default password provided on config properties
     * @param roomName
     * @param participantName
     * @return MultiUserChat
     */
    public MultiUserChat joinParticipantToTheRoom(String roomName, String participantName)
            throws IllegalArgumentException {

        String roomKey = roomName + ":" + participantName;

        if (joinedRooms.containsKey(roomKey)) {
            return joinedRooms.get(roomKey);
        }

        try {
            XMPPTCPConnection participantConnection = xmppConnectionManager.getParticipantConnection(
                    participantName, chatGatewayProperties.getParticipantPassword());

            MultiUserChatManager manager = chatManagerProvider.getManager(participantConnection);

            String roomJidString = buildEntityBareId(
                    roomName, chatGatewayProperties.getServiceName(), chatGatewayProperties.getDomain());
            EntityBareJid roomJid = JidCreate.entityBareFrom(roomJidString.toString());
            MultiUserChat muc = manager.getMultiUserChat(roomJid);

            /* join without replaying old messages */
            MucEnterConfiguration.Builder builder =
                    muc.getEnterConfigurationBuilder(Resourcepart.from(participantName));
            builder.requestNoHistory();

            eventListenerService.registerUserStatusListeners(roomName, participantName, muc, () ->
                    removeParticipantFromRoomTracking(roomKey, roomName, participantName));

            muc.join(builder.build());
            joinedRooms.put(roomKey, muc);

            participantRooms
                    .computeIfAbsent(participantName, k -> ConcurrentHashMap.newKeySet())
                    .add(roomName);

            log.info("Participant [{}] joined [{}] room and register the listener", participantName, roomName);
            return muc;

        } catch (Exception e) {
            log.error("Error when joining participant [{}] to the room [{}]", participantName, roomName, e);
            throw new IllegalArgumentException("Error when joining participant to the room ", e);
        }
    }

    /**
     * Remove existing participant from the room
     * @param roomName
     * @param userName
     * @return
     * @throws IllegalArgumentException
     */
    public MultiUserChat removeParticipantFromTheRoom(String roomName, String userName)
            throws IllegalArgumentException {

        String roomKey = roomName + ":" + userName;

        try {
            MultiUserChat muc = joinedRooms.get(roomKey);
            if (muc == null) {
                log.warn("Participant [{}] is not in room [{}] nothing to remove", userName, roomName);
                return null;
            }

            /* remove room from participant tracking */
            Set<String> rooms = participantRooms.get(userName);
            if (rooms != null) {
                rooms.remove(roomName);
                log.info("Participant [{}] remaining rooms: {}", userName, rooms);
            }

            if (muc.isJoined()) {
                muc.leave();
            }

            joinedRooms.remove(roomKey);

            if (rooms != null && rooms.isEmpty()) {
                participantRooms.remove(userName);
                xmppConnectionManager.disconnectParticipant(userName);
                log.info("Disconnected participant [{}] - no more rooms: {}", userName);
            }

            log.info("Participant [{}] left room [{}] ", userName, roomName);
            return muc;

        } catch (Exception e) {
            log.error("Error leaving room [{}] for participant [{}]", roomName, userName, e);
            throw new IllegalArgumentException("Error leaving participant from room ", e);
        }
    }

    /**
     * Reads messages from a MUC room direct query from database
     * @param roomName   the room to read messages from
     * @param messageIds specific message IDs to retrieve, or empty/null for all
     * @return list of MessageResponse objects
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
    private List<MessageResponse> mapToMessageResponse(List<MessageRow> messageRows, String roomName) {
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

    /** Create and Send Notification to Notification Service
     * @param title
     * @param content
     */
    public void createAndSentNotification(String title, String content) {
        notificationsService.createNotification(
                title,
                content,
                Notification.NotificationTypeEnum.INFORMATIONAL_ALERT,
                NotificationPriority.INFORMATION);
    }

    public void registerListenerService(String roomName, MultiUserChat muc) {
        eventListenerService.registerRoomListeners(roomName, muc);
    }

    private void removeParticipantFromRoomTracking(String roomKey, String roomName, String participantName) {
        joinedRooms.remove(roomKey);
        Optional.ofNullable(participantRooms.get(participantName))
                .ifPresent(rooms -> rooms.remove(roomName));
    }

    /*
     * This method reattach the room-watcher (join and listener registration) to every
     * existing room on startup, so an explicit muc.join is required before listeners
     * can receive anything
     */
    private void addRoomsListener() {
        Rooms rooms = listRoomFromServer();
        rooms.getRooms().forEach(room -> {
            MultiUserChatManager manager = getManager();
            String roomJidString = buildEntityBareId(
                    room.getRoomName(), chatGatewayProperties.getServiceName(), chatGatewayProperties.getDomain());
            try {
                EntityBareJid roomJid = JidCreate.entityBareFrom(roomJidString);
                MultiUserChat muc = manager.getMultiUserChat(roomJid);
                if (!muc.isJoined()) {
                    muc.join(Resourcepart.from("system-watcher"));
                    log.info("Watcher joined room [{}]", room.getRoomName());
                }
                registerListenerService(room.getRoomName(), muc);

            } catch (XmppStringprepException
                     | SmackException.NoResponseException
                     | XMPPException.XMPPErrorException
                     | SmackException.NotConnectedException
                     | InterruptedException
                     | MultiUserChatException.NotAMucServiceException e) {
                throw new IllegalArgumentException("Error when registering listener");
            }
        });
    }

    public void removeRoomListeners(String roomName, MultiUserChat muc) {
        eventListenerService.removeRoomListeners(roomName, muc);
    }

    /**
     * Reads the admin.authorizedJIDs system property and extracts the usernames of the admin
     * accounts that should be preserved during reset-all command
     *
     * @param restClient client used to query the Openfire admin properties
     * @return the set of admin usernames to exclude from deletion
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
     *
     * @param restClient     client used to list and delete users
     * @param adminUsernames usernames to not delete
     * @return the number of users deleted.
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
     *
     * @param adminUsernames usernames to keep
     */
    private void removeDeletedUsersFromParticipants(Set<String> adminUsernames) {
        ChatAssetResponse chatAssetResponse = readProperty(PropertyConstants.PARTICIPANTS);
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
     *
     * @param restClient client used to delete rooms via the REST api
     * @return RoomsAndMessagesDeletedResponse
     */
    private RoomsAndMessagesDeletedResponse deleteAllRoomsAndMessages(RestClient restClient) {
        Rooms rooms = listRoomFromServer();

        int roomsDeleted = 0;
        int messagesDeleted = 0;

        if (rooms == null || rooms.getRooms() == null) {
            return new RoomsAndMessagesDeletedResponse();
        }

        for (Room room : rooms.getRooms()) {
            String roomKey = room.getRoomName();

            try {
                List<MessageResponse> messages = readMessageByParticipantAndRoom(roomKey, new ArrayList<>());
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
                String roomJidString = buildEntityBareId(
                        roomKey, chatGatewayProperties.getServiceName(), chatGatewayProperties.getDomain());
                MultiUserChatManager manager = getManager();
                EntityBareJid roomJid = JidCreate.entityBareFrom(roomJidString);
                MultiUserChat muc = manager.getMultiUserChat(roomJid);

                if (muc.isJoined()) {
                    eventListenerService.removeRoomListeners(roomKey, muc);
                    muc.leave();
                }
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

        Rooms remainingRooms = listRoomFromServer();
        propertyService.sendObjectToPropService(PropertyConstants.ROOMS, remainingRooms.getRooms());

        return new RoomsAndMessagesDeletedResponse(roomsDeleted, messagesDeleted);
    }

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
        joinedRooms.clear();
        participantRooms.clear();

        log.info(
                "Reset all complete - usersDeleted={}, roomsDeleted={}, messagesDeleted={}",
                usersDeleted,
                roomsDeleted,
                messagesDeleted);

        return response;
    }
}
