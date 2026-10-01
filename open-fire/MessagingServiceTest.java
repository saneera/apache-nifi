package com.babcock.inspire.openfire.service;

// Template for the messaging-behaviour tests that used to live in
// ChatServiceTest (your shouldSendMessage test). The actual send-message logic
// now lives in MessagingService, so it's tested here directly against a mocked
// RoomService rather than through the ChatService facade - this is the direct
// translation of your shouldSendMessage test onto the new class.

import com.babcock.inspire.openfire.services.*;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.StanzaBuilder;
import org.jivesoftware.smackx.muc.MultiUserChat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessagingServiceTest {

    @Mock private RoomService roomService;
    @Mock private MessageListenerService messageListenerService;
    @Mock private MucRestApiService mucRestApiService;

    @Mock private MultiUserChat muc;

    @InjectMocks
    private MessagingService messagingService;

    @Test
    void sendMessageToRoom_sendsThroughRoomServiceMuc() throws Exception {
        when(roomService.checkParticipantJoinedTheRoom("TestRoom", "participant1")).thenReturn(muc);

        MessageBuilder builder = StanzaBuilder.buildMessage();
        when(muc.buildMessage()).thenReturn(builder);

        messagingService.sendMessageToRoom("TestRoom", "participant1", "hello");

        verify(roomService).checkParticipantJoinedTheRoom("TestRoom", "participant1");
        verify(muc).sendMessage(any(Message.class));
    }

    @Test
    void sendMessageToRoom_propagatesNotJoinedFailure() {
        when(roomService.checkParticipantJoinedTheRoom("TestRoom", "participant1"))
                .thenThrow(new IllegalArgumentException("participant1 not joined to the room TestRoom"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        messagingService.sendMessageToRoom("TestRoom", "participant1", "hello"))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(muc);
    }

    @Test
    void deleteMessageFromRoom_validatesMembershipThenDeletes() {
        when(roomService.checkParticipantJoinedTheRoom("TestRoom", "participant1")).thenReturn(muc);
        DeleteMessageResponse deleteResponse = new DeleteMessageResponse();
        deleteResponse.setMessageId("msg-1");
        when(mucRestApiService.deleteMessageByRoomNameAndMessageId("TestRoom", "msg-1"))
                .thenReturn(deleteResponse);

        messagingService.deleteMessageFromRoom("TestRoom", "participant1", "msg-1");

        verify(messageListenerService).processRoomMessageRetraction("TestRoom", "msg-1");
    }

    @Test
    void readMessageByParticipantAndRoom_filtersByMessageId() {
        MessageRow matching = mock(MessageRow.class);
        when(matching.getMessageId()).thenReturn("abc-123");
        when(matching.getBody()).thenReturn("hi");
        when(matching.getSender()).thenReturn("participant1");
        when(matching.getSentDate()).thenReturn(0L);

        MessageRow nonMatching = mock(MessageRow.class);
        when(nonMatching.getMessageId()).thenReturn("zzz-999");

        ListMessageResponse serverResponse = new ListMessageResponse();
        serverResponse.setMessages(java.util.List.of(matching, nonMatching));
        when(mucRestApiService.readMessagesOfRoom("TestRoom")).thenReturn(serverResponse);

        var result = messagingService.readMessageByParticipantAndRoom("TestRoom", java.util.List.of("abc"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getMessageId()).isEqualTo("abc-123");
    }
}
