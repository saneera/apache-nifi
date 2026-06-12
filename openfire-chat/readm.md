# Offline Caching for Mobile End Users

## Overview

This document covers the offline message caching strategy for Openfire,
storage requirements, MySQL volume sizing, and how to verify using MySQL Workbench.

Epic: Traffic Gateway - Chat
Story: HFC2-0791 — Offline caching for mobile end users

---

## How it works

Openfire has built-in store-and-forward for offline messages.
No plugins required. No client-side code changes needed.

```
Sender sends message
        ↓
Destination MEU is offline
        ↓
Openfire stores message in ofOffline table (MySQL)
        ↓
MEU comes back online
        ↓
Openfire automatically pushes all stored messages to MEU
        ↓
Smack message listener receives them — same as live messages
```

The client (Smack) does not need any special handling.
The existing `addMessageListener` catches both live and offline-delivered messages.

---

## Storage Requirements

### Assumptions

| Parameter | Value |
|-----------|-------|
| Mobile End Users (MEUs) | 100 (upper bound of < 100 range) |
| Messages per user per day | 50 (upper bound of 10–50 range) |
| Message size | 100 KB (as per story assumption) |
| Retention period | 1 day |
| Safety buffer | 2x |

### Calculation

```
Base storage = Users × Messages/day × Message size × Retention days
             = 100 × 50 × 100 KB × 1
             = 500,000 KB
             = ~488 MB

With 2x safety buffer = ~1 GB
```

### Recommendation

| Tier | Storage | Suitable for |
|------|---------|-------------|
| Minimum | 1 GB | Current requirements |
| Recommended | 5 GB | Room to grow |
| Production safe | 10 GB | Future user growth |

**Recommended PVC size: 5 GB** for the MySQL volume.

---

## Kubernetes Volume Configuration

The MySQL PVC in `k8s/01-mysql.yaml` should be sized as follows:

```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: mysql-data
  namespace: openfire
spec:
  accessModes: [ReadWriteOnce]
  storageClassName: standard
  resources:
    requests:
      storage: 5Gi     # sized for offline message storage
```

---

## Openfire Offline Quota Property

Openfire enforces a per-user offline storage quota.
Default is 100 KB per user which matches the story assumption.

To verify or update:

```sql
-- Check current quota
SELECT * FROM ofProperty WHERE name = 'xmpp.offline.quota';

-- Set quota to 100 KB per user (default)
INSERT IGNORE INTO ofProperty (name, propValue)
VALUES ('xmpp.offline.quota', '102400');

-- Set retention period to 1 day (86400 seconds)
INSERT IGNORE INTO ofProperty (name, propValue)
VALUES ('xmpp.offline.store_messages', 'true');
```

---

## Verifying with MySQL Workbench

### Connect to the MySQL pod

**Step 1 — Port-forward the MySQL pod locally:**

```bash
kubectl -n openfire port-forward statefulset/mysql 3306:3306
```

**Step 2 — Open MySQL Workbench and create a new connection:**

| Field | Value |
|-------|-------|
| Hostname | 127.0.0.1 |
| Port | 3306 |
| Username | openfire |
| Password | (from your k8s secret) |
| Default Schema | openfire |

**Step 3 — Key tables to inspect:**

```sql
-- Offline messages currently stored
SELECT
    username,
    messageID,
    creationDate,
    messageSize,
    LEFT(stanza, 100) AS preview
FROM ofOffline
ORDER BY creationDate DESC;

-- Total offline storage per user
SELECT
    username,
    COUNT(*)        AS message_count,
    SUM(messageSize) / 1024 AS total_kb
FROM ofOffline
GROUP BY username;

-- Archived messages (Monitoring plugin)
SELECT
    fromJID,
    toJID,
    sentDate,
    LEFT(body, 100) AS preview
FROM ofMessageArchive
ORDER BY sentDate DESC
LIMIT 50;
```

---

## Offline Message Flow — Sequence

```
MEU (offline)          Openfire            MySQL
     |                     |                  |
     |   [disconnected]    |                  |
     |                     |                  |
     |              message arrives           |
     |                     |                  |
     |                     |-- INSERT ------->|
     |                     |   ofOffline      |
     |                     |                  |
     |   [comes online]    |                  |
     |-- connect() ------->|                  |
     |-- login()  -------->|                  |
     |                     |-- SELECT ------->|
     |                     |<-- messages -----|
     |<-- push messages ---|                  |
     |                     |-- DELETE ------->|
     |                     |   (delivered)    |
```

---

## Client Side — No Changes Needed

The Smack client automatically receives offline messages on login.
The same listener handles both:

```java
// This single listener catches BOTH live AND offline-delivered messages
muc.addMessageListener(message -> {
    String from = message.getFrom().getResourceOrEmpty().toString();
    String body = message.getBody();
    // handle message — no need to distinguish offline vs live
});

// Join with history to also catch messages sent while offline
DiscussionHistory history = new DiscussionHistory();
history.setSince(lastSeenTimestamp);   // only missed messages
muc.join(Resourcepart.from(nickname), null, history, timeout);
```

---

## Summary

| Item | Detail |
|------|--------|
| Offline storage mechanism | Openfire built-in store-and-forward |
| Storage table | `ofOffline` in MySQL |
| Per-user quota | 100 KB (configurable via `xmpp.offline.quota`) |
| Retention | 1 day |
| Calculated storage need | ~488 MB base, 1 GB with buffer |
| Recommended PVC | 5 GB (room for growth) |
| Client changes needed | None — Smack handles delivery automatically |
| Verify via | MySQL Workbench → `ofOffline` table |


```sql


SELECT * FROM ofProperty
WHERE name LIKE '%conversation%'
   OR name LIKE '%monitoring%'
   OR name LIKE '%archiv%';

SELECT name, propValue FROM ofProperty
WHERE name IN (
    'conversation.metadataArchiving',
    'conversation.messageArchiving'
);

INSERT IGNORE INTO ofProperty (name, propValue) VALUES
    ('conversation.metadataArchiving', 'true'),
    ('conversation.messageArchiving',  'true');


SELECT * FROM ofMessageArchive ORDER BY sentDate DESC LIMIT 10;

```
SELECT name, propValue FROM ofProperty
WHERE name IN (
'conversation.metadataArchiving',
'conversation.messageArchiving'
);



public List<Message> getMessagesByIds(String roomName,
String participantName,
List<String> targetMessageIds) throws Exception {

    MultiUserChat muc = joinedRooms.get(roomName + ":" + participantName);

    // Thread-safe list to collect found messages
    List<Message> foundMessages = new CopyOnWriteArrayList<>();
    CountDownLatch latch = new CountDownLatch(1);

    // Temporary listener — collects ALL history messages
    MessageListener tempListener = message -> {
        String stanzaId = message.getStanzaId();

        // Only collect messages whose ID is in our target list
        if (stanzaId != null && targetMessageIds.contains(stanzaId)) {
            foundMessages.add(message);
        }

        // Check if we found all messages we need
        if (foundMessages.size() == targetMessageIds.size()) {
            latch.countDown();   // ← got everything, stop waiting
        }
    };

    // ── Register listener BEFORE join ────────────────────────────────────
    muc.addMessageListener(tempListener);

    // ── Join with large history window ────────────────────────────────────
    MucEnterConfiguration config = muc
        .getEnterConfigurationBuilder(Resourcepart.from(participantName))
        .requestMaxStanzasHistory(500)
        .build();

    muc.join(config);

    try {
        // Wait max 10 seconds for all messages to arrive
        boolean completed = latch.await(10, TimeUnit.SECONDS);

        if (!completed) {
            log.warn("Only found [{}/{}] messages within timeout",
                foundMessages.size(), targetMessageIds.size());
        }

        return foundMessages;

    } finally {
        // Always remove temp listener
        muc.removeMessageListener(tempListener);
    }
}



public List<MessageResponse> readMessagesByParticipantAndRoom(
String roomName,
String participantName,
List<String> messageIds) {

    String roomKey = roomName + ":" + participantName;
    MultiUserChat muc = joinedRooms.get(roomKey);

    if (muc == null) {
        log.warn("Participant [{}] not in room [{}]", participantName, roomName);
        return Collections.emptyList();
    }

    List<Message> foundMessages = new CopyOnWriteArrayList<>();
    CountDownLatch latch = new CountDownLatch(1);

    MessageListener tempListener = message -> {
        String stanzaId = message.getStanzaId();

        if (stanzaId != null && messageIds.contains(stanzaId)) {
            foundMessages.add(message);
        }

        // Release latch when all found
        if (foundMessages.size() == messageIds.size()) {
            latch.countDown();
        }
    };

    // ── Register listener BEFORE join ─────────────────────────────────────
    muc.addMessageListener(tempListener);

    try {
        // ── Leave first if already joined ─────────────────────────────────
        if (muc.isJoined()) {
            muc.leave();
        }

        MucEnterConfiguration config = muc
            .getEnterConfigurationBuilder(Resourcepart.from(participantName))
            .requestMaxStanzasHistory(500)
            .build();

        muc.join(config);

        // Wait max 10 seconds
        boolean completed = latch.await(10, TimeUnit.SECONDS);

        if (!completed) {
            log.warn("Only found [{}/{}] messages within timeout",
                foundMessages.size(), messageIds.size());
        }

        // ── Map to response ───────────────────────────────────────────────
        return foundMessages.stream()
            .map(message -> MessageResponse.builder()
                .messageId(message.getStanzaId())
                .message(message.getBody())
                .sender(message.getFrom()
                    .getResourceOrEmpty().toString())   // ← fix sender
                .roomName(roomName)
                .build())
            .collect(Collectors.toList());

    } catch (Exception e) {
        log.error("Error reading messages for participant [{}] room [{}]",
            participantName, roomName, e);
        throw new RuntimeException(e);
    } finally {
        // ── Always remove temp listener ───────────────────────────────────
        muc.removeMessageListener(tempListener);

        // ── Rejoin without history to restore normal state ────────────────
        try {
            if (!muc.isJoined()) {
                MucEnterConfiguration rejoinConfig = muc
                    .getEnterConfigurationBuilder(
                        Resourcepart.from(participantName))
                    .requestNoHistory()
                    .build();
                muc.join(rejoinConfig);
            }
        } catch (Exception e) {
            log.error("Error rejoining room [{}] after read", roomName, e);
        }
    }
}
