package com.babcock.inspire.openfire.service;

// Template showing the delegation-style rewrite for ChatServiceTest after the
// RoomService/MessagingService/ParticipantAccountService/ResetAllService split.
// Every test here just proves ChatService forwards to the right collaborator
// with the right arguments and returns what the collaborator returned - it no
// longer re-tests room/messaging/account/reset-all BEHAVIOUR, because that
// behaviour now lives in those collaborator classes (see RoomServiceTest and
// MessagingServiceTest for where the real assertions moved to).
//
// Note @InjectMocks now needs a @Mock for every constructor param, including
// the four new services - that's what was missing and caused the NPEs.

import com.babcock.inspire.openfire.services.*;
import org.jivesoftware.smackx.muc.MultiUserChat;
import org.jivesoftware.smackx.muc.MultiUserChatManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock private AssetHandlerStateStoreConfig stateStoreConfig;
    @Mock private AssetHandlerStateStore store;
    @Mock private ChatGatewayProperties chatGatewayProperties;
    @Mock private CommandHandlerFactory commandHandlerFactory;
    @Mock private PropertyHandlerFactory propertyHandlerFactory;
    @Mock private XmppConnectionManager xmppConnectionManager;
    @Mock private PropertyService propertyService;

    @Mock private RoomService roomService;
    @Mock private MessagingService messagingService;
    @Mock private ParticipantAccountService participantAccountService;
    @Mock private ResetAllService resetAllService;
    @Mock private NotificationsService notificationsService;

    @Mock private MultiUserChat muc;
    @Mock private MultiUserChatManager manager;

    @InjectMocks
    private ChatService service;

    @Test
    void sendMessageToRoom_delegatesToMessagingService() {
        when(messagingService.sendMessageToRoom("TestRoom", "participant1", "hello"))
                .thenReturn("stanza-123");

        String stanzaId = service.sendMessageToRoom("TestRoom", "participant1", "hello");

        assertThat(stanzaId).isEqualTo("stanza-123");
        verify(messagingService).sendMessageToRoom("TestRoom", "participant1", "hello");
        verifyNoInteractions(roomService); // sendMessageToRoom itself never touches RoomService directly
    }

    @Test
    void deleteMessageFromRoom_delegatesToMessagingService() {
        service.deleteMessageFromRoom("TestRoom", "participant1", "msg-1");

        verify(messagingService).deleteMessageFromRoom("TestRoom", "participant1", "msg-1");
    }

    @Test
    void readMessageByParticipantAndRoom_delegatesToMessagingService() {
        List<MessageResponse> expected = List.of(MessageResponse.builder().messageId("m1").build());
        when(messagingService.readMessageByParticipantAndRoom("TestRoom", List.of()))
                .thenReturn(expected);

        List<MessageResponse> result = service.readMessageByParticipantAndRoom("TestRoom", List.of());

        assertThat(result).isEqualTo(expected);
    }

    @Test
    void joinParticipantToTheRoom_delegatesToRoomService() {
        when(roomService.joinParticipantToTheRoom("TestRoom", "participant1")).thenReturn(muc);

        MultiUserChat result = service.joinParticipantToTheRoom("TestRoom", "participant1");

        assertThat(result).isSameAs(muc);
        verify(roomService).joinParticipantToTheRoom("TestRoom", "participant1");
    }

    @Test
    void removeParticipantFromTheRoom_delegatesToRoomService() {
        service.removeParticipantFromTheRoom("TestRoom", "participant1");

        verify(roomService).removeParticipantFromTheRoom("TestRoom", "participant1");
    }

    @Test
    void checkParticipantJoinedTheRoom_delegatesToRoomService() {
        when(roomService.checkParticipantJoinedTheRoom("TestRoom", "participant1")).thenReturn(muc);

        MultiUserChat result = service.checkParticipantJoinedTheRoom("TestRoom", "participant1");

        assertThat(result).isSameAs(muc);
    }

    @Test
    void getRoomFromServer_delegatesToRoomService() {
        Room room = new Room();
        when(roomService.getRoomFromServer("room@conference.domain")).thenReturn(room);

        Room result = service.getRoomFromServer("room@conference.domain");

        assertThat(result).isSameAs(room);
    }

    @Test
    void listRoomFromServer_delegatesToRoomService() {
        Rooms rooms = new Rooms();
        when(roomService.listRoomFromServer()).thenReturn(rooms);

        assertThat(service.listRoomFromServer()).isSameAs(rooms);
    }

    @Test
    void createAndGetRoom_delegatesToRoomService() {
        RoomInfo info = mock(RoomInfo.class);
        when(roomService.createAndGetRoom("room@conference.domain", manager, "TestRoom", "desc", chatGatewayProperties))
                .thenReturn(info);

        RoomInfo result = service.createAndGetRoom(
                "room@conference.domain", manager, "TestRoom", "desc", chatGatewayProperties);

        assertThat(result).isSameAs(info);
    }

    @Test
    void createAccount_delegatesToParticipantAccountService() {
        service.createAccount("participant1");

        verify(participantAccountService).createAccount("participant1");
    }

    @Test
    void deleteAccount_delegatesToParticipantAccountService() {
        service.deleteAccount("participant1");

        verify(participantAccountService).deleteAccount("participant1");
    }

    @Test
    void resetAll_delegatesToResetAllService() {
        ResetAllResponse expected = new ResetAllResponse();
        when(resetAllService.resetAll()).thenReturn(expected);

        ResetAllResponse result = service.resetAll();

        assertThat(result).isSameAs(expected);
    }

    @Test
    void getManager_delegatesToRoomService() {
        when(roomService.getManager()).thenReturn(manager);

        assertThat(service.getManager()).isSameAs(manager);
    }

    @Test
    void getChatGatewayProperties_returnsInjectedProperties() {
        assertThat(service.getChatGatewayProperties()).isSameAs(chatGatewayProperties);
    }

    @Test
    void createAndSentNotification_delegatesToNotificationsService() {
        service.createAndSentNotification("title", "content");

        verify(notificationsService).createNotification(
                eq("title"), eq("content"), any(), any());
    }
}
