package com.babcock.inspire.openfire.services;

// Proposed split of ChatService.java - see ChatService.java (facade) for rationale.
// Owns Openfire account lifecycle for participants (separate from room
// membership, which lives in RoomService).

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jxmpp.parts.Localpart;

@Slf4j
@RequiredArgsConstructor
public class ParticipantAccountService {

    private final ChatGatewayProperties chatGatewayProperties;
    private final XmppConnectionManager xmppConnectionManager;

    /**
     * Create user for participant
     */
    public void createAccount(String participantName) {
        log.info("Creating participant on Openfire for [{}]", participantName);
        AccountManager accountManager = AccountManager.getInstance(xmppConnectionManager.getSystemConnection());
        accountManager.sensitiveOperationOverInsecureConnection(true);
        try {
            accountManager.createAccount(
                    Localpart.from(participantName), chatGatewayProperties.getParticipantPassword());
        } catch (XMPPException.XMPPErrorException
                 | SmackException.NoResponseException
                 | SmackException.NotConnectedException
                 | InterruptedException e) {
            log.error("Error when create Account [{}]", e.getMessage());
            throw new IllegalArgumentException("Error when create Account ", e);
        }
    }

    public void deleteAccount(String participantName) {
        log.info("Deleting participant on Openfire for [{}]", participantName);

        XMPPTCPConnection participantConnection = xmppConnectionManager.getParticipantConnection(
                participantName, chatGatewayProperties.getParticipantPassword());
        AccountManager accountManager = AccountManager.getInstance(participantConnection);
        accountManager.sensitiveOperationOverInsecureConnection(true);

        try {
            accountManager.deleteAccount();
            participantConnection.instantShutdown();
        } catch (XMPPException.XMPPErrorException
                 | SmackException.NoResponseException
                 | SmackException.NotConnectedException
                 | InterruptedException e) {
            log.error("Error when delete Account [{}]", e.getMessage());
            throw new IllegalArgumentException("Error when delete Account ", e);
        }
    }
}
