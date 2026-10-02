// REVISED PATCH for RoomService.java - replaces the previous
// RoomService-gajim-join-patch.java. The previous version stored the
// room-watcher's own (admin-bound) MultiUserChat as if it were a usable,
// sendable connection for the externally-joined participant. It is not -
// it's bound to the system/admin connection, confirmed in your debugger
// screenshot (admin@openfire-xmpp-dev1/...). If sendMessageToRoom(...) were
// ever called for a participant who only joined via Gajim, this would have
// silently sent their message under the admin identity instead of theirs.
//
// Fix: track externally-observed joins SEPARATELY from joinedRooms.
// joinedRooms stays exactly as it was - only ever populated by
// joinParticipantToTheRoom(...) with a real, app-owned connection, and that's
// the only thing checkParticipantJoinedTheRoom(...)/sendMessageToRoom(...)
// are allowed to use. The new externallyJoinedParticipants set is for
// presence/membership-listing purposes only (e.g. "is participant1 in the
// room right now", a participant-count, a "who's here" API) - NOT for
// sending.
//
// If you later need your app to actually send AS a Gajim-only participant,
// that needs a real decision: either reject it with a clear error
// ("participant joined externally, not through this app - cannot send on
// their behalf"), or have the app also join under a different nickname
// (which then shows as a second, distinct occupant in the room) - both are
// legitimate choices, but silently sending under the admin identity is not.
// This patch takes the "reject with a clear error" option, since it's the
// one that can't surprise you later; swap it for the other if you decide you
// actually want app-side sending for externally-joined participants.
//
// ------------------------------------------------------------------------

import org.jxmpp.jid.EntityFullJid; // add to imports

// ADD this new field alongside joinedRooms/participantRooms:

/**
 * roomKey ("roomName:participantName") entries for occupants observed joining
 * directly through another client (e.g. Gajim), not through
 * joinParticipantToTheRoom(...). Tracked for presence/membership-listing
 * purposes only - deliberately NOT usable for sending, since there is no
 * real app-owned connection behind these entries (see class-level note).
 */
private final Set<String> externallyJoinedParticipants = ConcurrentHashMap.newKeySet();

// REPLACE registerListenerService(...) - unchanged from the previous patch,
// still wires the two callbacks:

public void registerListenerService(String roomName, MultiUserChat muc) {
    eventListenerService.registerRoomListeners(
            roomName,
            muc,
            participant -> trackExternalParticipantJoin(roomName, participant),
            participant -> untrackExternalParticipantLeave(roomName, participant));
}

// REPLACE trackExternalParticipantJoin(...) - no longer takes/stores a MUC at all:

/**
 * Invoked by the room-wide watcher's ParticipantStatusListener.joined(...) for
 * EVERY occupant join, including our own app's joins (the watcher sees all
 * presence in the room regardless of who initiated it). Guards against
 * double-tracking our own joins by checking joinedRooms first - those are
 * already tracked there by joinParticipantToTheRoom(...), so this only adds
 * an entry for a join we didn't initiate ourselves.
 * <p>
 * Deliberately does NOT store a MultiUserChat - there is no real, app-owned
 * connection for an externally-joined participant (see class-level note on
 * why the previous version storing the watcher's own admin-bound MUC here
 * was wrong). This is presence tracking only.
 */
private void trackExternalParticipantJoin(String roomName, EntityFullJid participant) {
    String participantName = participant.getResourceOrThrow().toString();
    String roomKey = roomName + ":" + participantName;

    if (joinedRooms.containsKey(roomKey)) {
        return; // our own join, already tracked with a real connection
    }

    externallyJoinedParticipants.add(roomKey);
    participantRooms.computeIfAbsent(participantName, k -> ConcurrentHashMap.newKeySet()).add(roomName);
    log.info(
            "Tracked externally-joined participant [{}] in room [{}] (joined via another client, presence only)",
            participantName,
            roomName);
}

// REPLACE untrackExternalParticipantLeave(...):

/**
 * Mirrors trackExternalParticipantJoin(...) on the way out. If
 * removeParticipantFromTheRoom(...) already removed the real joinedRooms
 * entry (because WE initiated the removal), this only cleans up the
 * externally-tracked entry if there is one - safe no-op otherwise.
 */
private void untrackExternalParticipantLeave(String roomName, EntityFullJid participant) {
    String participantName = participant.getResourceOrThrow().toString();
    String roomKey = roomName + ":" + participantName;

    boolean wasExternallyTracked = externallyJoinedParticipants.remove(roomKey);
    if (wasExternallyTracked) {
        Optional.ofNullable(participantRooms.get(participantName))
                .ifPresent(rooms -> rooms.remove(roomName));
        log.info(
                "Untracked externally-joined participant [{}] leaving room [{}]",
                participantName,
                roomName);
    }
}

// NOT adding a isParticipantPresentInRoom()-style method this time - there's
// no real caller for it yet, and an unused public method is just dead code
// to maintain. If you add a "who's in the room"/participant-listing endpoint
// later, that's the moment to add a method reading
// joinedRooms.containsKey(roomKey) || externallyJoinedParticipants.contains(roomKey)
// - trivial to bring back then, with a real call site to verify it against.

// UPDATE clearAllRoomTracking() (called from ResetAllService) to also clear
// the new set:

public void clearAllRoomTracking() {
    joinedRooms.clear();
    participantRooms.clear();
    externallyJoinedParticipants.clear();
}
