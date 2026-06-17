/**
 * Reads messages from a MUC room for a specific participant.
 *
 * This method temporarily rejoins the room with history enabled to replay
 * past messages. Two modes are supported:
 *
 *   - fetchAll (messageIds is null or empty):
 *     Rejoins the room requesting the last 500 messages and waits 2 seconds
 *     for Openfire to replay all history stanzas. 500 is used as a safe upper
 *     bound — adjust if rooms are expected to have more messages.
 *
 *   - fetchByIds (messageIds provided):
 *     Rejoins and collects only messages whose stanzaId matches one of the
 *     provided IDs. A CountDownLatch releases early once all IDs are found,
 *     or after a 10-second timeout if some IDs are not found in history.
 *
 * Why join and re-leave:
 *   Smack replays history only on join. The participant must leave first to
 *   trigger a fresh join with history. After reading, the participant rejoins
 *   without history to restore normal real-time message flow.
 *
 * Why a temp listener:
 *   History messages arrive via the same MessageListener as live messages.
 *   A temporary listener is added before join to intercept the replayed
 *   stanzas without interfering with the existing room listener.
 *
 * @param roomName        the room to read messages from
 * @param participantName the participant performing the read
 * @param messageIds      specific message IDs to retrieve, or empty/null for all
 * @return list of MessageResponse objects
 * @throws IllegalArgumentException if participant is not in the room
 */
public List<MessageResponse> readMessagesByParticipantAndRoom(
        String roomName,
        String participantName,
        List<String> messageIds) {

    MultiUserChat muc = getJoinedMucOrThrow(roomName, participantName);

    boolean fetchAll = messageIds == null || messageIds.isEmpty();

    List<Message> foundMessages = fetchAll
            ? fetchAllMessages(muc, participantName, roomName)
            : fetchMessagesByIds(muc, participantName, roomName, messageIds);

    return mapToMessageResponse(foundMessages, roomName);
}

// ─────────────────────────────────────────────────────────────────────────────
// Private helpers
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Gets the joined MUC instance from cache.
 * Throws IllegalArgumentException (4xx) if participant is not in the room.
 */
private MultiUserChat getJoinedMucOrThrow(String roomName, String participantName) {
    String roomKey = roomName + ":" + participantName;
    MultiUserChat muc = joinedRooms.get(roomKey);
    if (muc == null) {
        throw new IllegalArgumentException(
                "Participant [" + participantName + "] is not in room [" + roomName + "]");
    }
    return muc;
}

/**
 * Fetches ALL messages in the room by rejoining with history.
 *
 * Waits 2 seconds after join for Openfire to finish replaying history stanzas.
 * The 2-second window is a pragmatic choice — increase if messages are missed
 * on slow networks or large rooms.
 */
private List<Message> fetchAllMessages(MultiUserChat muc,
                                       String participantName,
                                       String roomName) {
    List<Message> foundMessages = new CopyOnWriteArrayList<>();

    MessageListener tempListener = message -> {
        if (message.getBody() != null) {
            foundMessages.add(message);
        }
    };

    muc.addMessageListener(tempListener);
    try {
        rejoinWithHistory(muc, participantName);
        Thread.sleep(2000);
        log.info("Retrieved [{}] messages from room [{}]",
                foundMessages.size(), roomName);
    } catch (Exception e) {
        log.error("Error fetching all messages from room [{}]", roomName, e);
        throw new IllegalArgumentException("Error fetching messages", e);
    } finally {
        muc.removeMessageListener(tempListener);
        rejoinWithoutHistory(muc, participantName, roomName);
    }

    return foundMessages;
}

/**
 * Fetches specific messages by ID from room history.
 *
 * Uses a CountDownLatch that releases early when all requested IDs are found,
 * or times out after 10 seconds if some IDs are not present in history
 * (e.g. older than the 500-message window or already retracted).
 */
private List<Message> fetchMessagesByIds(MultiUserChat muc,
                                         String participantName,
                                         String roomName,
                                         List<String> messageIds) {
    List<Message> foundMessages = new CopyOnWriteArrayList<>();
    CountDownLatch latch = new CountDownLatch(1);

    MessageListener tempListener = message -> {
        if (message.getBody() == null) return;
        String stanzaId = message.getStanzaId();
        if (stanzaId != null && messageIds.contains(stanzaId)) {
            foundMessages.add(message);
            if (foundMessages.size() == messageIds.size()) {
                latch.countDown();
            }
        }
    };

    muc.addMessageListener(tempListener);
    try {
        rejoinWithHistory(muc, participantName);
        boolean completed = latch.await(10, TimeUnit.SECONDS);
        if (!completed) {
            log.warn("Only found [{}/{}] messages within timeout",
                    foundMessages.size(), messageIds.size());
        }
    } catch (Exception e) {
        log.error("Error fetching messages by ID from room [{}]", roomName, e);
        throw new IllegalArgumentException("Error fetching messages", e);
    } finally {
        muc.removeMessageListener(tempListener);
        rejoinWithoutHistory(muc, participantName, roomName);
    }

    return foundMessages;
}

/**
 * Leaves the room (if joined) and rejoins requesting the last 500 messages.
 *
 * 500 is used as the history limit to balance completeness with performance.
 * Rooms with more than 500 messages will only replay the most recent 500.
 */
private void rejoinWithHistory(MultiUserChat muc, String participantName)
        throws Exception {
    if (muc.isJoined()) {
        muc.leave();
    }
    MucEnterConfiguration config = muc
            .getEnterConfigurationBuilder(Resourcepart.from(participantName))
            .requestMaxStanzasHistory(500)
            .build();
    muc.join(config);
}

/**
 * Rejoins the room without requesting history.
 * Called in finally block to restore normal real-time message flow
 * after a history read operation.
 */
private void rejoinWithoutHistory(MultiUserChat muc,
                                  String participantName,
                                  String roomName) {
    try {
        if (!muc.isJoined()) {
            MucEnterConfiguration config = muc
                    .getEnterConfigurationBuilder(Resourcepart.from(participantName))
                    .requestNoHistory()
                    .build();
            muc.join(config);
            log.info("Rejoined room [{}] after read", roomName);
        }
    } catch (Exception e) {
        log.error("Error rejoining room [{}] after read", roomName, e);
    }
}

/**
 * Maps raw Smack Message objects to MessageResponse DTOs.
 * Extracts the original timestamp from DelayInformation for history messages.
 */
private List<MessageResponse> mapToMessageResponse(List<Message> messages,
                                                   String roomName) {
    return messages.stream()
            .map(message -> {
                DelayInformation delay = DelayInformation.from(message);
                Instant timestamp = delay != null
                        ? delay.getStamp().toInstant()
                        : Instant.now();

                return MessageResponse.builder()
                        .messageId(message.getStanzaId())
                        .message(message.getBody())
                        .sender(message.getFrom().getResourceOrEmpty().toString())
                        .roomName(roomName)
                        .timestamp(timestamp)
                        .isDelayed(delay != null)
                        .build();
            })
            .collect(Collectors.toList());
}
