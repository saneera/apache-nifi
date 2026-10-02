// PATCH for RoomService.java - not a full file. Apply these changes:
//
// 1. Change registerListenerService(...) to pass the two new callbacks into
//    EventListenerService.registerRoomListeners(...).
// 2. Add the two new private methods below it.
// 3. Add the EntityFullJid import.
//
// ------------------------------------------------------------------------

import org.jxmpp.jid.EntityFullJid; // add to imports

// REPLACE the existing registerListenerService(...) with:

/**
 * Registers the room-wide watcher listeners for a room, and wires up tracking
 * so that a participant who joins the room directly through another client
 * (e.g. Gajim) rather than through joinParticipantToTheRoom(...) still ends up
 * recorded in joinedRooms/participantRooms.
 */
public void registerListenerService(String roomName, MultiUserChat muc) {
    eventListenerService.registerRoomListeners(
            roomName,
            muc,
            participant -> trackExternalParticipantJoin(roomName, participant, muc),
            participant -> untrackExternalParticipantLeave(roomName, participant));
}

/**
 * Invoked by the room-wide watcher's ParticipantStatusListener.joined(...) for
 * EVERY occupant join - our own app's joins included, since the watcher sees
 * all presence in the room regardless of who initiated the join. Smack/XMPP
 * gives no clean way to tell "our own join, already tracked" apart from
 * "someone else joined directly" at this callback, so this relies on
 * joinedRooms already containing the entry: joinParticipantToTheRoom(...)
 * puts the roomKey into joinedRooms itself right after muc.join(...)
 * succeeds, so by the time the watcher's joined() event is observed for our
 * own join, the entry should already be there and this is a no-op.
 * <p>
 * CAVEAT (flagging rather than silently deciding): the MultiUserChat stored
 * here for an externally-joined participant is the room WATCHER's own
 * connection, not one authenticated as that participant - there's no such
 * connection on our side to store, since we didn't create it. That's enough
 * for checkParticipantJoinedTheRoom(...) to treat them as present (so e.g. a
 * "list participants" or membership check works), but if sendMessageToRoom(...)
 * is ever called for a participant who only joined via Gajim, the message
 * would be sent under the watcher's identity, not theirs - Gajim sends its
 * own messages directly and doesn't go through this app's API, so this is
 * unlikely to come up, but confirm that assumption holds for your use case.
 * <p>
 * Known race: our own join's watcher-side joined() event and
 * joinParticipantToTheRoom(...)'s own joinedRooms.put(...) call are not
 * synchronized against each other, so in theory the watcher's event could be
 * observed a moment before our own put() runs, causing this method to
 * (harmlessly) overwrite the not-yet-written entry with the watcher's muc
 * instead. In practice muc.join(...) completing is what triggers the
 * presence broadcast the watcher reacts to, so our own put() almost always
 * wins, but it's not guaranteed by anything in the API.
 */
private void trackExternalParticipantJoin(String roomName, EntityFullJid participant, MultiUserChat watcherMuc) {
    String participantName = participant.getResourceOrThrow().toString();
    String roomKey = roomName + ":" + participantName;

    if (joinedRooms.containsKey(roomKey)) {
        return; // already tracked - most likely our own join being echoed back
    }

    joinedRooms.put(roomKey, watcherMuc);
    participantRooms.computeIfAbsent(participantName, k -> ConcurrentHashMap.newKeySet()).add(roomName);
    log.info(
            "Tracked externally-joined participant [{}] in room [{}] (joined via another client)",
            participantName,
            roomName);
}

/**
 * Invoked by the room-wide watcher's ParticipantStatusListener on left/kicked/
 * banned, for every occupant - mirrors trackExternalParticipantJoin(...).
 * If removeParticipantFromTheRoom(...) already removed the tracking entry
 * (because WE initiated the removal), this is a safe no-op; otherwise it
 * cleans up tracking for a participant who left through another client, or
 * was kicked/banned by a room admin outside our own removeParticipantFromTheRoom
 * flow.
 */
private void untrackExternalParticipantLeave(String roomName, EntityFullJid participant) {
    String participantName = participant.getResourceOrThrow().toString();
    String roomKey = roomName + ":" + participantName;

    MultiUserChat removed = joinedRooms.remove(roomKey);
    if (removed != null) {
        Optional.ofNullable(participantRooms.get(participantName))
                .ifPresent(rooms -> rooms.remove(roomName));
        log.info(
                "Untracked participant [{}] leaving room [{}] (left via another client, or kicked/banned)",
                participantName,
                roomName);
    }
}
