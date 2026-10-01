package com.babcock.inspire.openfire.services;

// Proposed split of ChatService.java - see ChatService.java (facade) in this same
// folder for the full rationale. This service owns everything about room
// lifecycle and room/participant tracking:
//   - creating/looking up rooms on the Openfire server
//   - joining/removing participants to/from rooms
//   - the joinedRooms / participantRooms maps (moved here from ChatService)
//   - room-level listener (re)registration, including the restart-time
//     addRoomsListener() sweep
//
// Ownership decision: joinedRooms/participantRooms are read and written by
// join/remove flows AND wiped by resetAll() in ResetAllService. Rather than
// exposing the maps themselves, this class exposes a narrow
// clearAllRoomTracking() method that ResetAllService calls - keeps the maps
// private and the invariant (roomKey = roomName + ":" + participantName)
// enforced in one place.
//
// createAndGetRoom(...) was not visible in the video pass that produced
// ChatService.java, but was referenced from CreateRoomCommand
// ("service.createAndGetRoom(roomJidString, manager, roomName, roomDescription,
// chatGatewayProperties)") and was the subject of the earlier "re-creating
// already-joined rooms on restart" bug fix (isJoined guard). Reconstructed
// here from that earlier discussion - please diff carefully against your
// real method, this one is lower-confidence than the rest of the file.

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smackx.muc.HostedRoom;
import org.jivesoftware.smackx.muc.MucCreateConfigFormHandle;
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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RequiredArgsConstructor
public class RoomService {

    private final ChatGatewayProperties chatGatewayProperties;
    private final ChatManagerProvider chatManagerProvider;
    private final XmppConnectionManager xmppConnectionManager;
    private final EventListenerService eventListenerService;
    private final NotificationsService notificationsService;

    /** roomKey ("roomName:participantName") -> joined MultiUserChat handle */
    private final Map<String, MultiUserChat> joinedRooms = new ConcurrentHashMap<>();

    /** participants -> joined rooms names */
    private final Map<String, Set<String>> participantRooms = new ConcurrentHashMap<>();

    public MultiUserChatManager getManager() {
        return chatManagerProvider.getManager(xmppConnectionManager.getSystemConnection());
    }

    /**
     * Creates the room on the server if it does not already exist/is not already
     * joined by the system connection, otherwise returns the existing room info
     * and makes sure its listeners are (re)registered. Guards against re-creating
     * a room that is already joined, which previously happened on service restart.
     *
     * @param roomJidString the full room JID, e.g. "room@conference.domain"
     * @param manager       the MultiUserChatManager to create/fetch the room with
     * @param roomName      the room's local name
     * @param roomDescription description to set on a newly-created room
     * @param chatGatewayProperties config used for service name/domain when rebuilding JIDs
     * @return RoomInfo describing the (possibly newly-created) room
     * @throws IllegalArgumentException on any XMPP/connection failure
     */
    public RoomInfo createAndGetRoom(
            String roomJidString,
            MultiUserChatManager manager,
            String roomName,
            String roomDescription,
            ChatGatewayProperties chatGatewayProperties) {

        try {
            EntityBareJid roomJid = JidCreate.entityBareFrom(roomJidString);
            MultiUserChat muc = manager.getMultiUserChat(roomJid);

            if (muc.isJoined()) {
                // Already created/joined (e.g. service restarted) - don't re-create,
                // just make sure the watcher listeners are attached and return the
                // existing room info.
                log.info("Room [{}] already joined, skipping create", roomName);
                registerListenerService(roomName, muc);
                return manager.getRoomInfo(roomJid);
            }

            MucCreateConfigFormHandle configFormHandle =
                    muc.create(Resourcepart.from(roomName));
            configFormHandle.makeInstant();

            if (!muc.isJoined()) {
                muc.join(Resourcepart.from("system-watcher"));
            }

            registerListenerService(roomName, muc);

            log.info("Created room [{}] with description [{}]", roomName, roomDescription);
            return manager.getRoomInfo(roomJid);

        } catch (XMPPException.XMPPErrorException
                 | SmackException.NotConnectedException
                 | XmppStringprepException
                 | SmackException.NoResponseException
                 | MultiUserChatException.MucAlreadyJoinedException
                 | MultiUserChatException.NotAMucServiceException
                 | InterruptedException e) {
            log.error("Error when creating room [{}]", roomName, e);
            throw new IllegalArgumentException("Error when creating room ", e);
        }
    }

    /**
     * @param roomJidString
     * @return Room
     * @throws IllegalArgumentException
     */
    public Room getRoomFromServer(String roomJidString) throws IllegalArgumentException {

        MultiUserChatManager manager = getManager();

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
        MultiUserChatManager manager = getManager();
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
     * Joined the participant to the room with default password provided on config properties
     */
    public MultiUserChat checkParticipantJoinedTheRoom(String roomName, String participantName)
            throws IllegalArgumentException {

        String roomKey = roomName + ":" + participantName;

        if (!joinedRooms.containsKey(roomKey)) {
            notificationsService.createNotification(
                    "Unauthorised",
                    "Participant [" + participantName + "] not authorized to send message to the room [" + roomName + "]",
                    Notification.NotificationTypeEnum.INFORMATIONAL_ALERT,
                    NotificationPriority.INFORMATION);
            throw new IllegalArgumentException(participantName + " not joined to the room " + roomName);
        }

        return joinedRooms.get(roomKey);
    }

    /**
     * Joined the participant to the room with default password provided on config properties
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

    public void registerListenerService(String roomName, MultiUserChat muc) {
        eventListenerService.registerRoomListeners(roomName, muc);
    }

    public void removeRoomListeners(String roomName, MultiUserChat muc) {
        eventListenerService.removeRoomListeners(roomName, muc);
    }

    private void removeParticipantFromRoomTracking(String roomKey, String roomName, String participantName) {
        joinedRooms.remove(roomKey);
        Optional.ofNullable(participantRooms.get(participantName))
                .ifPresent(rooms -> rooms.remove(roomName));
    }

    /**
     * Re-attaches the room-watcher (join + listener registration) to every
     * existing room on startup. An explicit muc.join is required first, since
     * getMultiUserChat() alone does not join - listeners attached to an
     * unjoined MultiUserChat never fire.
     */
    public void addRoomsListener() {
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

    /**
     * Used by ResetAllService to wipe room/participant tracking after all
     * rooms have been deleted from the server. Kept here (rather than exposing
     * the maps directly) so the roomKey convention stays private to this class.
     */
    public void clearAllRoomTracking() {
        joinedRooms.clear();
        participantRooms.clear();
    }

    /** Leaves/unregisters listeners for a room and removes it from tracking - used during bulk delete. */
    public void leaveAndUnregister(String roomName, MultiUserChat muc) {
        if (muc.isJoined()) {
            eventListenerService.removeRoomListeners(roomName, muc);
            muc.leave();
        }
    }

    private static String buildEntityBareId(String roomName, String serviceName, String domain) {
        return roomName + "@" + serviceName + "." + domain;
    }
}
