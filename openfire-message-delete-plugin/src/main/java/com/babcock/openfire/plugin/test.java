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

    } catch (SmackException.NotConnectedException e) {
        log.error("Not connected when fetching messages from room [{}]", roomName, e);
        throw new IllegalArgumentException(
                "Not connected to room: " + roomName, e);

    } catch (SmackException.NoResponseException e) {
        log.error("No response from server for room [{}]", roomName, e);
        throw new IllegalArgumentException(
                "No response from server for room: " + roomName, e);

    } catch (XMPPException.XMPPErrorException e) {
        log.error("XMPP error fetching messages from room [{}]", roomName, e);
        throw new IllegalArgumentException(
                "XMPP error for room: " + roomName, e);

    } catch (MultiUserChatException.NotAMucServiceException e) {
        log.error("Not a MUC service for room [{}]", roomName, e);
        throw new IllegalArgumentException(
                "Not a MUC service: " + roomName, e);

    } catch (MultiUserChatException.MucNotJoinedException e) {
        log.error("MUC not joined for room [{}]", roomName, e);
        throw new IllegalArgumentException(
                "MUC not joined: " + roomName, e);

    } catch (XmppStringprepException e) {
        log.error("Invalid participant name [{}]", participantName, e);
        throw new IllegalArgumentException(
                "Invalid participant name: " + participantName, e);

    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.error("Interrupted while fetching messages from room [{}]", roomName, e);
        throw new IllegalArgumentException(
                "Interrupted while fetching messages", e);

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

    } catch (SmackException.NotConnectedException e) {
        log.error("Not connected when fetching messages from room [{}]", roomName, e);
        throw new IllegalArgumentException(
                "Not connected to room: " + roomName, e);

    } catch (SmackException.NoResponseException e) {
        log.error("No response from server for room [{}]", roomName, e);
        throw new IllegalArgumentException(
                "No response from server for room: " + roomName, e);

    } catch (XMPPException.XMPPErrorException e) {
        log.error("XMPP error fetching messages from room [{}]", roomName, e);
        throw new IllegalArgumentException(
                "XMPP error for room: " + roomName, e);

    } catch (MultiUserChatException.NotAMucServiceException e) {
        log.error("Not a MUC service for room [{}]", roomName, e);
        throw new IllegalArgumentException(
                "Not a MUC service: " + roomName, e);

    } catch (MultiUserChatException.MucNotJoinedException e) {
        log.error("MUC not joined for room [{}]", roomName, e);
        throw new IllegalArgumentException(
                "MUC not joined: " + roomName, e);

    } catch (XmppStringprepException e) {
        log.error("Invalid participant name [{}]", participantName, e);
        throw new IllegalArgumentException(
                "Invalid participant name: " + participantName, e);

    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.error("Interrupted while fetching messages from room [{}]", roomName, e);
        throw new IllegalArgumentException(
                "Interrupted while fetching messages", e);

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
        throws SmackException.NotConnectedException,
        SmackException.NoResponseException,
        XMPPException.XMPPErrorException,
        MultiUserChatException.NotAMucServiceException,
        MultiUserChatException.MucNotJoinedException,
        XmppStringprepException,
        InterruptedException {

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
    } catch (SmackException.NotConnectedException e) {
        log.error("Not connected rejoining room [{}]", roomName, e);
    } catch (SmackException.NoResponseException e) {
        log.error("No response rejoining room [{}]", roomName, e);
    } catch (XMPPException.XMPPErrorException e) {
        log.error("XMPP error rejoining room [{}]", roomName, e);
    } catch (MultiUserChatException.NotAMucServiceException e) {
        log.error("Not a MUC service rejoining room [{}]", roomName, e);
    } catch (MultiUserChatException.MucNotJoinedException e) {
        log.error("MUC not joined rejoining room [{}]", roomName, e);
    } catch (XmppStringprepException e) {
        log.error("Invalid participant name [{}] rejoining room [{}]",
                participantName, roomName, e);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.error("Interrupted rejoining room [{}]", roomName, e);
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




/**
 * Fetches ALL messages in the room asynchronously using CompletableFuture.
 *
 * Returns a CompletableFuture that completes when history replay is done.
 * The future is completed by the subject listener (end-of-history signal)
 * or after a 2-second sleep as a fallback.
 *
 * Caller uses fetchAllMessages().get() to block until messages are ready.
 */
private CompletableFuture<List<Message>> fetchAllMessages(
        MultiUserChat muc,
        String participantName,
        String roomName) {

    CompletableFuture<List<Message>> future = new CompletableFuture<>();
    List<Message> foundMessages = new CopyOnWriteArrayList<>();

    MessageListener tempListener = message -> {
        if (message.getBody() != null) {
            foundMessages.add(message);
        }
    };

    muc.addMessageListener(tempListener);

    // Run asynchronously — don't block the calling thread
    CompletableFuture.runAsync(() -> {
        try {
            rejoinWithHistory(muc, participantName);
            Thread.sleep(2000);   // wait for history replay to finish
            future.complete(foundMessages);   // ← signal done

        } catch (SmackException.NotConnectedException e) {
            future.completeExceptionally(
                    new IllegalArgumentException("Not connected to room: " + roomName, e));

        } catch (SmackException.NoResponseException e) {
            future.completeExceptionally(
                    new IllegalArgumentException("No response from server: " + roomName, e));

        } catch (XMPPException.XMPPErrorException e) {
            future.completeExceptionally(
                    new IllegalArgumentException("XMPP error for room: " + roomName, e));

        } catch (MultiUserChatException.NotAMucServiceException e) {
            future.completeExceptionally(
                    new IllegalArgumentException("Not a MUC service: " + roomName, e));

        } catch (MultiUserChatException.MucNotJoinedException e) {
            future.completeExceptionally(
                    new IllegalArgumentException("MUC not joined: " + roomName, e));

        } catch (XmppStringprepException e) {
            future.completeExceptionally(
                    new IllegalArgumentException("Invalid participant: " + participantName, e));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.completeExceptionally(
                    new IllegalArgumentException("Interrupted fetching messages", e));

        } finally {
            muc.removeMessageListener(tempListener);
            rejoinWithoutHistory(muc, participantName, roomName);
        }
    });

    return future;
}


public List<MessageResponse> readMessagesByParticipantAndRoom(
        String roomName,
        String participantName,
        List<String> messageIds) {

    MultiUserChat muc = getJoinedMucOrThrow(roomName, participantName);

    boolean fetchAll = messageIds == null || messageIds.isEmpty();

    List<Message> foundMessages;

    try {
        foundMessages = fetchAll
                ? fetchAllMessages(muc, participantName, roomName).get()   // ← .get() blocks here
                : fetchMessagesByIds(muc, participantName, roomName, messageIds);

    } catch (java.util.concurrent.ExecutionException e) {
        log.error("Error fetching messages from room [{}]", roomName, e);
        throw new IllegalArgumentException("Error fetching messages", e.getCause());

    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalArgumentException("Interrupted fetching messages", e);
    }

    return mapToMessageResponse(foundMessages, roomName);
}



===========



/**
 * Fetches ALL messages in the room asynchronously using CompletableFuture.
 *
 * Returns a CompletableFuture that completes after allowing time for
 * Openfire to finish replaying history stanzas following a room join.
 *
 * Why Thread.sleep:
 *   Smack provides no callback or signal for when history replay is complete.
 *   The subject-updated listener was considered but is unreliable — rooms
 *   without a subject set never receive a subject stanza after join.
 *   A timed wait is the only reliable approach without MAM support.
 *
 * Why 2000ms:
 *   Empirically chosen to give Openfire enough time to replay up to 500
 *   messages over the internal k8s network. Increase if messages are
 *   missed on slower environments.
 *
 * Caller uses fetchAllMessages().get() to block until messages are ready.
 *
 * @param muc             the joined MultiUserChat instance
 * @param participantName participant performing the read
 * @param roomName        room name for logging
 * @return CompletableFuture containing all collected messages
 */
private CompletableFuture<List<Message>> fetchAllMessages(
        MultiUserChat muc,
        String participantName,
        String roomName) {

    CompletableFuture<List<Message>> future = new CompletableFuture<>();
    List<Message> foundMessages = new CopyOnWriteArrayList<>();

    MessageListener tempListener = message -> {
        if (message.getBody() != null) {
            foundMessages.add(message);
        }
    };

    muc.addMessageListener(tempListener);

    CompletableFuture.runAsync(() -> {
        try {
            rejoinWithHistory(muc, participantName);

            // Wait for Openfire to finish replaying history.
            // No completion signal is available without MAM — see javadoc above.
            TimeUnit.MILLISECONDS.sleep(historyReplayWaitMs);

            log.info("Retrieved [{}] messages from room [{}]",
                    foundMessages.size(), roomName);

            future.complete(foundMessages);

        } catch (SmackException.NotConnectedException e) {
            future.completeExceptionally(
                    new IllegalArgumentException("Not connected to room: " + roomName, e));
        } catch (SmackException.NoResponseException e) {
            future.completeExceptionally(
                    new IllegalArgumentException("No response from server: " + roomName, e));
        } catch (XMPPException.XMPPErrorException e) {
            future.completeExceptionally(
                    new IllegalArgumentException("XMPP error for room: " + roomName, e));
        } catch (MultiUserChatException.NotAMucServiceException e) {
            future.completeExceptionally(
                    new IllegalArgumentException("Not a MUC service: " + roomName, e));
        } catch (MultiUserChatException.MucNotJoinedException e) {
            future.completeExceptionally(
                    new IllegalArgumentException("MUC not joined: " + roomName, e));
        } catch (XmppStringprepException e) {
            future.completeExceptionally(
                    new IllegalArgumentException("Invalid participant: " + participantName, e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.completeExceptionally(
                    new IllegalArgumentException("Interrupted fetching messages", e));
        } finally {
            muc.removeMessageListener(tempListener);
            rejoinWithoutHistory(muc, participantName, roomName);
        }
    });

    return future;
}


// In ChatService
private final long historyReplayWaitMs;

public ChatService(ChatGatewayProperties properties, ...) {
    this.historyReplayWaitMs = properties.getHistoryReplayWaitMs();
}

chat-gateway:
history-replay-wait-ms: 2000   # ← tunable per environment
