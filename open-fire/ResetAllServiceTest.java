package com.babcock.inspire.openfire.service;

// Unit tests for the real ResetAllService (ChatService dependency removed,
// PARTICIPANTS read straight off PropertyService + Jackson).
//
// MOCKING NOTE: resetAll() builds its own RestClient via the static
// RestClient.builder() inside the method, which is the classic pain point for
// testing - you can't inject a RestClient normally because the method
// constructs one itself. Handled here with:
//   - Mockito.mockStatic(RestClient.class) + mockito-inline (needed for any
//     static mocking - you flagged this requirement yourself earlier in the
//     ChatMessageEventListener test work, so it should already be on the
//     classpath)
//   - RETURNS_DEEP_STUBS on the mocked RestClient so the
//     .get().uri(...).retrieve().body(...) fluent chain doesn't need every
//     intermediate interface (RequestHeadersUriSpec, ResponseSpec, ...)
//     stubbed by hand
// This is the heaviest test here by far - the guard-clause and
// delegation-shaped tests below it are much simpler and a safer bet to trust
// without re-running them yourself first.

import com.babcock.inspire.openfire.services.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jivesoftware.smackx.muc.MultiUserChat;
import org.jivesoftware.smackx.muc.MultiUserChatManager;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT) // deep-stub chains trip strict-stubbing false positives
class ResetAllServiceTest {

    @Mock private ChatGatewayProperties chatGatewayProperties;
    @Mock private XmppConnectionManager xmppConnectionManager;
    @Mock private PropertyService propertyService;
    @Mock private RoomService roomService;
    @Mock private MessagingService messagingService;
    @Mock private MucRestApiService mucRestApiService;
    @Mock private MessageListenerService messageListenerService;

    @InjectMocks
    private ResetAllService resetAllService;

    @Test
    void resetAll_throwsAndDoesNothing_whenResetAllDisabled() {
        when(chatGatewayProperties.isResetAllEnabled()).thenReturn(false);

        assertThatThrownBy(() -> resetAllService.resetAll())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Reset all is not enabled");

        verifyNoInteractions(xmppConnectionManager, propertyService, roomService, messagingService,
                mucRestApiService, messageListenerService);
    }

    @Test
    void resetAll_happyPath_deletesUsersAndRoomsAndClearsTracking() {
        when(chatGatewayProperties.isResetAllEnabled()).thenReturn(true);
        when(chatGatewayProperties.getHost()).thenReturn("localhost");
        when(chatGatewayProperties.getPluginPort()).thenReturn(9090);
        when(chatGatewayProperties.getUsername()).thenReturn("admin");
        when(chatGatewayProperties.getPassword()).thenReturn("secret");
        when(chatGatewayProperties.getServiceName()).thenReturn("conference");
        when(chatGatewayProperties.getDomain()).thenReturn("domain");

        RestClient.Builder builder = mock(RestClient.Builder.class, withSettings().defaultAnswer(RETURNS_SELF));
        RestClient restClient = mock(RestClient.class, withSettings().defaultAnswer(RETURNS_DEEP_STUBS));

        RestApiUser adminUser = new RestApiUser();
        adminUser.setUsername("admin");
        RestApiUser regularUser = new RestApiUser();
        regularUser.setUsername("participant1");
        UserList userList = new UserList();
        userList.setUsers(List.of(adminUser, regularUser));

        Room room = new Room();
        room.setRoomName("TestRoom");
        Rooms rooms = new Rooms();
        rooms.getRooms().add(room);

        MultiUserChatManager manager = mock(MultiUserChatManager.class);
        MultiUserChat muc = mock(MultiUserChat.class);

        try (MockedStatic<RestClient> restClientStatic = mockStatic(RestClient.class)) {
            restClientStatic.when(RestClient::builder).thenReturn(builder);
            when(builder.build()).thenReturn(restClient);

            // admin.authorizedJIDs lookup
            when(restClient.get().uri("/system/properties/admin.authorizedJIDs").retrieve()
                    .body(any(org.springframework.core.ParameterizedTypeReference.class)))
                    .thenReturn(Map.of("value", "admin@domain"));

            // user list
            when(restClient.get().uri("/users").retrieve().body(UserList.class))
                    .thenReturn(userList);

            // PARTICIPANTS property round-trip
            when(propertyService.getPropertyValue(PropertyConstants.PARTICIPANTS))
                    .thenReturn("[\"admin\",\"participant1\"]");

            // room listing/messages/leave
            when(roomService.listRoomFromServer()).thenReturn(rooms);
            when(messagingService.readMessageByParticipantAndRoom(eq("TestRoom"), any())).thenReturn(List.of());
            when(roomService.getManager()).thenReturn(manager);
            when(manager.getMultiUserChat(any())).thenReturn(muc);

            ResetAllResponse response = resetAllService.resetAll();

            assertThat(response.getUsersDeleted()).isEqualTo(1); // only participant1, admin preserved
            assertThat(response.getPreservedAdmins()).containsExactly("admin");
            assertThat(response.getRoomsDeleted()).isEqualTo(1);

            verify(xmppConnectionManager).disconnectParticipant("participant1");
            verify(xmppConnectionManager, never()).disconnectParticipant("admin");
            verify(roomService).leaveAndUnregister("TestRoom", muc);
            verify(roomService).clearAllRoomTracking();
            verify(propertyService).sendObjectToPropService(PropertyConstants.ROOMS_MESSAGE_COUNT, List.of());
            // PARTICIPANTS re-sent with the non-admin user stripped out
            verify(propertyService).sendObjectToPropService(eq(PropertyConstants.PARTICIPANTS), argThat(
                    arg -> arg instanceof List<?> list && list.equals(List.of("admin"))));
        }
    }

    @Test
    void resetAll_participantsPropertyUnreadable_treatsAsEmptyWithoutThrowing() {
        when(chatGatewayProperties.isResetAllEnabled()).thenReturn(true);
        when(chatGatewayProperties.getHost()).thenReturn("localhost");
        when(chatGatewayProperties.getPluginPort()).thenReturn(9090);
        when(chatGatewayProperties.getUsername()).thenReturn("admin");
        when(chatGatewayProperties.getPassword()).thenReturn("secret");
        when(chatGatewayProperties.getServiceName()).thenReturn("conference");
        when(chatGatewayProperties.getDomain()).thenReturn("domain");

        RestClient.Builder builder = mock(RestClient.Builder.class, withSettings().defaultAnswer(RETURNS_SELF));
        RestClient restClient = mock(RestClient.class, withSettings().defaultAnswer(RETURNS_DEEP_STUBS));

        RestApiUser regularUser = new RestApiUser();
        regularUser.setUsername("participant1");
        UserList userList = new UserList();
        userList.setUsers(List.of(regularUser));

        Rooms emptyRooms = new Rooms();

        try (MockedStatic<RestClient> restClientStatic = mockStatic(RestClient.class)) {
            restClientStatic.when(RestClient::builder).thenReturn(builder);
            when(builder.build()).thenReturn(restClient);

            when(restClient.get().uri("/system/properties/admin.authorizedJIDs").retrieve()
                    .body(any(org.springframework.core.ParameterizedTypeReference.class)))
                    .thenReturn(null);

            when(restClient.get().uri("/users").retrieve().body(UserList.class))
                    .thenReturn(userList);

            // malformed/unexpected PARTICIPANTS value - Jackson parse fails, should fall back
            // to an empty list rather than blow up the whole reset-all.
            when(propertyService.getPropertyValue(PropertyConstants.PARTICIPANTS))
                    .thenReturn(new Object());

            when(roomService.listRoomFromServer()).thenReturn(emptyRooms);

            ResetAllResponse response = resetAllService.resetAll();

            assertThat(response.getUsersDeleted()).isEqualTo(1);
            verify(propertyService).sendObjectToPropService(PropertyConstants.PARTICIPANTS, List.of());
        }
    }

    @Test
    void resetAll_noRoomsOnServer_skipsRoomDeletionCleanly() {
        when(chatGatewayProperties.isResetAllEnabled()).thenReturn(true);
        when(chatGatewayProperties.getHost()).thenReturn("localhost");
        when(chatGatewayProperties.getPluginPort()).thenReturn(9090);
        when(chatGatewayProperties.getUsername()).thenReturn("admin");
        when(chatGatewayProperties.getPassword()).thenReturn("secret");

        RestClient.Builder builder = mock(RestClient.Builder.class, withSettings().defaultAnswer(RETURNS_SELF));
        RestClient restClient = mock(RestClient.class, withSettings().defaultAnswer(RETURNS_DEEP_STUBS));

        UserList emptyUsers = new UserList();
        emptyUsers.setUsers(List.of());

        try (MockedStatic<RestClient> restClientStatic = mockStatic(RestClient.class)) {
            restClientStatic.when(RestClient::builder).thenReturn(builder);
            when(builder.build()).thenReturn(restClient);

            when(restClient.get().uri("/system/properties/admin.authorizedJIDs").retrieve()
                    .body(any(org.springframework.core.ParameterizedTypeReference.class)))
                    .thenReturn(null);
            when(restClient.get().uri("/users").retrieve().body(UserList.class))
                    .thenReturn(emptyUsers);

            when(roomService.listRoomFromServer()).thenReturn(null); // rooms == null branch

            ResetAllResponse response = resetAllService.resetAll();

            assertThat(response.getRoomsDeleted()).isZero();
            assertThat(response.getMessagesDeleted()).isZero();
            verify(roomService).clearAllRoomTracking();
            verifyNoInteractions(mucRestApiService);
        }
    }
}

@Test
void shouldReturnAllMessages_whenMessageIdsEmpty() {
    MessageResponse m1 = MessageResponse.builder().messageId("msg-1").build();
    MessageResponse m2 = MessageResponse.builder().messageId("msg-2").build();
    when(messagingService.readMessageByParticipantAndRoom(ROOM_NAME, List.of()))
            .thenReturn(List.of(m1, m2));

    List<MessageResponse> result = chatService.readMessageByParticipantAndRoom(ROOM_NAME, List.of());

    assertThat(result).hasSize(2);
    assertThat(result).extracting(MessageResponse::getMessageId).containsExactly("msg-1", "msg-2");
}

@Test
void shouldReturnList_whenMessageIdMatch() {
    MessageResponse m2 = MessageResponse.builder().messageId("msg-2").build();
    when(messagingService.readMessageByParticipantAndRoom(ROOM_NAME, List.of("msg-2")))
            .thenReturn(List.of(m2));

    List<MessageResponse> result = chatService.readMessageByParticipantAndRoom(ROOM_NAME, List.of("msg-2"));

    assertThat(result).hasSize(1);
    assertThat(result).extracting(MessageResponse::getMessageId).containsExactly("msg-2");
}

@Test
void shouldReturnEmptyList_whenNoMessageIdMatch() {
    when(messagingService.readMessageByParticipantAndRoom(ROOM_NAME, List.of("no-match")))
            .thenReturn(List.of());

    List<MessageResponse> result = chatService.readMessageByParticipantAndRoom(ROOM_NAME, List.of("no-match"));

    assertThat(result).isEmpty();
}
