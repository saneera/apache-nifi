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
