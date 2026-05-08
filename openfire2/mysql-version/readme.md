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
