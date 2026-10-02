package com.babcock.inspire.openfire.services;

// Transcribed from your screenshots, then extended so that an external join
// (Gajim or any other client joining the room directly, not through
// joinParticipantToTheRoom) gets reflected into RoomService's tracking.
//
// registerRoomListeners(...) now takes two extra callbacks - onParticipantJoined
// and onParticipantLeft - instead of EventListenerService holding a reference
// back to RoomService. RoomService already depends on EventListenerService
// (registerRoomListeners/registerUserStatusListeners), so the reverse
// reference would be a circular bean dependency, same shape as the
// ResetAllService/ChatService cycle you just fixed. Passing plain
// Consumer<EntityFullJid> lambdas keeps this a one-directional dependency and
// matches the "simple direct callback over an event bus" call you made for
// registerUserStatusListeners's onRemoved.
//
// kicked/banned/left on the room-wide ParticipantStatusListener all mean "no
// longer an occupant", so all three route into onParticipantLeft - only
// joined() routes into onParticipantJoined. nicknameChanged is NOT handled
// here: if a participant renames mid-session, the roomKey (built from their
// OLD nickname) is now stale and nothing currently re-keys it. Flagging that
// as a known gap rather than silently fixing it, since I don't know whether
// your app currently allows in-room nickname changes at all.

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jivesoftware.smack.MessageListener;
import org.jivesoftware.smack.PresenceListener;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Presence;
import org.jivesoftware.smack.packet.StanzaBuilder;
import org.jivesoftware.smack.util.StringUtils;
import org.jivesoftware.smackx.delay.packet.DelayInformation;
import org.jivesoftware.smackx.muc.MultiUserChat;
import org.jivesoftware.smackx.muc.ParticipantStatusListener;
import org.jivesoftware.smackx.muc.UserStatusListener;
import org.jxmpp.jid.EntityFullJid;
import org.jxmpp.jid.Jid;
import org.jxmpp.jid.parts.Resourcepart;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Service
@Slf4j
@RequiredArgsConstructor
public class EventListenerService {

    private final MessageListenerService messageListenerService;

    private final Map<String, ListenersModel> listeners = new ConcurrentHashMap<>();
    private final Map<String, UserStatusListener> participantListeners = new ConcurrentHashMap<>();

    /**
     * Register listeners for rooms
     *
     * @param roomName
     * @param muc
     * @param onParticipantJoined called with the full JID of ANY occupant who joins
     *                             the room, including one that joined directly through
     *                             another client (e.g. Gajim) rather than through this
     *                             app's own join flow. The callback is responsible for
     *                             figuring out whether that join is already tracked.
     * @param onParticipantLeft   called when an occupant leaves, is kicked, or is banned.
     */
    public void registerRoomListeners(
            String roomName,
            MultiUserChat muc,
            Consumer<EntityFullJid> onParticipantJoined,
            Consumer<EntityFullJid> onParticipantLeft) {

        String roomKey = roomName;
        if (listeners.containsKey(roomKey)) {
            log.info("Listeners already registered [{}]", roomKey);
            return;
        }

        MessageListener messageListener = getMessageListener(roomName);
        ParticipantStatusListener participantListener =
                getParticipantStatusListener(roomName, onParticipantJoined, onParticipantLeft);
        PresenceListener presenceListener = getPresenceListener(roomName);

        ListenersModel listenersModel = new ListenersModel(messageListener, participantListener, presenceListener);

        muc.addMessageListener(messageListener);
        muc.addParticipantStatusListener(participantListener);
        muc.addParticipantListener(presenceListener);
        log.info("Successfully registered listeners [{}]", roomKey);
        listeners.put(roomKey, listenersModel);
    }

    /**
     * Register for listener for users (participants)
     *
     * @param roomName
     * @param participantName
     * @param participantMuc
     */
    public void registerUserStatusListeners(
            String roomName, String participantName, MultiUserChat participantMuc, Runnable onRemoved) {

        String participantKey = roomName + ":" + participantName;
        if (participantListeners.containsKey(participantKey)) {
            log.info("Participant Listeners already registered [{}]", participantKey);
            return;
        }

        log.info("Registering listeners for participant [{}] in room [{}]", participantName, roomName);

        UserStatusListener listener = new UserStatusListener() {
            @Override
            public void kicked(Jid actor, String reason) {
                log.info("CURRENT USER KICKED room=[{}] actor=[{}] reason=[{}]", roomName, actor, reason);
                onRemoved.run();
            }

            @Override
            public void banned(Jid actor, String reason) {
                log.info("CURRENT USER BANNED room=[{}] actor=[{}] reason=[{}]", roomName, actor, reason);
                onRemoved.run();
            }

            @Override
            public void roomDestroyed(MultiUserChat alternateMUC, String reason) {
                log.info("ROOM DESTROYED room=[{}] reason=[{}]", roomName, reason);
                onRemoved.run();
            }
        };

        participantMuc.addUserStatusListener(listener);
        participantListeners.put(participantKey, listener);
        log.info("Successfully status listeners for users [{}] in room [{}]", participantName, roomName);
    }

    private MessageListener getMessageListener(String roomName) {
        return message -> {
            try {
                /**
                 * Delayed messages are historical messages that Openfire replays
                 * automatically when a participant joins a room. The following code
                 * filters them out because we only want to process real-time messages.
                 * */
                DelayInformation delay = DelayInformation.from(message);

                if (delay != null) {
                    log.info("Ignoring delayed message room=[{}]", roomName);
                    return;
                }

                log.info("ROOM MESSAGE room=[{}] from=[{}] body=[{}]", roomName, message.getFrom(), message.getBody());
                messageListenerService.processRoomMessage(roomName, message);
            } catch (Exception e) {
                log.error("Error processing room message", e);
            }
        };
    }

    private static PresenceListener getPresenceListener(String roomName) {
        return new PresenceListener() {
            @Override
            public void processPresence(Presence presence) {
                log.info("ROOM Presence room=[{}] reason=[{}]", roomName, presence.getStatus());
            }
        };
    }

    private ParticipantStatusListener getParticipantStatusListener(
            String roomName, Consumer<EntityFullJid> onParticipantJoined, Consumer<EntityFullJid> onParticipantLeft) {

        return new ParticipantStatusListener() {
            @Override
            public void joined(EntityFullJid participant) {
                log.info("PARTICIPANT JOINED room=[{}] participant=[{}]", roomName, participant);
                try {
                    onParticipantJoined.accept(participant);
                } catch (Exception e) {
                    // never let a tracking failure take down the room-wide listener -
                    // a missed tracking update is recoverable, a dead listener isn't.
                    log.error("Error tracking externally-joined participant room=[{}] participant=[{}]",
                            roomName, participant, e);
                }
            }

            @Override
            public void left(EntityFullJid participant) {
                log.info("PARTICIPANT LEFT room=[{}] participant=[{}]", roomName, participant);
                safelyUntrack(roomName, participant, onParticipantLeft);
            }

            @Override
            public void kicked(EntityFullJid participant, Jid actor, String reason) {
                log.info(
                        "PARTICIPANT KICKED room=[{}] participant=[{}] actor=[{}] reason=[{}]",
                        roomName,
                        participant,
                        actor,
                        reason);
                safelyUntrack(roomName, participant, onParticipantLeft);
            }

            @Override
            public void banned(EntityFullJid participant, Jid actor, String reason) {
                log.info("PARTICIPANT BANNED room=[{}] participant=[{}]", roomName, participant);
                safelyUntrack(roomName, participant, onParticipantLeft);
            }

            @Override
            public void nicknameChanged(EntityFullJid participant, Resourcepart newNickname) {
                log.info(
                        "NICKNAME CHANGED room=[{}] participant=[{}] newNick=[{}]",
                        roomName,
                        participant,
                        newNickname);
                // NOT handled: the room/participant tracking key is built from the
                // nickname at join time, so a rename currently leaves a stale entry
                // behind rather than being re-keyed. See class-level note.
            }
        };
    }

    private void safelyUntrack(String roomName, EntityFullJid participant, Consumer<EntityFullJid> onParticipantLeft) {
        try {
            onParticipantLeft.accept(participant);
        } catch (Exception e) {
            log.error("Error untracking participant room=[{}] participant=[{}]", roomName, participant, e);
        }
    }

    public void removeRoomListeners(String roomName, MultiUserChat muc) {
        String roomKey = roomName;
        ListenersModel listenersModel = listeners.remove(roomKey);

        if (listenersModel == null) {
            log.warn("No listeners found for room [{}], skipping removal", roomKey);
            return;
        }

        if (listenersModel.getMessageListener() != null) {
            muc.removeMessageListener(listenersModel.getMessageListener());
            log.info("Removed message listener for [{}]", roomName);
        }

        if (listenersModel.getParticipantStatusListener() != null) {
            muc.removeParticipantStatusListener(listenersModel.getParticipantStatusListener());
            log.info("Removed participant status listener for [{}]", roomName);
        }

        if (listenersModel.getPresenceEventListener() != null) {
            muc.removeParticipantListener(listenersModel.getPresenceEventListener());
            log.info("Removed participant presence status listener for [{}]", roomName);
        }

        log.info("Successfully removed listeners for [{}]", roomName);
    }
}
