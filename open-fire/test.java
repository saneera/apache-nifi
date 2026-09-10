@Component
public class RoomParticipantTracker {

    private final Map<String, MultiUserChat> joinedRooms = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> participantRooms = new ConcurrentHashMap<>();

    public void trackJoin(String roomName, String participantName, MultiUserChat muc) {
        String roomKey = roomName + ":" + participantName;
        joinedRooms.put(roomKey, muc);
        participantRooms
                .computeIfAbsent(participantName, k -> ConcurrentHashMap.newKeySet())
                .add(roomName);
        log.info("Tracked join room=[{}] participant=[{}]", roomName, participantName);
    }

    public void trackLeave(String roomName, String participantName) {
        String roomKey = roomName + ":" + participantName;
        joinedRooms.remove(roomKey);

        Set<String> rooms = participantRooms.get(participantName);
        if (rooms != null) {
            rooms.remove(roomName);
            if (rooms.isEmpty()) {
                participantRooms.remove(participantName);
            }
        }
        log.info("Tracked leave room=[{}] participant=[{}]", roomName, participantName);
    }

    public MultiUserChat getJoinedRoom(String roomName, String participantName) {
        return joinedRooms.get(roomName + ":" + participantName);
    }

    public boolean isJoined(String roomName, String participantName) {
        return joinedRooms.containsKey(roomName + ":" + participantName);
    }

    public Set<String> getRoomsForParticipant(String participantName) {
        return participantRooms.getOrDefault(participantName, Set.of());
    }

    public Map<String, MultiUserChat> getJoinedRooms() {
        return joinedRooms;
    }
}

public MultiUserChat joinParticipantToTheRoom(String roomName, String participantName)
        throws IllegalArgumentException {

    if (roomParticipantTracker.isJoined(roomName, participantName)) {
        return roomParticipantTracker.getJoinedRoom(roomName, participantName);
    }

    try {
        // ... existing connection/manager/muc setup unchanged ...

        eventListenerService.registerRoomListeners(roomName, muc);

        muc.join(builder.build());
        roomParticipantTracker.trackJoin(roomName, participantName, muc);

        log.info("Participant [{}] joined [{}] room and register the listener", participantName, roomName);
        return muc;
    } catch (Exception e) {
        // ... existing catch blocks ...
    }
}


private ParticipantStatusListener getParticipantStatusListener(String roomName) {
    return new ParticipantStatusListener() {

        @Override
        public void joined(EntityFullJid participant) {
            log.info("PARTICIPANT JOINED room=[{}] participant=[{}]", roomName, participant);
            String participantName = participant.getResourcepart().toString();
            roomParticipantTracker.trackJoin(roomName, participantName, /* muc reference needed here */);
        }

        @Override
        public void left(EntityFullJid participant) {
            log.info("PARTICIPANT LEFT room=[{}] participant=[{}]", roomName, participant);
            roomParticipantTracker.trackLeave(roomName, participant.getResourcepart().toString());
        }

        @Override
        public void kicked(EntityFullJid participant, Jid actor, String reason) {
            log.info("PARTICIPANT KICKED room=[{}] participant=[{}] actor=[{}] reason=[{}]",
                    roomName, participant, actor, reason);
            roomParticipantTracker.trackLeave(roomName, participant.getResourcepart().toString());
        }

        @Override
        public void banned(EntityFullJid participant, Jid actor, String reason) {
            log.info("PARTICIPANT BANNED room=[{}] participant=[{}] actor=[{}] reason=[{}]",
                    roomName, participant, actor, reason);
            roomParticipantTracker.trackLeave(roomName, participant.getResourcepart().toString());
        }
    };
}
