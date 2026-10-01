package com.babcock.inspire.openfire.service;

// Template for the room-membership/lifecycle tests that used to live in
// ChatServiceTest (shouldSendMessage's room-joined check, shouldReturnFromCache,
// etc.) - that logic now lives in RoomService, so it gets tested against
// RoomService directly rather than through the ChatService facade.
//
// shouldReturnFromCache from your screenshot becomes
// joinParticipantToTheRoom_returnsCachedMucWithoutRejoining below. It reached
// into service.getRoomService().getJoinedRooms() directly - that only works if
// RoomService exposes joinedRooms via a getter. The draft I sent keeps the map
// private (clearAllRoomTracking() is the only hook ResetAllService gets), so
// this version seeds the cache through the public API instead
// (joinParticipantToTheRoom once, then asserting the second call doesn't
// rejoin) rather than reaching into internals. If you've since added a
// getJoinedRooms() getter on your end, either approach works - having the
// getter is a bit more convenient for tests but widens what's public.

import com.babcock.inspire.openfire.services.*;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smackx.muc.MultiUserChat;
import org.jivesoftware.smackx.muc.MultiUserChatManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoomServiceTest {

    @Mock private ChatGatewayProperties chatGatewayProperties;
    @Mock private ChatManagerProvider chatManagerProvider;
    @Mock private XmppConnectionManager xmppConnectionManager;
    @Mock private EventListenerService eventListenerService;
    @Mock private NotificationsService notificationsService;

    @Mock private MultiUserChatManager manager;
    @Mock private MultiUserChat muc;
    @Mock private XMPPTCPConnection participantConnection;

    @InjectMocks
    private RoomService roomService;

    @Test
    void checkParticipantJoinedTheRoom_throwsAndNotifies_whenNotJoined() {
        assertThatThrownBy(() -> roomService.checkParticipantJoinedTheRoom("TestRoom", "participant1"))
                .isInstanceOf(IllegalArgumentException.class);

        verify(notificationsService).createNotification(
                eq("Unauthorised"), contains("participant1"), any(), any());
    }

    @Test
    void joinParticipantToTheRoom_joinsAndCaches_onFirstCall() throws Exception {
        when(chatGatewayProperties.getParticipantPassword()).thenReturn("pw");
        when(chatGatewayProperties.getServiceName()).thenReturn("conference");
        when(chatGatewayProperties.getDomain()).thenReturn("domain");
        when(xmppConnectionManager.getParticipantConnection("participant1", "pw"))
                .thenReturn(participantConnection);
        when(chatManagerProvider.getManager(participantConnection)).thenReturn(manager);
        when(manager.getMultiUserChat(any())).thenReturn(muc);
        when(muc.getEnterConfigurationBuilder(any()))
                .thenReturn(mock(org.jivesoftware.smackx.muc.MucEnterConfiguration.Builder.class, withSettings().defaultAnswer(invocation -> {
                    // builder methods return itself except build(), which must return a real config
                    if (invocation.getMethod().getName().equals("build")) {
                        return mock(org.jivesoftware.smackx.muc.MucEnterConfiguration.class);
                    }
                    return invocation.getMock();
                })));

        MultiUserChat result = roomService.joinParticipantToTheRoom("TestRoom", "participant1");

        assertThat(result).isSameAs(muc);
        verify(muc).join(any(org.jivesoftware.smackx.muc.MucEnterConfiguration.class));
        verify(eventListenerService).registerUserStatusListeners(eq("TestRoom"), eq("participant1"), eq(muc), any());

        // second call must hit the cache, not rejoin
        MultiUserChat second = roomService.joinParticipantToTheRoom("TestRoom", "participant1");
        assertThat(second).isSameAs(muc);
        verify(muc, times(1)).join(any(org.jivesoftware.smackx.muc.MucEnterConfiguration.class)); // still 1, not 2
    }

    @Test
    void removeParticipantFromTheRoom_returnsNull_whenNotTracked() {
        MultiUserChat result = roomService.removeParticipantFromTheRoom("TestRoom", "participant1");

        assertThat(result).isNull();
        verifyNoInteractions(eventListenerService);
    }

    @Test
    void clearAllRoomTracking_clearsCacheSoSubsequentChecksFail() throws Exception {
        // seed the cache via the real join path (see joinParticipantToTheRoom test above
        // for the mocking boilerplate) - omitted here for brevity, call
        // roomService.joinParticipantToTheRoom(...) first in your real version.

        roomService.clearAllRoomTracking();

        assertThatThrownBy(() -> roomService.checkParticipantJoinedTheRoom("TestRoom", "participant1"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
