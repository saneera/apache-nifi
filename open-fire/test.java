package com.babcock.inspire.openfire.services;

import com.babcock.inspire.openfire.constants.PropertyConstants;
import com.babcock.inspire.openfire.events.ChatMessageEvent;
import com.babcock.inspire.openfire.models.RoomMessageCount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatMessageEventListenerTest {

    @Mock
    private PropertyService propertyService;

    @InjectMocks
    private ChatMessageEventListener listener;

    private static final String PROP_NAME = "roomsMessageCount";

    private void stubExistingCounts(List<RoomMessageCount> existing) {
        when(propertyService.getPropertyValue(PropertyConstants.ROOMS_MESSAGE_COUNT))
                .thenReturn(Map.of(PROP_NAME, existing));
        when(propertyService.getPropName(PropertyConstants.ROOMS_MESSAGE_COUNT))
                .thenReturn(PROP_NAME);
    }

    @SuppressWarnings("unchecked")
    private List<RoomMessageCount> captureSentRooms() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(propertyService).sendObjectToPropService(eq(PropertyConstants.ROOMS_MESSAGE_COUNT), captor.capture());
        return (List<RoomMessageCount>) captor.getValue();
    }

    @Test
    void newMessage_inNewRoom_createsEntryAndIncrementsCount() {
        stubExistingCounts(new ArrayList<>());

        ChatMessageEvent event = ChatMessageEvent.builder()
                .roomName("Room1")
                .messageId("msg-1")
                .sender("alice@domain")
                .message("hello")
                .delayed(false)
                .retracted(false)
                .build();

        listener.handleIncomingMessage(event);

        List<RoomMessageCount> sent = captureSentRooms();
        assertThat(sent).hasSize(1);
        RoomMessageCount room1 = sent.get(0);
        assertThat(room1.getRoomName()).isEqualTo("Room1");
        assertThat(room1.getMessageCount()).isEqualTo(1);
        assertThat(room1.getMessageIds()).containsExactly("msg-1");
    }

    @Test
    void newMessage_inExistingRoom_incrementsWithoutTouchingOtherRooms() {
        RoomMessageCount room1 = RoomMessageCount.builder()
                .roomName("Room1").messageCount(3)
                .messageIds(new ArrayList<>(List.of("msg-1", "msg-2", "msg-3")))
                .build();
        RoomMessageCount room2 = RoomMessageCount.builder()
                .roomName("Room2").messageCount(5)
                .messageIds(new ArrayList<>(List.of("other-1")))
                .build();
        stubExistingCounts(new ArrayList<>(List.of(room1, room2)));

        ChatMessageEvent event = ChatMessageEvent.builder()
                .roomName("Room1").messageId("msg-4")
                .delayed(false).retracted(false)
                .build();

        listener.handleIncomingMessage(event);

        List<RoomMessageCount> sent = captureSentRooms();
        RoomMessageCount updatedRoom1 = sent.stream().filter(r -> r.getRoomName().equals("Room1")).findFirst().orElseThrow();
        RoomMessageCount untouchedRoom2 = sent.stream().filter(r -> r.getRoomName().equals("Room2")).findFirst().orElseThrow();

        assertThat(updatedRoom1.getMessageCount()).isEqualTo(4);
        assertThat(updatedRoom1.getMessageIds()).contains("msg-4");
        assertThat(untouchedRoom2.getMessageCount()).isEqualTo(5); // unchanged
    }

    @Test
    void delayedMessage_isSkippedEntirely_noPropertyServiceCallAtAll() {
        ChatMessageEvent event = ChatMessageEvent.builder()
                .roomName("Room1").messageId("history-1")
                .delayed(true).retracted(false)
                .build();

        listener.handleIncomingMessage(event);

        verifyNoInteractions(propertyService);
    }

    @Test
    void retractedMessage_decrementsCountAndRemovesId() {
        RoomMessageCount room1 = RoomMessageCount.builder()
                .roomName("Room1").messageCount(2)
                .messageIds(new ArrayList<>(List.of("msg-1", "msg-2")))
                .build();
        stubExistingCounts(new ArrayList<>(List.of(room1)));

        ChatMessageEvent event = ChatMessageEvent.builder()
                .roomName("Room1").messageId("msg-1")
                .delayed(false).retracted(true)
                .build();

        listener.handleIncomingMessage(event);

        List<RoomMessageCount> sent = captureSentRooms();
        RoomMessageCount updated = sent.get(0);
        assertThat(updated.getMessageCount()).isEqualTo(1);
        assertThat(updated.getMessageIds()).containsExactly("msg-2");
    }

    @Test
    void retractedMessage_countNeverGoesBelowZero() {
        RoomMessageCount room1 = RoomMessageCount.builder()
                .roomName("Room1").messageCount(0)
                .messageIds(new ArrayList<>())
                .build();
        stubExistingCounts(new ArrayList<>(List.of(room1)));

        ChatMessageEvent event = ChatMessageEvent.builder()
                .roomName("Room1").messageId("phantom-msg")
                .delayed(false).retracted(true)
                .build();

        listener.handleIncomingMessage(event);

        List<RoomMessageCount> sent = captureSentRooms();
        assertThat(sent.get(0).getMessageCount()).isZero();
    }

    @Test
    void duplicateMessageId_doesNotIncrementCountAgain() {
        RoomMessageCount room1 = RoomMessageCount.builder()
                .roomName("Room1").messageCount(1)
                .messageIds(new ArrayList<>(List.of("msg-1")))
                .build();
        stubExistingCounts(new ArrayList<>(List.of(room1)));

        ChatMessageEvent event = ChatMessageEvent.builder()
                .roomName("Room1").messageId("msg-1") // redelivered, e.g. after reconnect
                .delayed(false).retracted(false)
                .build();

        listener.handleIncomingMessage(event);

        List<RoomMessageCount> sent = captureSentRooms();
        assertThat(sent.get(0).getMessageCount()).isEqualTo(1); // not incremented twice
        assertThat(sent.get(0).getMessageIds()).containsExactly("msg-1"); // not duplicated in the list

        // NOTE: sendObjectToPropService is still called here even though nothing
        // changed - this test documents current behavior, not necessarily desired
        // behavior. If you fix that redundant call, flip this assertion to
        // verifyNoInteractions(propertyService) or a never() on sendObjectToPropService.
        verify(propertyService, times(1)).sendObjectToPropService(anyString(), any());
    }

    @Test
    void exceptionFromPropertyService_isCaughtAndLogged_doesNotPropagate() {
        when(propertyService.getPropertyValue(PropertyConstants.ROOMS_MESSAGE_COUNT))
                .thenThrow(new RuntimeException("simulated downstream failure"));

        ChatMessageEvent event = ChatMessageEvent.builder()
                .roomName("Room1").messageId("msg-1")
                .delayed(false).retracted(false)
                .build();

        assertThatCode(() -> listener.handleIncomingMessage(event)).doesNotThrowAnyException();
        verify(propertyService, never()).sendObjectToPropService(anyString(), any());
    }

    @Test
    void lockIsAlwaysReleased_evenWhenProcessingThrows() {
        when(propertyService.getPropertyValue(PropertyConstants.ROOMS_MESSAGE_COUNT))
                .thenThrow(new RuntimeException("boom"));

        ChatMessageEvent event = ChatMessageEvent.builder()
                .roomName("Room1").messageId("msg-1")
                .delayed(false).retracted(false)
                .build();

        // Calling twice would deadlock on roomMessageLock.lock() if the first
        // call's finally block failed to unlock - this indirectly proves the lock
        // is released even on the exception path.
        assertThatCode(() -> {
            listener.handleIncomingMessage(event);
            listener.handleIncomingMessage(event);
        }).doesNotThrowAnyException();
    }
}
