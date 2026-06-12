@ExtendWith(MockitoExtension.class)
class ChatServiceReadMessagesTest {

    @Mock
    private XmppConnectionManager xmppConnectionManager;

    @Mock
    private ChatManagerProvider chatManagerProvider;

    @Mock
    private MultiUserChat muc;

    @Mock
    private EventListenerService eventListenerService;

    @InjectMocks
    private ChatService chatService;

    private static final String ROOM_NAME        = "Room1";
    private static final String PARTICIPANT_NAME = "participant1";
    private static final String ROOM_KEY         = ROOM_NAME + ":" + PARTICIPANT_NAME;

    @BeforeEach
    void setUp() {
        // Put muc into joinedRooms cache
        chatService.getJoinedRooms().put(ROOM_KEY, muc);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    private Message buildMessage(String stanzaId, String body, String from) {
        Message message = mock(Message.class);
        when(message.getStanzaId()).thenReturn(stanzaId);
        when(message.getBody()).thenReturn(body);

        Jid fromJid = mock(Jid.class);
        when(fromJid.getResourceOrEmpty())
                .thenReturn(Resourcepart.fromOrNull(from));
        when(message.getFrom()).thenReturn(fromJid);

        return message;
    }

    private Message buildDelayedMessage(String stanzaId, String body,
                                        String from, Instant originalTime) {
        Message message = buildMessage(stanzaId, body, from);

        DelayInformation delay = mock(DelayInformation.class);
        when(delay.getStamp()).thenReturn(Date.from(originalTime));

        // Mock static DelayInformation.from(message)
        try (MockedStatic<DelayInformation> mockedDelay =
                     mockStatic(DelayInformation.class)) {
            mockedDelay.when(() -> DelayInformation.from(message))
                    .thenReturn(delay);
        }

        return message;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Test: participant not in room
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void shouldReturnEmptyList_whenParticipantNotInRoom() {
        // Given
        chatService.getJoinedRooms().clear();   // no muc in cache

        // When
        List<MessageResponse> result = chatService
                .readMessagesByParticipantAndRoom(
                        ROOM_NAME, PARTICIPANT_NAME, List.of("msg-1"));

        // Then
        assertThat(result).isEmpty();
        verifyNoInteractions(muc);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Test: fetch specific message IDs
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void shouldReturnMessages_whenMessageIdsProvided() throws Exception {
        // Given
        when(muc.isJoined()).thenReturn(true);

        List<String> targetIds = List.of("msg-1", "msg-2");

        // Simulate history replay via listener
        doAnswer(invocation -> {
            MessageListener listener = invocation.getArgument(0);
            listener.processMessage(buildMessage("msg-1", "Hello", "alice"));
            listener.processMessage(buildMessage("msg-2", "World", "bob"));
            listener.processMessage(buildMessage("msg-3", "Other", "carol")); // not in list
            return null;
        }).when(muc).addMessageListener(any());

        // When
        List<MessageResponse> result = chatService
                .readMessagesByParticipantAndRoom(
                        ROOM_NAME, PARTICIPANT_NAME, targetIds);

        // Then
        assertThat(result).hasSize(2);
        assertThat(result).extracting(MessageResponse::getMessageId)
                .containsExactlyInAnyOrder("msg-1", "msg-2");
        assertThat(result).extracting(MessageResponse::getSender)
                .containsExactlyInAnyOrder("alice", "bob");
    }

    // ─────────────────────────────────────────────────────────────────────
    // Test: fetch all messages (empty messageIds)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void shouldReturnAllMessages_whenMessageIdsEmpty() throws Exception {
        // Given
        when(muc.isJoined()).thenReturn(true);

        doAnswer(invocation -> {
            MessageListener listener = invocation.getArgument(0);
            listener.processMessage(buildMessage("msg-1", "Hello", "alice"));
            listener.processMessage(buildMessage("msg-2", "World", "bob"));
            listener.processMessage(buildMessage("msg-3", "Hey",   "carol"));
            return null;
        }).when(muc).addMessageListener(any());

        // When — empty list = fetch all
        List<MessageResponse> result = chatService
                .readMessagesByParticipantAndRoom(
                        ROOM_NAME, PARTICIPANT_NAME, Collections.emptyList());

        // Then
        assertThat(result).hasSize(3);
        assertThat(result).extracting(MessageResponse::getMessageId)
                .containsExactlyInAnyOrder("msg-1", "msg-2", "msg-3");
    }

    // ─────────────────────────────────────────────────────────────────────
    // Test: fetch all messages (null messageIds)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void shouldReturnAllMessages_whenMessageIdsNull() throws Exception {
        // Given
        when(muc.isJoined()).thenReturn(true);

        doAnswer(invocation -> {
            MessageListener listener = invocation.getArgument(0);
            listener.processMessage(buildMessage("msg-1", "Hello", "alice"));
            return null;
        }).when(muc).addMessageListener(any());

        // When — null = fetch all
        List<MessageResponse> result = chatService
                .readMessagesByParticipantAndRoom(
                        ROOM_NAME, PARTICIPANT_NAME, null);

        // Then
        assertThat(result).hasSize(1);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Test: messages with null body are skipped
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void shouldSkipMessages_whenBodyIsNull() throws Exception {
        // Given
        when(muc.isJoined()).thenReturn(true);

        doAnswer(invocation -> {
            MessageListener listener = invocation.getArgument(0);
            // message with null body — should be skipped
            Message nullBody = mock(Message.class);
            when(nullBody.getBody()).thenReturn(null);
            listener.processMessage(nullBody);
            // valid message
            listener.processMessage(buildMessage("msg-1", "Hello", "alice"));
            return null;
        }).when(muc).addMessageListener(any());

        // When
        List<MessageResponse> result = chatService
                .readMessagesByParticipantAndRoom(
                        ROOM_NAME, PARTICIPANT_NAME, Collections.emptyList());

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getMessageId()).isEqualTo("msg-1");
    }

    // ─────────────────────────────────────────────────────────────────────
    // Test: leave called before join
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void shouldLeaveBeforeJoin_whenAlreadyJoined() throws Exception {
        // Given
        when(muc.isJoined()).thenReturn(true);
        doAnswer(invocation -> null).when(muc).addMessageListener(any());

        // When
        chatService.readMessagesByParticipantAndRoom(
                ROOM_NAME, PARTICIPANT_NAME, Collections.emptyList());

        // Then — leave must be called before join
        InOrder order = inOrder(muc);
        order.verify(muc).leave();
        order.verify(muc).join(any(MucEnterConfiguration.class));
    }

    // ─────────────────────────────────────────────────────────────────────
    // Test: rejoin without history in finally
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void shouldRejoinWithoutHistory_afterRead() throws Exception {
        // Given
        when(muc.isJoined())
                .thenReturn(true)   // first check — triggers leave
                .thenReturn(false); // finally check — triggers rejoin

        doAnswer(invocation -> null).when(muc).addMessageListener(any());

        // When
        chatService.readMessagesByParticipantAndRoom(
                ROOM_NAME, PARTICIPANT_NAME, Collections.emptyList());

        // Then — join called twice: once for read, once for rejoin
        verify(muc, times(2)).join(any(MucEnterConfiguration.class));
    }

    // ─────────────────────────────────────────────────────────────────────
    // Test: temp listener removed in finally
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void shouldRemoveTempListener_afterRead() throws Exception {
        // Given
        when(muc.isJoined()).thenReturn(false);
        doAnswer(invocation -> null).when(muc).addMessageListener(any());

        // When
        chatService.readMessagesByParticipantAndRoom(
                ROOM_NAME, PARTICIPANT_NAME, Collections.emptyList());

        // Then
        verify(muc).removeMessageListener(any(MessageListener.class));
    }

    // ─────────────────────────────────────────────────────────────────────
    // Test: timeout — partial results returned
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void shouldReturnPartialResults_whenTimeoutOccurs() throws Exception {
        // Given
        when(muc.isJoined()).thenReturn(false);

        // Only delivers 1 of 2 requested messages — causes timeout
        doAnswer(invocation -> {
            MessageListener listener = invocation.getArgument(0);
            listener.processMessage(buildMessage("msg-1", "Hello", "alice"));
            // msg-2 never arrives
            return null;
        }).when(muc).addMessageListener(any());

        // When
        List<MessageResponse> result = chatService
                .readMessagesByParticipantAndRoom(
                        ROOM_NAME, PARTICIPANT_NAME, List.of("msg-1", "msg-2"));

        // Then — returns what was found before timeout
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getMessageId()).isEqualTo("msg-1");
    }
}
