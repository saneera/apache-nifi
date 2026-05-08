```mermaid
sequenceDiagram
    participant Client
    participant AddParticipantCommand
    participant PropertyHandler
    participant ParticipantsCache
    participant PropertyService
    participant PropServer

    Client->>AddParticipantCommand: executeCommand("add-participant", commandParams)

    AddParticipantCommand->>AddParticipantCommand: Validate participantName

    alt participantName is empty
        AddParticipantCommand-->>Client: IllegalArgumentException
    end

    AddParticipantCommand->>PropertyHandler: getPropertyHandler(PARTICIPANTS)

    PropertyHandler-->>AddParticipantCommand: ParticipantsHandler

    AddParticipantCommand->>ParticipantsCache: getPropertyValueFromCache("participants")

    ParticipantsCache-->>AddParticipantCommand: Existing participants list

    AddParticipantCommand->>AddParticipantCommand: Check duplicate participant

    alt participant already exists
        AddParticipantCommand-->>Client: IllegalArgumentException("Participant already exists")
    end

    AddParticipantCommand->>AddParticipantCommand: Add new Participant(participantName)

    AddParticipantCommand->>PropertyService: sendObjectToPropService("participants", participantsList)

    PropertyService->>PropServer: Persist updated participants

    PropServer-->>PropertyService: Success

    PropertyService-->>AddParticipantCommand: Success

    AddParticipantCommand-->>Client: ChatAssetResponse(ParticipantResponse)
```


```mermaid
sequenceDiagram
    participant Client
    participant DeleteParticipantCommand
    participant PropertyHandler
    participant ParticipantsCache
    participant PropertyService
    participant PropServer

    Client->>DeleteParticipantCommand: executeCommand("delete-participant", commandParams)

    DeleteParticipantCommand->>DeleteParticipantCommand: Validate participantName

    alt participantName is empty
        DeleteParticipantCommand-->>Client: IllegalArgumentException
    end

    DeleteParticipantCommand->>PropertyHandler: getPropertyHandler(PARTICIPANTS)

    PropertyHandler-->>DeleteParticipantCommand: ParticipantsHandler

    DeleteParticipantCommand->>ParticipantsCache: getPropertyValueFromCache("participants")

    ParticipantsCache-->>DeleteParticipantCommand: Existing participants list

    DeleteParticipantCommand->>DeleteParticipantCommand: Validate participant exists

    alt participant does not exist
        DeleteParticipantCommand-->>Client: IllegalArgumentException("Participant does not exist")
    end

    DeleteParticipantCommand->>DeleteParticipantCommand: removeIf(participantName matches)

    DeleteParticipantCommand->>PropertyService: sendObjectToPropService("participants", updatedParticipants)

    PropertyService->>PropServer: Persist updated participants list

    PropServer-->>PropertyService: Success

    PropertyService-->>DeleteParticipantCommand: Success

    DeleteParticipantCommand-->>Client: ChatAssetResponse(DeletedResponse)
```


In XMPP, a participant has a specific meaning — it refers to a user who has joined a room. Participants are therefore tightly coupled to room membership.

As such, XMPP does not define the concept of “global participants” outside the context of rooms.

To support this requirement, we can maintain a global participant list at the service level by persisting it in the Prop Server database and using it when managing room participation.



`GET /participants`

When a request (`GET /participants`) is sent from the HTTP client, the `propsDelegate` acts as the entry point.  
The request is then forwarded to the Chat Traffic Gateway Service (CTG), which retrieves the participant list from the Property Service cache/store.

If the participant list is not available in the cache, the CTG fetches the latest value from the Property Service and persists it locally for future requests.

The response is then returned back through:

Property Service -> CTG -> propsDelegate -> Client

![get_participants.pnq](...)


`PUT /add-participant`

When a request (`PUT /add-participant`) is sent from the HTTP client, the `cmdDelegate` acts as the entry point and forwards the request to the Chat Traffic Gateway Service (CTG).

The CTG validates the participant name and retrieves the existing participant list from the Property Service cache/store.

If the participant already exists, the request is rejected with a validation error.

Otherwise, the participant is added to the global participant list, and the updated list is persisted to the Property Service database.

The updated participant state is then returned back through:

Property Service -> CTG -> cmdDelegate -> Client

![add_participant.pnq](...)


`DELETE /delete-participant`

When a request (`DELETE /delete-participant`) is sent from the HTTP client, the `cmdDelegate` acts as the entry point and forwards the request to the Chat Traffic Gateway Service (CTG).

The CTG validates the participant name and retrieves the existing participant list from the Property Service cache/store.

If the participant does not exist, the request is rejected with a validation error.

Otherwise, the participant is removed from the global participant list, and the updated list is persisted to the Property Service database.

The updated participant state is then returned back through:

Property Service -> CTG -> cmdDelegate -> Client

![delete_participant.pnq](...)
