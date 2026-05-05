# Chat Traffic Gateway – Commands & Properties Flow 

## Overview

This service manages:
 - Rooms → synced with Openfire + Property Service
 - Participants → managed ONLY via Property Service (no Openfire calls)



## Architecture Summary

```mermaid
flowchart LR
    A[Client / API] --> B[Command Handler]
    B --> C[Chat Service]
    C --> D[Openfire Server]
    C --> E[Property Service -Redis]
```


### Command Responsibilities

|| Command || Openfire || Property Service ||
| create-room | Yes | Yes |
| delete-room | Yes | Yes |
| add-participant | No | Yes |
| delete-participant | No | Yes |
| rooms (GET) | Yes | Yes |
| participants (GET) | No | Yes |


### 1. Create Room

```mermaid
sequenceDiagram
    participant Client
    participant Command
    participant ChatService
    participant Openfire
    participant PropertyService

    Client->>Command: create-room(roomName, description)
    Command->>ChatService: createAndGetRoom()
    ChatService->>Openfire: create MUC room
    Openfire-->>ChatService: RoomInfo
    ChatService->>PropertyService: update rooms list
    Command-->>Client: RoomCreatedResponse
```

Key Points
•	Validates:
•	roomName
•	description
•	Prevents duplicate rooms
•	Syncs result into Property Service


### 2. Delete Room

Flow

```mermaid
sequenceDiagram
    participant Client
    participant Command
    participant ChatService
    participant Openfire
    participant PropertyService

    Client->>Command: delete-room(roomName)
    Command->>ChatService: validateRoomExists()
    ChatService->>Openfire: destroy room
    Openfire-->>ChatService: success
    ChatService->>PropertyService: update rooms list
    Command-->>Client: RoomDeletedResponse
```


Key Points
•	Room existence validated first
•	Openfire is the source of truth
•	Property Service updated after deletion


### 3. Add Participant


#### Flow 

```mermaid
sequenceDiagram
    participant Client
    participant Command
    participant ChatService
    participant PropertyService

    Client->>Command: add-participant(name)
    Command->>ChatService: getAllParticipants()
    ChatService->>PropertyService: fetch participants
    Command->>Command: validate not exists
    Command->>ChatService: updateParticipants()
    ChatService->>PropertyService: save updated list
    Command-->>Client: ParticipantCreatedResponse
```


Key Points
 - Stored as:

```json
["user1", "user2"]
```

 - No Openfire membership
 - Duplicate check enforced


### 4. Delete Participant

#### Flow 

```mermaid
sequenceDiagram
    participant Client
    participant Command
    participant ChatService
    participant PropertyService

    Client->>Command: delete-participant(name)
    Command->>ChatService: getAllParticipants()
    ChatService->>PropertyService: fetch participants
    Command->>Command: validate exists
    Command->>ChatService: updateParticipants()
    ChatService->>PropertyService: save updated list
    Command-->>Client: ParticipantDeletedResponse

```


Key Points
 - Throws error if user not found
 - Pure Property Service operation


### 5. Get Rooms
#### Flow

```mermaid
sequenceDiagram
    participant Client
    participant Command
    participant ChatService
    participant Openfire
    participant PropertyService

    Client->>Command: GET rooms
    Command->>ChatService: listRoomFromServer()
    ChatService->>Openfire: fetch rooms
    Openfire-->>ChatService: room list
    ChatService->>PropertyService: (optional sync)
    Command-->>Client: RoomsResponse

```


### 6. Get Participants

#### Flow 

```mermaid
sequenceDiagram
    participant Client
    participant Command
    participant PropertyService

    Client->>Command: GET participants
    Command->>PropertyService: getPropertyValue("participants")
    PropertyService-->>Command: List<String>
    Command-->>Client: ParticipantResponse

```


### Data Models

#### Participants

```json
{
  "participants": [
    "Saneera",
    "John"
  ]
}
```

### Rooms

```json
{
  "rooms": [
    {
      "roomName": "Test1",
      "description": "Room description"
    }
  ]
}
```


### Validation Rules

### Room
 - name cannot be null/empty
 - description cannot be null/empty
 - must not already exist

### Participant
 - name cannot be null/empty
 - must not already exist (add)
 - must exist (delete) 


### Final Architecture Insight


```mermaid
flowchart TD
    A[Rooms] -->|Synced| B(Openfire)
    A -->|Stored| C(Property Service)

    D[Participants] -->|Stored ONLY| C
```

### Summary
 -	Rooms → Openfire + Property Service
 - Participants → Property Service ONLY
 - Commands are clearly separated
 - No unnecessary XMPP calls
