import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Map;

import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smackx.muc.HostedRoom;
import org.jivesoftware.smackx.muc.MultiUserChatManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class SmackServiceTest {

    private SmackService smackService;
    private XMPPTCPConnection connection;
    private DriverConfig driverConfig;

    @BeforeEach
    void setUp() {
        connection = mock(XMPPTCPConnection.class);
        driverConfig = mock(DriverConfig.class);

        smackService = spy(new SmackService());
        smackService.connection = connection;
        smackService.driverConfig = driverConfig;
    }

    @Test
    void testListRoomFromServer_success() throws Exception {

        when(driverConfig.getServiceName()).thenReturn("conference");
        when(driverConfig.getDomain()).thenReturn("example.com");

        MultiUserChatManager manager = mock(MultiUserChatManager.class);

        HostedRoom hostedRoom1 = mock(HostedRoom.class);
        HostedRoom hostedRoom2 = mock(HostedRoom.class);

        Map map = Map.of(
                mock(EntityBareJid.class), hostedRoom1,
                mock(EntityBareJid.class), hostedRoom2
        );

        try (MockedStatic<MultiUserChatManager> mucMock =
                     Mockito.mockStatic(MultiUserChatManager.class)) {

            mucMock.when(() ->
                            MultiUserChatManager.getInstanceFor(connection))
                    .thenReturn(manager);

            when(manager.getRoomsHostedBy(any()))
                    .thenReturn(map);

            Room room1 = new Room("Test1", "Desc1");
            Room room2 = new Room("Test2", "Desc2");

            doReturn(room1).when(smackService)
                    .buildRoom(manager, hostedRoom1);

            doReturn(room2).when(smackService)
                    .buildRoom(manager, hostedRoom2);

            Rooms result = smackService.listRoomFromServer();

            assertNotNull(result);
            assertEquals(2, result.getRooms().size());
            assertEquals("Test1", result.getRooms().get(0).getRoomName());
            assertEquals("Test2", result.getRooms().get(1).getRoomName());
        }
    }

    @Test
    void testListRoomFromServer_exception_returnsEmptyRooms() throws Exception {

        when(driverConfig.getServiceName()).thenReturn("conference");
        when(driverConfig.getDomain()).thenReturn("example.com");

        try (MockedStatic<MultiUserChatManager> mucMock =
                     Mockito.mockStatic(MultiUserChatManager.class)) {

            mucMock.when(() ->
                            MultiUserChatManager.getInstanceFor(connection))
                    .thenThrow(new RuntimeException("error"));

            Rooms result = smackService.listRoomFromServer();

            assertNotNull(result);
            assertTrue(result.getRooms().isEmpty());
        }
    }
}


============


        import static org.junit.jupiter.api.Assertions.*;
        import static org.mockito.ArgumentMatchers.*;
        import static org.mockito.Mockito.*;

        import java.util.HashMap;
import java.util.Map;

import org.jivesoftware.smackx.muc.MultiUserChat;
import org.jivesoftware.smackx.muc.MultiUserChatManager;
import org.jivesoftware.smackx.xdata.Form;
import org.jivesoftware.smackx.xdata.packet.DataForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class CreateRoomCommandTest {

    private CreateRoomCommand command;

    private SmackService service;
    private PropertyService propertyService;
    private ChatGatewayProperties driverConfig;
    private MultiUserChatManager manager;
    private MultiUserChat muc;

    @BeforeEach
    void setup() {
        service = mock(SmackService.class);
        propertyService = mock(PropertyService.class);
        driverConfig = mock(ChatGatewayProperties.class);
        manager = mock(MultiUserChatManager.class);
        muc = mock(MultiUserChat.class);

        command = spy(new CreateRoomCommand(
                service,
                propertyService
        ));
    }

    @Test
    void executeCommand_success() throws Exception {

        Map<String, Object> params = new HashMap<>();
        params.put("roomName", "Test1");
        params.put("description", "Test1");

        Rooms rooms = new Rooms();

        doReturn(driverConfig).when(service).getDriverConfig();
        doReturn(rooms).when(command)
                .validateRoomExists(any(Rooms.class), eq("Test1"));

        when(service.getConnection()).thenReturn(null);

        try (MockedStatic<MultiUserChatManager> mockStatic =
                     Mockito.mockStatic(MultiUserChatManager.class)) {

            mockStatic.when(() ->
                            MultiUserChatManager.getInstanceFor(any()))
                    .thenReturn(manager);

            when(manager.getMultiUserChat(any()))
                    .thenReturn(muc);

            Form form = mock(Form.class);
            Form submitForm = mock(Form.class);

            when(muc.getConfigurationForm()).thenReturn(form);
            when(form.getFillableForm()).thenReturn(submitForm);

            when(driverConfig.getServiceName()).thenReturn("conference");
            when(driverConfig.getDomain()).thenReturn("example.com");
            when(driverConfig.getMaxUsers()).thenReturn(10);

            RoomInfo roomInfo = mock(RoomInfo.class);
            when(manager.getRoomInfo(any()))
                    .thenReturn(roomInfo);

            when(roomInfo.getName()).thenReturn("Test1");
            when(roomInfo.getDescription()).thenReturn("Test1");

            SmackAssetResponse response =
                    command.executeCommand("create-room", params);

            assertNotNull(response);
            verify(propertyService)
                    .sendObjectToPropService("rooms", rooms);

            assertEquals(1, rooms.getRooms().size());
            assertEquals("Test1",
                    rooms.getRooms().get(0).getRoomName());
        }
    }

    @Test
    void executeCommand_roomNameNull_throwsException() {

        Map<String, Object> params = new HashMap<>();
        params.put("roomName", null);
        params.put("description", "desc");

        assertThrows(IllegalArgumentException.class,
                () -> command.executeCommand(
                        "create-room", params));
    }

    @Test
    void executeCommand_descriptionNull_throwsException() {

        Map<String, Object> params = new HashMap<>();
        params.put("roomName", "Test1");
        params.put("description", null);

        assertThrows(IllegalArgumentException.class,
                () -> command.executeCommand(
                        "create-room", params));
    }

    @Test
    void executeCommand_duplicateRoom_throwsException() {

        Map<String, Object> params = new HashMap<>();
        params.put("roomName", "Test1");
        params.put("description", "desc");

        doThrow(new IllegalArgumentException("duplicate"))
                .when(command)
                .validateRoomExists(any(), eq("Test1"));

        assertThrows(IllegalArgumentException.class,
                () -> command.executeCommand(
                        "create-room", params));
    }

    @Test
    void executeCommand_internalFailure_throwsXmppException()
            throws Exception {

        Map<String, Object> params = new HashMap<>();
        params.put("roomName", "Test1");
        params.put("description", "desc");

        Rooms rooms = new Rooms();

        doReturn(driverConfig).when(service).getDriverConfig();
        doReturn(rooms).when(command)
                .validateRoomExists(any(), eq("Test1"));

        when(service.getConnection()).thenReturn(null);

        try (MockedStatic<MultiUserChatManager> mockStatic =
                     Mockito.mockStatic(MultiUserChatManager.class)) {

            mockStatic.when(() ->
                            MultiUserChatManager.getInstanceFor(any()))
                    .thenThrow(new RuntimeException());

            assertThrows(XmppStringprepException.class,
                    () -> command.executeCommand(
                            "create-room", params));
        }
    }
}



=============



        import static org.junit.jupiter.api.Assertions.*;
        import static org.mockito.ArgumentMatchers.*;
        import static org.mockito.Mockito.*;

        import java.util.HashMap;
import java.util.Map;

import org.jivesoftware.smackx.muc.MultiUserChat;
import org.jivesoftware.smackx.muc.MultiUserChatManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class DeleteRoomCommandTest {

    private DeleteRoomCommand command;

    private SmackService service;
    private PropertyService propertyService;
    private ChatGatewayProperties driverConfig;
    private MultiUserChatManager manager;
    private MultiUserChat muc;

    @BeforeEach
    void setup() {

        service = mock(SmackService.class);
        propertyService = mock(PropertyService.class);
        driverConfig = mock(ChatGatewayProperties.class);
        manager = mock(MultiUserChatManager.class);
        muc = mock(MultiUserChat.class);

        command = spy(new DeleteRoomCommand(
                service,
                propertyService
        ));
    }

    @Test
    void executeCommand_success() throws Exception {

        Map<String, Object> params = new HashMap<>();
        params.put("roomName", "Test1");

        Rooms rooms = new Rooms();
        rooms.getRooms().add(
                new Room("Test1", "Desc1"));
        rooms.getRooms().add(
                new Room("Test2", "Desc2"));

        doReturn(driverConfig)
                .when(service).getDriverConfig();

        doReturn(rooms)
                .when(command)
                .validateRoomExistsBeforeDelete(
                        any(Rooms.class),
                        eq("Test1"));

        when(service.getConnection()).thenReturn(null);

        try (MockedStatic<MultiUserChatManager> mockStatic =
                     Mockito.mockStatic(MultiUserChatManager.class)) {

            mockStatic.when(() ->
                            MultiUserChatManager.getInstanceFor(any()))
                    .thenReturn(manager);

            when(manager.getMultiUserChat(any()))
                    .thenReturn(muc);

            when(driverConfig.getServiceName())
                    .thenReturn("conference");

            when(driverConfig.getDomain())
                    .thenReturn("example.com");

            SmackAssetResponse response =
                    command.executeCommand(
                            "delete-room", params);

            assertNotNull(response);

            verify(muc).destroy(
                    eq("Room closed"),
                    isNull());

            verify(propertyService)
                    .sendObjectToPropService(
                            "rooms",
                            rooms);

            assertEquals(1,
                    rooms.getRooms().size());

            assertEquals("Test2",
                    rooms.getRooms()
                            .get(0)
                            .getRoomName());
        }
    }

    @Test
    void executeCommand_roomNameNull_throwsException() {

        Map<String, Object> params = new HashMap<>();
        params.put("roomName", null);

        assertThrows(
                IllegalArgumentException.class,
                () -> command.executeCommand(
                        "delete-room",
                        params));
    }

    @Test
    void executeCommand_roomNotFound_throwsException() {

        Map<String, Object> params = new HashMap<>();
        params.put("roomName", "Test1");

        doThrow(new IllegalArgumentException("not found"))
                .when(command)
                .validateRoomExistsBeforeDelete(
                        any(Rooms.class),
                        eq("Test1"));

        assertThrows(
                IllegalArgumentException.class,
                () -> command.executeCommand(
                        "delete-room",
                        params));
    }

    @Test
    void executeCommand_internalFailure_throwsXmppException()
            throws Exception {

        Map<String, Object> params = new HashMap<>();
        params.put("roomName", "Test1");

        Rooms rooms = new Rooms();
        rooms.getRooms().add(
                new Room("Test1", "Desc1"));

        doReturn(driverConfig)
                .when(service).getDriverConfig();

        doReturn(rooms)
                .when(command)
                .validateRoomExistsBeforeDelete(
                        any(Rooms.class),
                        eq("Test1"));

        when(service.getConnection()).thenReturn(null);

        try (MockedStatic<MultiUserChatManager> mockStatic =
                     Mockito.mockStatic(MultiUserChatManager.class)) {

            mockStatic.when(() ->
                            MultiUserChatManager.getInstanceFor(any()))
                    .thenThrow(new RuntimeException());

            assertThrows(
                    XmppStringprepException.class,
                    () -> command.executeCommand(
                            "delete-room",
                            params));
        }
    }
}


============

@ExtendWith(MockitoExtension.class)
class CreateRoomCommandTest {

    @Mock
    private SmackService service;

    @Mock
    private PropertyService propertyService;

    @Mock
    private PropertyHandlerFactory propertyHandlerFactory;

    @Mock
    private MultiUserChatManager manager;

    @Mock
    private MultiUserChat muc;

    @Mock
    private Form form;

    @Mock
    private FillableForm submitForm;

    private CreateRoomCommand command;

    @BeforeEach
    void setup() {

        ChatGatewayProperties config = new ChatGatewayProperties();
        config.setServiceName("conference");
        config.setDomain("example.com");
        config.setMaxUsers(20);

        when(service.getDriverConfig()).thenReturn(config);

        // IMPORTANT: wrapper method in SmackService
        when(service.getChatManager()).thenReturn(manager);

        command = spy(new CreateRoomCommand(
                service,
                propertyService,
                propertyHandlerFactory
        ));
    }

    @Test
    void executeCommand_success() throws Exception {

        Map<String, Object> params = new HashMap<>();
        params.put("roomName", "Test1");
        params.put("description", "Test Room");

        Rooms rooms = new Rooms();

        doReturn(rooms).when(command)
                .validateRoomExists(any(Rooms.class), eq("Test1"));

        when(manager.getMultiUserChat(any())).thenReturn(muc);

        when(muc.getConfigurationForm()).thenReturn(form);
        when(form.getFillableForm()).thenReturn(submitForm);

        RoomInfo roomInfo = mock(RoomInfo.class);
        when(roomInfo.getName()).thenReturn("Test1");
        when(roomInfo.getDescription()).thenReturn("Test Room");

        when(manager.getRoomInfo(any())).thenReturn(roomInfo);

        SmackAssetResponse response =
                command.executeCommand("create-room", params);

        assertNotNull(response);
        assertEquals(1, rooms.getRooms().size());
        assertEquals("Test1", rooms.getRooms().get(0).getRoomName());

        verify(muc).create(any());
        verify(muc).sendConfigurationForm(submitForm);

        verify(propertyService)
                .sendObjectToPropService("rooms", rooms);
    }
}


=====
@ExtendWith(MockitoExtension.class)
class DeleteRoomCommandTest {

    @Mock
    private SmackService service;

    @Mock
    private PropertyService propertyService;

    @Mock
    private PropertyHandlerFactory propertyHandlerFactory;

    @Mock
    private ChatGatewayProperties driverConfig;

    @Mock
    private MultiUserChat muc;

    @Mock
    private ChatManagerProvider provider;

    private DeleteRoomCommand command;

    @BeforeEach
    void setup() {
        command = new DeleteRoomCommand(
                service,
                propertyService,
                propertyHandlerFactory
        );
    }
}


==========





        import org.jivesoftware.smackx.muc.MultiUserChat;
import org.jivesoftware.smackx.muc.MultiUserChatManager;
import org.jivesoftware.smackx.muc.RoomInfo;
import org.jivesoftware.smackx.xdata.Form;
import org.jivesoftware.smackx.xdata.FillableForm;
import org.jxmpp.jid.EntityBareJid;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
        import static org.mockito.Mockito.*;
        import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class CreateRoomCommandTest {

@Mock
private YourServiceClass service; // The service in your image

@Mock
private MultiUserChatManager manager; // Mockito-inline handles this final class

@Mock
private MultiUserChat multiUserChat;

@Mock
private FillableForm fillableForm;

@InjectMocks
private CreateRoomCommand createRoomCommand;

@Test
void testExecuteCommand_FullFlow() throws Exception {
// 1. Prepare Mock Data
Map<String, Object> params = new HashMap<>();
params.put("roomName", "dev-room");
params.put("description", "Development Chat");

ChatGatewayProperties props = mock(ChatGatewayProperties.class);
Form mockForm = mock(Form.class);
RoomInfo mockRoomInfo = mock(RoomInfo.class);
EntityBareJid mockJid = mock(EntityBareJid.class);

// 2. Setup Stubs
when(service.getDriverConfig()).thenReturn(props);
when(service.getManager()).thenReturn(manager);
when(props.getServiceName()).thenReturn("conference.localhost");

// Use try-with-resources for the static JidCreate mock
try (MockedStatic<JidCreate> jidStatic = mockStatic(JidCreate.class)) {
jidStatic.when(() -> JidCreate.entityBareFrom(anyString())).thenReturn(mockJid);

when(manager.getMultiUserChat(mockJid)).thenReturn(multiUserChat);
when(multiUserChat.getConfigurationForm()).thenReturn(mockForm);
when(mockForm.getFillableForm()).thenReturn(fillableForm);
when(manager.getRoomInfo(mockJid)).thenReturn(mockRoomInfo);
when(mockRoomInfo.getName()).thenReturn("dev-room");

// 3. Execute
SmackAssetResponse response = createRoomCommand.executeCommand("CreateRoom", params);

// 4. Verify
assertNotNull(response);
verify(multiUserChat).create(any());
verify(fillableForm).setAnswer(eq("muc#roomconfig_roomname"), eq("dev-room"));
verify(multiUserChat).sendConfigurationForm(fillableForm);
}
}
}
