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
