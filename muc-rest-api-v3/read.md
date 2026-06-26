Below is an updated version of the wiki that reflects your actual environment. It describes Red, Black, and Test NiFi as peers where each deployment acts as both a Source and a Destination. It also includes the security validation approach using a different keystore/truststore on the Test environment.

Apache NiFi Secure Site-to-Site Communication Validation

Jira Story

Document the security controls implemented by Apache NiFi for secure data transfer across the Cross Domain Solution (CDS) and provide evidence that the controls are functioning as expected.

⸻

1. Purpose

This document describes how Apache NiFi secures Site-to-Site (S2S) communication between Kubernetes clusters using HTTPS and TLS. It also documents the validation performed to demonstrate that only trusted Apache NiFi deployments can establish secure Site-to-Site communication.

⸻

2. Environment

The validation environment consists of three Apache NiFi deployments running in Kubernetes.

Environment

Role

Red NiFi

Source and Destination

Black NiFi

Source and Destination

Test NiFi

Source and Destination


Each deployment is capable of:

* Sending FlowFiles to another NiFi deployment.
* Receiving FlowFiles from another NiFi deployment.
* Acting as both a Site-to-Site client and server.

⸻

3. Architecture

```text

                    +----------------------+
                    |      Red NiFi        |
                    | Source / Destination |
                    +----------------------+
                       ↑              ↓
                 HTTPS/TLS      HTTPS/TLS
                       ↓              ↑
                    +----------------------+
                    |     Black NiFi       |
                    | Source / Destination |
                    +----------------------+
                       ↑              ↓
                 HTTPS/TLS      HTTPS/TLS
                       ↓              ↑
                    +----------------------+
                    |      Test NiFi       |
                    | Source / Destination |
                    +----------------------+

```

All Site-to-Site communication is performed over HTTPS using TLS encryption.

⸻

4. Security Controls

Security Control

Implementation

Encryption in Transit

HTTPS with TLS

Mutual Authentication

X.509 Certificates

Secure Site-to-Site Communication

Remote Process Groups over HTTPS

Authentication

Certificate-based authentication

Authorization

NiFi Access Policies

Audit Logging

Provenance Repository and NiFi application logs

Reliable Delivery

Queueing and automatic retry


5. Site-to-Site Configuration

Each Apache NiFi deployment is configured with:

* HTTPS enabled
* X.509 Server Certificate
* Keystore
* Truststore
* Secure Site-to-Site enabled
* Input Ports
* Remote Process Groups

Example Remote Process Group URL

```text
https://<nifi-host>:8443
```



6. Testing Approach

Objective

Validate that:

* Site-to-Site communication is encrypted.
* Trusted NiFi deployments can exchange FlowFiles.
* Communication fails when certificates are not trusted.
* No data is transferred over an untrusted connection.

⸻

Test Scenario 1 – Trusted Communication (Red ↔ Black)

Steps

1. Configure a Remote Process Group from Red to Black.
2. Send FlowFiles from Red to Black.
3. Verify successful receipt on Black.
4. Configure a Remote Process Group from Black to Red.
5. Send FlowFiles from Black to Red.
6. Verify successful receipt on Red.

Expected Result

* HTTPS connection established.
* TLS handshake successful.
* FlowFiles transferred successfully.
* Provenance events recorded on both deployments.

Result: PASS


Test Scenario 2 – Trusted Communication with Test Environment

The Test NiFi deployment also acts as both a Source and Destination.

Communication can be configured in both directions:

* Test → Red
* Red → Test
* Test → Black
* Black → Test

Where the certificate trust relationship exists, FlowFiles are transferred successfully over HTTPS using TLS.

⸻

Test Scenario 3 – Certificate Validation

Objective

Verify that Apache NiFi rejects Site-to-Site communication when the remote certificate is not trusted.

Test Configuration

The Test NiFi deployment was configured with a different keystore and truststore for validation purposes.

The receiving NiFi deployment does not trust the Test certificate.

Test Steps

1. Configure a Remote Process Group from Test NiFi to Red (or Black).
2. Start Site-to-Site communication.
3. Observe the Remote Process Group status.
4. Review the NiFi application logs.

Expected Result

* TLS handshake fails.
* Site-to-Site connection is rejected.
* No FlowFiles are transferred.
* FlowFiles remain queued on the source until the trust relationship is corrected.

Result: PASS

This confirms that Apache NiFi only accepts Site-to-Site communication from trusted certificates.

⸻

7. Failed Communication Handling

Apache NiFi provides reliable message delivery.

When communication cannot be established:

* FlowFiles remain queued.
* No FlowFiles are lost.
* Automatic retry occurs once communication is restored.
* Communication resumes automatically after the certificate or trust configuration is corrected.

This behaviour ensures reliable and secure message delivery.

⸻

8. Evidence

The following evidence was collected during testing:

* Red NiFi flow configuration.
* Black NiFi flow configuration.
* Test NiFi flow configuration.
* Remote Process Group configured with HTTPS.
* Successful FlowFile transfers between trusted deployments.
* Failed Site-to-Site connection when using an untrusted certificate.
* NiFi application logs showing TLS/certificate validation failure.
* Provenance events showing SEND and RECEIVE operations.
* Keystore and Truststore configuration used for validation.

⸻

9. Validation Summary

Validation

Result

HTTPS Site-to-Site enabled

PASS

TLS encryption verified

PASS

Red ↔ Black communication

PASS

Red ↔ Test communication (trusted)

PASS (where applicable)

Black ↔ Test communication (trusted)

PASS (where applicable)

Untrusted certificate rejected

PASS

FlowFiles retained during communication failure

PASS

Automatic retry after trust restored

PASS

Provenance and audit logging

PASS

10. Conclusion

The validation demonstrates that Apache NiFi provides secure Site-to-Site communication using HTTPS and TLS. All NiFi deployments (Red, Black, and Test) are capable of acting as both Source and Destination. Communication between trusted deployments is successful, while connections from deployments presenting untrusted certificates are rejected during the TLS handshake. Apache NiFi also ensures reliable delivery by retaining FlowFiles during communication failures and automatically retrying delivery once secure connectivity is restored. These results demonstrate that the implementation satisfies the required security controls for secure cross-domain data transfer.

This version is suitable for Confluence or your project wiki and aligns with your actual deployment model where Red, Black, and Test are all peers capable of sending and receiving data, rather than having fixed source/destination roles.
