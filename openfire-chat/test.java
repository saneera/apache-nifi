@ExtendWith(MockitoExtension.class)
class ReadRoomMessageCommandTest {

    @Mock
    private MultiUserChat muc;

    @Mock
    private MucEnterConfiguration.Builder mucBuilder;

    @Mock
    private MucEnterConfiguration mucEnterConfiguration;

    @InjectMocks
    private ChatService chatService;

    private static final String ROOM_NAME        = "Room1";
    private static final String PARTICIPANT_NAME = "participant1";
    private static final String ROOM_KEY         = ROOM_NAME + ":" + PARTICIPANT_NAME;

    @BeforeEach
    void setUp() {
        chatService.getJoinedRooms().put(ROOM_KEY, muc);

        // Stub the full builder chain — prevents NPE
        when(muc.getEnterConfigurationBuilder(any(Resourcepart.class)))
                .thenReturn(mucBuilder);
        when(mucBuilder.requestMaxStanzasHistory(anyInt()))
                .thenReturn(mucBuilder);
        when(mucBuilder.requestNoHistory())
                .thenReturn(mucBuilder);
        when(mucBuilder.build())
                .thenReturn(mucEnterConfiguration);
    }

    // ── Helper ────────────────────────────────────────────────────────────
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

    @Test
    void shouldReturnEmptyList_whenParticipantNotInRoom() {
        chatService.getJoinedRooms().clear();

        List<MessageResponse> result = chatService
                .readMessagesByParticipantAndRoom(
                        ROOM_NAME, PARTICIPANT_NAME, List.of("msg-1"));

        assertThat(result).isEmpty();
        verifyNoInteractions(muc);
    }

    @Test
    void shouldReturnMessages_whenMessageIdsProvided() throws Exception {
        when(muc.isJoined()).thenReturn(true);

        doAnswer(invocation -> {
            MessageListener listener = invocation.getArgument(0);
            listener.processMessage(
                    buildMessage("msg-1", "Hello", "participant1"));
            listener.processMessage(
                    buildMessage("msg-2", "World", "participant2"));
            listener.processMessage(
                    buildMessage("msg-3", "Other", "participant3"));
            return null;
        }).when(muc).addMessageListener(any());

        List<MessageResponse> result = chatService
                .readMessagesByParticipantAndRoom(
                        ROOM_NAME, PARTICIPANT_NAME, List.of("msg-1", "msg-2"));

        assertThat(result).hasSize(2);
        assertThat(result).extracting(MessageResponse::getMessageId)
                .containsExactlyInAnyOrder("msg-1", "msg-2");
        assertThat(result).extracting(MessageResponse::getSender)
                .containsExactlyInAnyOrder("participant1", "participant2");
    }

    @Test
    void shouldReturnAllMessages_whenMessageIdsEmpty() throws Exception {
        when(muc.isJoined()).thenReturn(true);

        doAnswer(invocation -> {
            MessageListener listener = invocation.getArgument(0);
            listener.processMessage(
                    buildMessage("msg-1", "Hello", "participant1"));
            listener.processMessage(
                    buildMessage("msg-2", "World", "participant2"));
            listener.processMessage(
                    buildMessage("msg-3", "Hey",   "participant3"));
            return null;
        }).when(muc).addMessageListener(any());

        List<MessageResponse> result = chatService
                .readMessagesByParticipantAndRoom(
                        ROOM_NAME, PARTICIPANT_NAME, Collections.emptyList());

        assertThat(result).hasSize(3);
    }

    @Test
    void shouldSkipMessages_whenBodyIsNull() throws Exception {
        when(muc.isJoined()).thenReturn(false);

        doAnswer(invocation -> {
            MessageListener listener = invocation.getArgument(0);
            Message nullBody = mock(Message.class);
            when(nullBody.getBody()).thenReturn(null);
            listener.processMessage(nullBody);
            listener.processMessage(
                    buildMessage("msg-1", "Hello", "participant1"));
            return null;
        }).when(muc).addMessageListener(any());

        List<MessageResponse> result = chatService
                .readMessagesByParticipantAndRoom(
                        ROOM_NAME, PARTICIPANT_NAME, Collections.emptyList());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getMessageId()).isEqualTo("msg-1");
    }

    @Test
    void shouldLeaveBeforeJoin_whenAlreadyJoined() throws Exception {
        when(muc.isJoined()).thenReturn(true);
        doAnswer(invocation -> null).when(muc).addMessageListener(any());

        chatService.readMessagesByParticipantAndRoom(
                ROOM_NAME, PARTICIPANT_NAME, Collections.emptyList());

        InOrder order = inOrder(muc);
        order.verify(muc).leave();
        order.verify(muc).join(any(MucEnterConfiguration.class));
    }

    @Test
    void shouldRemoveTempListener_afterRead() throws Exception {
        when(muc.isJoined()).thenReturn(false);
        doAnswer(invocation -> null).when(muc).addMessageListener(any());

        chatService.readMessagesByParticipantAndRoom(
                ROOM_NAME, PARTICIPANT_NAME, Collections.emptyList());

        verify(muc).removeMessageListener(any(MessageListener.class));
    }
}


public void deleteMessageFromRoom(String roomName,
                                  String participantName,
                                  String messageId) {
    try {
        MultiUserChat muc = checkParticipantJoinedTheRoom(
                roomName, participantName);

        // Build message targeting the ROOM JID
        MessageBuilder retractBuilder = muc.buildMessage()
                .ofType(Message.Type.groupchat);

        // Add retraction element using static helper
        MessageRetractionManager.addRetractionElementToMessage(
                new OriginIdElement(messageId),
                retractBuilder                   // ← your builder with room JID
        );

        // Send via connection
        muc.getXmppConnection().sendStanza(retractBuilder.build());

        log.info("Retracted message [{}] from room [{}]",
                messageId, roomName);

    } catch (IllegalArgumentException e) {
        throw e;
    } catch (SmackException.NotConnectedException e) {
        log.error("Error when connecting the room {}", e.getMessage());
        throw new IllegalArgumentException(
                "Error when connecting the room", e);
    } catch (InterruptedException e) {
        log.error("Error retracting message [{}] from room [{}]",
                messageId, roomName, e);
        throw new IllegalArgumentException(
                "Error retracting message", e);
    }
}
