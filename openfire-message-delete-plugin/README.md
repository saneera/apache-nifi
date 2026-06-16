# Message Delete Plugin — Openfire Custom Plugin

Exposes a REST endpoint to delete a MUC message from `ofMessageArchive` by `messageId`.

---

## Directory Structure

```
openfire-message-delete-plugin/
├── pom.xml
└── src/main/
    ├── java/com/babcock/openfire/plugin/
    │   ├── MessageDeletePlugin.java         ← Openfire plugin entry point
    │   └── rest/
    │       ├── MessageDeleteApplication.java ← Jersey app
    │       └── MessageDeleteResource.java    ← REST endpoints
    └── webapp/
        ├── plugin.xml                        ← Openfire plugin descriptor
        └── WEB-INF/
            └── web.xml                       ← Jersey servlet config
```

---

## Build

```bash
mvn clean package
```

Output: `target/message-delete.jar`

---

## Install

Copy the JAR into your Openfire plugins directory:

```bash
# Via kubectl for Kubernetes
kubectl cp target/message-delete.jar \
    openfire/<pod-name>:/usr/local/openfire/plugins/message-delete.jar

# Or add to your Docker image
COPY plugins/message-delete.jar /usr/local/openfire/plugins_org/message-delete.jar
```

Openfire auto-deploys plugins — no restart needed.

---

## Endpoints

### Delete message by ID

```
DELETE http://<node-ip>:9090/plugins/message-delete/api/v1/messages/{messageId}
Authorization: <REST_API_SECRET>
```

Response:
```json
{
    "success": true,
    "messageId": "NJ5E1-13",
    "room": "room1@conference.example.com"
}
```

### Delete message scoped to room (safer)

```
DELETE http://<node-ip>:9090/plugins/message-delete/api/v1/messages/{messageId}/room/{roomJid}
Authorization: <REST_API_SECRET>
```

---

## Error responses

| Status | Meaning |
|--------|---------|
| 200 | Message deleted successfully |
| 400 | messageId missing |
| 401 | Authorization header missing or wrong secret |
| 404 | Message not found in ofMessageArchive |
| 500 | Server error |

---

## Authentication

Uses the same secret as your REST API plugin.
Set in Openfire Admin Console → Server Settings → REST API → Secret.

Pass it in the header:
```
Authorization: yourSecret
```
or
```
Authorization: Bearer yourSecret
```

---

## Call from Spring Boot service

```java
// In your deleteMessageFromRoom — after Smack retraction
RestTemplate restTemplate = new RestTemplate();

HttpHeaders headers = new HttpHeaders();
headers.set("Authorization", restApiSecret);

HttpEntity<Void> request = new HttpEntity<>(headers);

ResponseEntity<String> response = restTemplate.exchange(
    "http://{nodeIp}:{port}/plugins/message-delete/api/v1/messages/{messageId}/room/{roomJid}",
    HttpMethod.DELETE,
    request,
    String.class,
    nodeIp, port, messageId, roomJid
);

log.info("Message deleted from DB: {}", response.getBody());
```
