// ==============================
// XmppConnectionManager.java
// ==============================

package com.chat.xmpp;

import lombok.extern.slf4j.Slf4j;
import org.jivesoftware.smack.ConnectionConfiguration;
import org.jivesoftware.smack.ReconnectionManager;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class XmppConnectionManager {

    private final Map<String, XMPPTCPConnection> connections =
            new ConcurrentHashMap<>();

    public synchronized XMPPTCPConnection getConnection(
            String username,
            String password
    ) throws Exception {

        XMPPTCPConnection existing =
                connections.get(username);

        if (existing != null) {

            if (existing.isConnected()
                    && existing.isAuthenticated()) {
                return existing;
            }

            if (!existing.isConnected()) {
                existing.connect();
            }

            if (!existing.isAuthenticated()) {
                existing.login(username, password);
            }

            return existing;
        }

        XMPPTCPConnectionConfiguration config =
                XMPPTCPConnectionConfiguration.builder()
                        .setXmppDomain("localhost")
                        .setHost("localhost")
                        .setPort(5222)
                        .setSecurityMode(
                                ConnectionConfiguration
                                        .SecurityMode
                                        .disabled
                        )
                        .build();

        XMPPTCPConnection connection =
                new XMPPTCPConnection(config);

        connection.connect();
        connection.login(username, password);

        ReconnectionManager
                .getInstanceFor(connection)
                .enableAutomaticReconnection();

        connections.put(username, connection);

        log.info("Connected XMPP user {}", username);

        return connection;
    }

    public void disconnect(String username) {

        XMPPTCPConnection connection =
                connections.remove(username);

        if (connection != null
                && connection.isConnected()) {

            connection.disconnect();

            log.info(
                    "Disconnected XMPP user {}",
                    username
            );
        }
    }
}



// ==============================
// ChatMessageEvent.java
// ==============================

package com.chat.xmpp.events;

import lombok.*;

        import java.time.Instant;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageEvent {

    private String roomName;

    private String sender;

    private String message;

    private Instant timestamp;
}



// ==============================
// MessageListenerService.java
// ==============================

package com.chat.xmpp.services;

import com.chat.xmpp.events.ChatMessageEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smackx.muc.MultiUserChat;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageListenerService {

    private final ApplicationEventPublisher publisher;

    public void registerRoomListener(
            MultiUserChat muc,
            String roomName
    ) {

        muc.addMessageListener(message -> {

            try {

                processRoomMessage(
                        roomName,
                        message
                );

            } catch (Exception ex) {

                log.error(
                        "Error processing room message",
                        ex
                );
            }
        });
    }

    private void processRoomMessage(
            String roomName,
            Message message
    ) {

        if (message.getBody() == null) {
            return;
        }

        String sender =
                message.getFrom() != null
                        ? message.getFrom().toString()
                        : "unknown";

        ChatMessageEvent event =
                ChatMessageEvent.builder()
                        .roomName(roomName)
                        .sender(sender)
                        .message(message.getBody())
                        .timestamp(Instant.now())
                        .build();

        log.info(
                "Received room message {}",
                event
        );

        publisher.publishEvent(event);
    }
}



// ==============================
// XmppRoomService.java
// ==============================

package com.chat.xmpp.services;

import com.chat.xmpp.XmppConnectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smackx.muc.MultiUserChat;
import org.jivesoftware.smackx.muc.MultiUserChatManager;
import org.jxmpp.jid.EntityBareJid;
import org.jxmpp.jid.impl.JidCreate;
import org.jxmpp.jid.parts.Resourcepart;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class XmppRoomService {

    private final XmppConnectionManager connectionManager;

    private final MessageListenerService messageListenerService;

    public MultiUserChat joinRoom(
            String roomName,
            String username,
            String password
    ) throws Exception {

        XMPPTCPConnection connection =
                connectionManager.getConnection(
                        username,
                        password
                );

        MultiUserChatManager manager =
                MultiUserChatManager.getInstanceFor(
                        connection
                );

        EntityBareJid roomJid =
                JidCreate.entityBareFrom(
                        roomName
                                + "@conference.localhost"
                );

        MultiUserChat muc =
                manager.getMultiUserChat(roomJid);

        if (!muc.isJoined()) {

            muc.join(
                    Resourcepart.from(username)
            );

            messageListenerService
                    .registerRoomListener(
                            muc,
                            roomName
                    );

            log.info(
                    "{} joined room {}",
                    username,
                    roomName
            );
        }

        return muc;
    }
}


// ==============================
// XmppMessageService.java
// ==============================

package com.chat.xmpp.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jivesoftware.smackx.muc.MultiUserChat;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class XmppMessageService {

    private final XmppRoomService roomService;

    public void sendRoomMessage(
            String roomName,
            String username,
            String password,
            String message
    ) {

        try {

            MultiUserChat muc =
                    roomService.joinRoom(
                            roomName,
                            username,
                            password
                    );

            muc.sendMessage(message);

            log.info(
                    "Sent message to room {}",
                    roomName
            );

        } catch (Exception ex) {

            log.error(
                    "Failed to send room message",
                    ex
            );

            throw new RuntimeException(ex);
        }
    }
}


// ==============================
// ChatMessageEventListener.java
// ==============================

package com.chat.xmpp.listeners;

import com.chat.xmpp.events.ChatMessageEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ChatMessageEventListener {

    @EventListener
    public void handleIncomingMessage(
            ChatMessageEvent event
    ) {

        log.info(
                "Processing incoming event {}",
                event
        );

        // Save to database
        // Publish to redis
        // Send websocket event
        // Trigger push notification
    }
}


// ==============================
// ChatController.java
// ==============================

package com.chat.controllers;

import com.chat.xmpp.services.XmppMessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatController {

    private final XmppMessageService messageService;

    @PostMapping("/rooms/{roomName}/messages")
    public void sendRoomMessage(
            @PathVariable String roomName,
            @RequestParam String username,
            @RequestParam String password,
            @RequestBody String message
    ) {

        messageService.sendRoomMessage(
                roomName,
                username,
                password,
                message
        );
    }
}
