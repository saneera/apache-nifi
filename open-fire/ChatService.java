package com.babcock.inspire.openfire.services;

// Proposed slimmed-down ChatService, after splitting room/messaging/account/
// reset-all logic out into RoomService, MessagingService,
// ParticipantAccountService and ResetAllService (see those files in this same
// folder). This file keeps the SAME public method signatures the command
// handlers already call via their `service` field (AbstractCommandHandler),
// so CreateRoomCommand/AddParticipantCommand/AddParticipantsToRoomCommand/etc.
// need zero changes - ChatService just delegates instead of implementing
// everything itself.
//
// Added getChatGatewayProperties() - this wasn't in the previous reconstruction
// but is called directly from AddParticipantsToRoomCommand and CreateRoomCommand
// ("service.getChatGatewayProperties()"), so the facade needs to expose it even
// though ChatService itself no longer does much with the properties object
// beyond handing it to the new services in the constructor.
//
// NOTE on the ResetAllService circular-dependency: see the comment at the top
// of ResetAllService.java - resetAll() delegates down to ResetAllService, which
// in turn calls back up into this facade's readProperty() for one lookup. This
// file injects ResetAllService normally (not @Lazy) because the cycle is broken
// on ResetAllService's side instead; only one side of a Spring circular
// dependency needs @Lazy for the proxy trick to work.

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jivesoftware.smackx.muc.MultiUserChat;
import org.jivesoftware.smackx.muc.MultiUserChatManager;
import org.jivesoftware.smackx.muc.RoomInfo;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.annotation.PreDestroy;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final AssetHandlerStateStoreConfig stateStoreConfig;
    private final AssetHandlerStateStore store;
    private final ChatGatewayProperties chatGatewayProperties;
    private final CommandHandlerFactory commandHandlerFactory;
    private final PropertyHandlerFactory propertyHandlerFactory;
    private final XmppConnectionManager xmppConnectionManager;
    private final PropertyService propertyService;

    private final RoomService roomService;
    private final MessagingService messagingService;
    private final ParticipantAccountService participantAccountService;
    private final ResetAllService resetAllService;
    private final NotificationsService notificationsService;

    public ChatGatewayProperties getChatGatewayProperties() {
        return chatGatewayProperties;
    }

    public MultiUserChatManager getManager() {
        return roomService.getManager();
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
        traffic.name(store.getPropertyValue("name")); // UNCERTAIN - carried over from earlier reconstruction
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
        roomService.addRoomsListener();
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

    public ChatAssetResponse updateProperty(String propertyId, Map<String, Object> params)
            throws AssetMessageException {
        PropertyHandler propertyHandler =
                propertyHandlerFactory.createPropertyHandler(propertyId, this, propertyService, commandHandlerFactory);
        return propertyHandler.setProperty(propertyId, params);
    }

    // === Room operations - delegate to RoomService ===

    public RoomInfo createAndGetRoom(
            String roomJidString, MultiUserChatManager manager, String roomName,
            String roomDescription, ChatGatewayProperties chatGatewayProperties) {
        return roomService.createAndGetRoom(roomJidString, manager, roomName, roomDescription, chatGatewayProperties);
    }

    public Room getRoomFromServer(String roomJidString) {
        return roomService.getRoomFromServer(roomJidString);
    }

    public Rooms listRoomFromServer() {
        return roomService.listRoomFromServer();
    }

    public MultiUserChat checkParticipantJoinedTheRoom(String roomName, String participantName) {
        return roomService.checkParticipantJoinedTheRoom(roomName, participantName);
    }

    public MultiUserChat joinParticipantToTheRoom(String roomName, String participantName) {
        return roomService.joinParticipantToTheRoom(roomName, participantName);
    }

    public MultiUserChat removeParticipantFromTheRoom(String roomName, String userName) {
        return roomService.removeParticipantFromTheRoom(roomName, userName);
    }

    public void registerListenerService(String roomName, MultiUserChat muc) {
        roomService.registerListenerService(roomName, muc);
    }

    public void removeRoomListeners(String roomName, MultiUserChat muc) {
        roomService.removeRoomListeners(roomName, muc);
    }

    // === Account operations - delegate to ParticipantAccountService ===

    public void createAccount(String participantName) {
        participantAccountService.createAccount(participantName);
    }

    public void deleteAccount(String participantName) {
        participantAccountService.deleteAccount(participantName);
    }

    // === Messaging operations - delegate to MessagingService ===

    public String sendMessageToRoom(String roomName, String participantName, String message) {
        return messagingService.sendMessageToRoom(roomName, participantName, message);
    }

    public void deleteMessageFromRoom(String roomName, String participantName, String messageId) {
        messagingService.deleteMessageFromRoom(roomName, participantName, messageId);
    }

    public List<MessageResponse> readMessageByParticipantAndRoom(String roomName, List<String> messageIds) {
        return messagingService.readMessageByParticipantAndRoom(roomName, messageIds);
    }

    // === Reset-all - delegate to ResetAllService ===

    public ResetAllResponse resetAll() {
        return resetAllService.resetAll();
    }

    // === Notifications ===

    public void createAndSentNotification(String title, String content) {
        notificationsService.createNotification(
                title,
                content,
                Notification.NotificationTypeEnum.INFORMATIONAL_ALERT,
                NotificationPriority.INFORMATION);
    }
}
