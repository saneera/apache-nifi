# MUC REST API Plugin for Openfire 5.x

REST API plugin for MUC operations. Currently exposes a DELETE endpoint
to permanently remove a MUC message from `ofMessageArchive`.

Built using the **same parent POM and Jersey setup** as the official
[openfire-restAPI-plugin](https://github.com/igniterealtime/openfire-restAPI-plugin).

---

## Project Structure

```
muc-rest-api/
├── plugin.xml                                        ← Openfire plugin descriptor
├── pom.xml                                           ← Uses Openfire plugins parent
├── README.md
└── src/
    ├── java/com/babcock/openfire/plugin/rest/
    │   ├── MucRestApiPlugin.java                     ← Plugin entry point
    │   ├── ServiceException.java                     ← HTTP-aware exception
    │   ├── controller/
    │   │   └── DeleteMessageController.java          ← DB logic
    │   └── service/
    │       ├── JerseyWrapper.java                    ← Jersey 2.x bridge
    │       ├── AuthFilter.java                       ← Authorization header filter
    │       └── DeleteMessageService.java             ← @DELETE endpoint
    └── web/
        └── WEB-INF/
            └── web-custom.xml                        ← Servlet registration
```

---

## Prerequisites

- Java 11+
- Maven 3.8+
- Access to Ignite Realtime Archiva repository (for Openfire parent POM)

---

## Build

```bash
mvn clean package -DskipTests
```

Output: `target/muc-rest-api-openfire-plugin-assembly.jar`

Rename for Openfire:
```bash
cp target/muc-rest-api.jar \
   target/muc-rest-api.jar
```

---

## Install

### Dev — copy to running pod

```bash
kubectl cp target/muc-rest-api.jar \
    -n openfire \
    openfire/<pod>:/usr/local/openfire/plugins/muc-rest-api.jar
```

### Production — Docker image

```dockerfile
COPY plugins/muc-rest-api.jar /usr/local/openfire/plugins_org/muc-rest-api.jar
```

---

## Authentication

Uses the same secret as the REST API plugin (`plugin.restapi.secret` in ofProperty).

---

## Endpoints

### Delete message

```
DELETE http://{host}:9090/plugins/muc-rest-api/messages/{messageId}
Authorization: yourSecret
```

**Success (200):**
```json
{"success":true,"messageId":"C5RT6-18","room":"room1@conference.example.com"}
```

**Not found (404):**
```json
{"error":"Message not found: C5RT6-18","messageId":"C5RT6-18"}
```

**Unauthorized (401):**
```json
{"error":"Unauthorized"}
```

---

## curl

```bash
curl -X DELETE \
  "http://localhost:9090/plugins/muc-rest-api/messages/C5RT6-18" \
  -H "Authorization: yourSecret" \
  -v
```

---

## Adding more endpoints

Register in `JerseyWrapper.java`:

```java
config.register(DeleteMessageService.class);
config.register(YourNewService.class);   // ← add here
```

Add servlet mapping in `web-custom.xml` if needed.
