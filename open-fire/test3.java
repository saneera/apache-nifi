// PATCH v4 for RoomService.java - REPLACES the track/untrack methods from the v2 patch
// (the screenshot shows v1 with the extra `muc` param - drop that too).
//
// Goal: when a participant joins the room from Gajim, the server (this app) must get
// a REAL connection for that participant, so sendMessageToRoom(...) works for them.
//
// How: on an external join we "adopt" it - run the normal joinParticipantToTheRoom(...)
// for that participant. That logs in as the participant (xmppConnectionManager,
// shared participant password), joins with nickname = username, and puts the muc into
// joinedRooms. From then on checkParticipantJoinedTheRoom / sendMessageToRoom treat the
// participant exactly like one added through the app, and messages go out as THEM
// (not as admin - the watcher muc is never used for sending).
//
// Imports to add: java.util.concurrent.ExecutorService, java.util.concurrent.Executors,
//                 jakarta.annotation.PreDestroy (javax.annotation on older Spring Boot)

// ---------------------------------------------------------------- new fields
// (externallyJoinedParticipants from v2 stays: it now means "seen in room, adoption
//  pending or failed" - presence only)

/** roomKeys where adoption failed (e.g. Gajim account has a different password). Not retried until they leave. */
private final Set<String> adoptionFailed = ConcurrentHashMap.newKeySet();

/** one lock object per roomKey so two threads never open two connections for the same participant */
private final Map<String, Object> joinLocks = new ConcurrentHashMap<>();

/**
 * Single thread on purpose: muc.join()/leave() block waiting for the server's reply, and
 * must NOT run on Smack's listener thread (the same thread that delivers that reply).
 * One thread also serialises the adoptions.
 */
private final ExecutorService adoptionExecutor = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "external-join-adopter");
    t.setDaemon(true);
    return t;
});

@PreDestroy
void stopAdoptionExecutor() {
    adoptionExecutor.shutdownNow();
}

// ---------------------------------------------------- 1. EDIT joinParticipantToTheRoom
// Wrap the body so the watcher's "joined" event for our own join can't race an adoption.
// Add these lines right after `String roomKey = roomName + ":" + participantName;`
// and close the synchronized block before the method's catch:
//
//     synchronized (joinLocks.computeIfAbsent(roomKey, k -> new Object())) {
//         if (joinedRooms.containsKey(roomKey)) {
//             return joinedRooms.get(roomKey);
//         }
//         try { ... existing body unchanged ... } catch (Exception e) { ... }
//     }
//
// (the existing containsKey check at the top can stay as the cheap fast path)

// ------------------------------------------------------------- 2. REPLACE track/untrack

/**
 * Called by the room watcher for EVERY occupant join (ours and external).
 * Records presence immediately, then adopts the participant in the background.
 */
private void trackExternalParticipantJoin(String roomName, EntityFullJid participant) {
    String participantName = participant.getResourceOrThrow().toString();
    String roomKey = roomName + ":" + participantName;

    if (joinedRooms.containsKey(roomKey) || adoptionFailed.contains(roomKey)) {
        return; // already a real tracked join, or adoption already known to fail
    }

    externallyJoinedParticipants.add(roomKey);
    participantRooms.computeIfAbsent(participantName, k -> ConcurrentHashMap.newKeySet()).add(roomName);
    log.info("Participant [{}] joined room [{}] from another client, adopting", participantName, roomName);

    adoptionExecutor.submit(() -> adoptExternalParticipant(roomName, participantName, roomKey));
}

private void adoptExternalParticipant(String roomName, String participantName, String roomKey) {
    try {
        // idempotent: returns the existing muc if a real join already happened
        joinParticipantToTheRoom(roomName, participantName);
        externallyJoinedParticipants.remove(roomKey); // now a first-class entry in joinedRooms
        log.info("Adopted [{}] in room [{}]: messages can now be sent as this participant", participantName, roomName);
    } catch (Exception e) {
        adoptionFailed.add(roomKey);
        log.warn("Could not adopt [{}] in room [{}] (still tracked as presence only, cannot send as them): {}",
                participantName, roomName, e.getMessage());
    }
}

/**
 * Called when an occupant is fully gone from the room (left / kicked / banned).
 * Note: while the app's adopted session is connected, a Gajim logout alone does NOT
 * fire this - the occupant is still in the room through our session (see notes).
 */
private void untrackExternalParticipantLeave(String roomName, EntityFullJid participant) {
    String participantName = participant.getResourceOrThrow().toString();
    String roomKey = roomName + ":" + participantName;

    adoptionFailed.remove(roomKey);
    if (externallyJoinedParticipants.remove(roomKey)) {
        Optional.ofNullable(participantRooms.get(participantName)).ifPresent(rooms -> rooms.remove(roomName));
    }

    if (joinedRooms.containsKey(roomKey)) {
        // occupant is gone server-side, so drop our (now stale) connection + tracking
        adoptionExecutor.submit(() -> {
            try {
                removeParticipantFromTheRoom(roomName, participantName);
            } catch (Exception e) {
                log.warn("Cleanup of [{}] in room [{}] failed: {}", participantName, roomName, e.getMessage());
            }
        });
    }
}

// --------------------------------------------- 3. small additions to existing methods
// forgetRoom(...)      add:  adoptionFailed.removeIf(key -> key.startsWith(prefix));
// clearAllRoomTracking() add: adoptionFailed.clear();
