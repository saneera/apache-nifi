# MUC Message Delete Plugin for Openfire 5.x

Openfire plugin that exposes a REST endpoint to permanently delete a MUC
message from `ofMessageArchive` by its `messageId`.

Follows the same build pattern and Jersey 2.x approach as the official
[openfire-restAPI-plugin](https://github.com/igniterealtime/openfire-restAPI-plugin).

---

## Project Structure

```
muc-message-delete/
├── plugin.xml                                    ← Openfire plugin descriptor (at root)
├── pom.xml                                       ← Standalone Maven build
├── README.md
├── src/
│   ├── assembly/
│   │   └── plugin-assembly.xml                   ← Controls JAR structure
│   ├── java/com/babcock/openfire/plugin/rest/
│   │   ├── MucMessageDeletePlugin.java           ← Plugin entry point
│   │   ├── ServiceException.java                 ← HTTP-aware exception
│   │   ├── controller/
│   │   │   └── DeleteMessageController.java      ← DB logic
│   │   └── service/
│   │       ├── JerseyWrapper.java                ← Jersey 2.x bridge servlet
│   │       ├── AuthFilter.java                   ← Authorization header filter
│   │       └── DeleteMessageService.java         ← JAX-RS @DELETE endpoint
│   └── web/
│       └── WEB-INF/
│           └── web-custom.xml                    ← Servlet registration
```

---

## Prerequisites

- Java 11+
- Maven 3.8+
- Access to Maven Central (for Jersey/Jackson)
- Access to igniterealtime Archiva (for Openfire xmppserver artifact)

---

## Build

```bash
mvn clean package -DskipTests
```

Output files in `target/`:

```
target/
├── muc-message-delete.jar                        ← THE plugin JAR (use this)
└── lib/
    └── muc-message-delete.jar                    ← classes only (intermediate)
```

> **Note:** The output is `muc-message-delete.jar` (not `...-openfire-plugin-assembly.jar`)
> because `appendAssemblyId=false` is set in the assembly plugin config.

---

## Install

### Option A — Copy to running Kubernetes pod (dev/test)

```bash
kubectl cp target/muc-message-delete.jar \
    -n openfire \
    openfire/<pod-name>:/usr/local/openfire/plugins/muc-message-delete.jar
```

Openfire auto-deploys — check logs:
```bash
kubectl -n openfire logs -f deployment/openfire | grep muc-message-delete
```

Expected log:
```
MucMessageDeletePlugin initialised. REST endpoint: DELETE /plugins/muc-message-delete/messages/{messageId}
```

### Option B — Bake into Docker image (production)

```dockerfile
COPY plugins/muc-message-delete.jar /usr/local/openfire/plugins_org/muc-message-delete.jar
```

---

## Authentication

Uses the same secret as the REST API plugin — stored in `ofProperty`:

| Property | Value |
|----------|-------|
| `plugin.restapi.secret` | your secret string |

Set via Openfire Admin Console → Server Settings → REST API → Secret Key.

Or directly via SQL:
```sql
INSERT IGNORE INTO ofProperty (name, propValue)
VALUES ('plugin.restapi.secret', 'yourSecret');
```

---

## API

### Delete a message

```
DELETE http://{host}:{port}/plugins/muc-message-delete/messages/{messageId}
Authorization: yourSecret
```

#### Path parameter

| Param | Description |
|-------|-------------|
| `messageId` | The Openfire-assigned stanza ID stored in `ofMessageArchive.messageID` |

#### Responses

| Status | Body |
|--------|------|
| `200 OK` | `{"success":true,"messageId":"...","room":"room1@conference.example.com"}` |
| `401 Unauthorized` | `{"error":"Unauthorized — ..."}` |
| `404 Not Found` | `{"error":"Message not found in ofMessageArchive: ..."}` |
| `500 Server Error` | `{"error":"Internal server error: ..."}` |

---

## Example — call from Spring Boot service

```java
@Service
public class MucMessageDeleteClient {

    @Value("${openfire.host}")
    private String host;

    @Value("${openfire.admin.port:9090}")
    private int port;

    @Value("${openfire.rest.secret}")
    private String secret;

    private final RestTemplate restTemplate = new RestTemplate();

    public void deleteMessage(String messageId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", secret);

        ResponseEntity<String> response = restTemplate.exchange(
            "http://{host}:{port}/plugins/muc-message-delete/messages/{messageId}",
            HttpMethod.DELETE,
            new HttpEntity<>(headers),
            String.class,
            host, port, messageId
        );

        log.info("Delete response: {}", response.getBody());
    }
}
```

---

## How it works

```
Spring Boot calls DELETE /plugins/muc-message-delete/messages/{messageId}
        ↓
Openfire PluginServlet routes to JerseyWrapper (registered in web-custom.xml)
        ↓
AuthFilter validates Authorization header against plugin.restapi.secret
        ↓
DeleteMessageService.deleteMessage() called
        ↓
DeleteMessageController queries ofMessageArchive — checks message exists
        ↓
DELETE FROM ofMessageArchive WHERE messageID = ?
        ↓
Returns 200 OK with success JSON
```

---

## Why this approach

Openfire does not update `ofMessageArchive` when a retraction stanza is
received — the original message row remains. This plugin provides a
direct DELETE to ensure the message is truly gone from the archive and
will not appear in history replay or MAM queries.
