# MQTT Message Flow Diagrams

## Device Discovery Flow

```mermaid
sequenceDiagram
    participant D1 as Device 1
    participant B as MQTT Broker
    participant D2 as Device 2
    participant D3 as Device 3
    
    Note over D1,D3: Device Discovery Phase
    
    D1->>B: CONNECT (clientId: klardrop_device1_uuid)
    B-->>D1: CONNACK
    
    D1->>B: SUBSCRIBE klardrop/discovery/announce/+
    D1->>B: SUBSCRIBE klardrop/presence/+
    
    D2->>B: PUBLISH klardrop/presence/device2 (QoS 0, Retained)
    B-->>D1: Deliver presence update
    
    D1->>B: PUBLISH klardrop/discovery/announce/device1 (QoS 1)
    B-->>D2: Deliver announcement
    B-->>D3: Deliver announcement
    
    Note over D1: Periodic presence updates
    loop Every 30 seconds
        D1->>B: PUBLISH klardrop/presence/device1 (QoS 0)
    end
```

## File Transfer Negotiation Flow

```mermaid
sequenceDiagram
    participant S as Sender Device
    participant B as MQTT Broker
    participant R as Receiver Device
    
    Note over S,R: Transfer Negotiation Phase
    
    S->>B: PUBLISH klardrop/transfer/request/sender/receiver/transfer123 (QoS 2)
    Note right of S: CloudTransferRequest {<br/>files: [{id: 1, name: "photo.jpg", size: 2MB}],<br/>options: {chunk_size: 64KB}<br/>}
    
    B-->>R: Deliver transfer request
    
    R->>R: Check available space<br/>Validate file types
    
    R->>B: PUBLISH klardrop/transfer/response/receiver/sender/transfer123 (QoS 2)
    Note left of R: CloudTransferResponse {<br/>status: ACCEPTED,<br/>accepted_file_ids: [1]<br/>}
    
    B-->>S: Deliver transfer response
    
    S->>B: PUBLISH klardrop/transfer/metadata/sender/receiver/transfer123 (QoS 2)
    B-->>R: Deliver file metadata
```

## Chunked File Transfer Flow

```mermaid
sequenceDiagram
    participant S as Sender
    participant B as MQTT Broker
    participant R as Receiver
    
    Note over S,R: File Transfer Phase (Parallel Chunks)
    
    par Chunk 0
        S->>B: PUBLISH .../chunks/.../0 (QoS 1)
        B-->>R: Deliver chunk 0
    and Chunk 1
        S->>B: PUBLISH .../chunks/.../1 (QoS 1)
        B-->>R: Deliver chunk 1
    and Chunk 2
        S->>B: PUBLISH .../chunks/.../2 (QoS 1)
        B-->>R: Deliver chunk 2
    and Chunk 3
        S->>B: PUBLISH .../chunks/.../3 (QoS 1)
        B-->>R: Deliver chunk 3
    end
    
    R->>R: Verify checksums<br/>Write to disk
    
    R->>B: PUBLISH klardrop/transfer/progress/... (QoS 0)
    B-->>S: Deliver progress update
    
    Note over S,R: Continue with remaining chunks...
    
    S->>B: PUBLISH klardrop/transfer/complete/... (QoS 2)
    B-->>R: Deliver completion notification
```

## Error Handling and Recovery Flow

```mermaid
sequenceDiagram
    participant S as Sender
    participant B as MQTT Broker
    participant R as Receiver
    
    Note over S,R: Error Recovery Scenario
    
    S->>B: PUBLISH chunk 10 (QoS 1)
    B-->>R: Deliver chunk 10
    
    S->>B: PUBLISH chunk 11 (QoS 1)
    Note over B: Network issue
    B--xR: Failed delivery
    
    S->>B: PUBLISH chunk 12 (QoS 1)
    B-->>R: Deliver chunk 12
    
    R->>R: Detect missing chunk 11
    
    R->>B: PUBLISH klardrop/transfer/ack/... (QoS 1)
    Note left of R: ChunkAcknowledgment {<br/>received: [0-10, 12],<br/>missing: [11]<br/>}
    
    B-->>S: Deliver acknowledgment
    
    S->>B: PUBLISH chunk 11 (QoS 1) [Retry]
    B-->>R: Deliver chunk 11
    
    R->>B: PUBLISH progress update
    B-->>S: Deliver progress
```

## Transfer Control Flow

```mermaid
sequenceDiagram
    participant U as User
    participant S as Sender
    participant B as MQTT Broker
    participant R as Receiver
    
    Note over U,R: Transfer Control Operations
    
    U->>S: Pause transfer
    S->>B: PUBLISH klardrop/control/pause/transfer123 (QoS 2)
    B-->>R: Deliver pause command
    
    R->>R: Pause receiving<br/>Save state
    
    Note over S,R: Transfer paused
    
    U->>S: Resume transfer
    S->>B: PUBLISH klardrop/control/resume/transfer123 (QoS 2)
    B-->>R: Deliver resume command
    
    R->>B: PUBLISH klardrop/transfer/ack/... (QoS 1)
    Note left of R: Request missing chunks
    
    B-->>S: Deliver acknowledgment
    
    S->>S: Resume from last<br/>acknowledged chunk
    
    Note over S,R: Transfer continues...
    
    alt User cancels
        U->>S: Cancel transfer
        S->>B: PUBLISH klardrop/control/cancel/transfer123 (QoS 2)
        B-->>R: Deliver cancel command
        R->>R: Clean up partial files
    end
```

## Multi-Device Broadcast Flow

```mermaid
sequenceDiagram
    participant S as Sender
    participant B as MQTT Broker
    participant R1 as Receiver 1
    participant R2 as Receiver 2
    participant R3 as Receiver 3
    
    Note over S,R3: Broadcast to Multiple Devices
    
    S->>B: PUBLISH klardrop/transfer/broadcast/sender/transfer456 (QoS 1)
    Note right of S: Broadcast request with<br/>target device list
    
    par To Receiver 1
        B-->>R1: Deliver broadcast
        R1->>B: PUBLISH response (ACCEPT)
    and To Receiver 2
        B-->>R2: Deliver broadcast
        R2->>B: PUBLISH response (ACCEPT)
    and To Receiver 3
        B-->>R3: Deliver broadcast
        R3->>B: PUBLISH response (REJECT - No space)
    end
    
    B-->>S: Deliver all responses
    
    S->>S: Track accepted receivers
    
    Note over S,R2: Send to accepted devices only
    
    par Send to R1
        S->>B: PUBLISH chunks for R1
        B-->>R1: Deliver chunks
    and Send to R2
        S->>B: PUBLISH chunks for R2
        B-->>R2: Deliver chunks
    end
```

## Connection State Management

```mermaid
stateDiagram-v2
    [*] --> Disconnected
    
    Disconnected --> Connecting: connect()
    Connecting --> Connected: CONNACK received
    Connecting --> Error: Connection failed
    
    Connected --> Disconnected: disconnect()
    Connected --> Reconnecting: Connection lost
    
    Reconnecting --> Connected: Reconnection successful
    Reconnecting --> Error: Max retries exceeded
    
    Error --> Connecting: retry()
    Error --> Disconnected: give up
    
    Connected --> Connected: Publish/Subscribe
    
    state Connected {
        [*] --> Idle
        Idle --> Publishing: publish()
        Publishing --> Idle: Published
        
        Idle --> Transferring: File transfer
        Transferring --> Idle: Complete
        Transferring --> Paused: pause()
        Paused --> Transferring: resume()
        Paused --> Idle: cancel()
    }
```

## Topic Hierarchy Visualization

```mermaid
graph TD
    A[klardrop/] --> B[presence/]
    A --> C[discovery/]
    A --> D[transfer/]
    A --> E[control/]
    A --> F[system/]
    
    B --> B1["{device_id}"]
    
    C --> C1[announce/]
    C --> C2[query/]
    C1 --> C11["{device_id}"]
    C2 --> C21["{query_id}"]
    
    D --> D1[request/]
    D --> D2[response/]
    D --> D3[metadata/]
    D --> D4[chunks/]
    D --> D5[progress/]
    D --> D6[ack/]
    
    D1 --> D11["{sender_id}/{receiver_id}/{transfer_id}"]
    D2 --> D21["{receiver_id}/{sender_id}/{transfer_id}"]
    D3 --> D31["{sender_id}/{receiver_id}/{transfer_id}"]
    D4 --> D41["{sender_id}/{receiver_id}/{transfer_id}/{chunk_id}"]
    
    E --> E1[pause/]
    E --> E2[resume/]
    E --> E3[cancel/]
    E1 --> E11["{transfer_id}"]
    E2 --> E21["{transfer_id}"]
    E3 --> E31["{transfer_id}"]
    
    F --> F1[stats/]
    F --> F2[errors/]
    F1 --> F11["{device_id}"]
    F2 --> F21["{device_id}"]
```

## Performance Optimization Flow

```mermaid
sequenceDiagram
    participant S as Sender
    participant B as MQTT Broker
    participant R as Receiver
    
    Note over S,R: Optimized Transfer with Compression & Parallel Chunks
    
    S->>S: Analyze file type<br/>Enable compression
    
    S->>B: PUBLISH transfer request<br/>options: {compress: true, parallel: 4}
    B-->>R: Deliver request
    
    R->>B: PUBLISH response (ACCEPT)
    B-->>S: Deliver response
    
    Note over S: Compress and split file
    
    par Compressed Chunk Stream 1
        loop Chunks 0, 4, 8...
            S->>S: Compress chunk
            S->>B: PUBLISH compressed chunk
            B-->>R: Deliver chunk
            R->>R: Decompress & write
        end
    and Compressed Chunk Stream 2
        loop Chunks 1, 5, 9...
            S->>S: Compress chunk
            S->>B: PUBLISH compressed chunk
            B-->>R: Deliver chunk
            R->>R: Decompress & write
        end
    and Progress Updates
        loop Every second
            R->>B: PUBLISH progress (QoS 0)
            B-->>S: Deliver progress
            S->>S: Adjust chunk size<br/>based on speed
        end
    end
```

## Notes on Message Flow

### QoS Level Usage
- **QoS 0** (At most once): Used for presence updates and progress reports where occasional loss is acceptable
- **QoS 1** (At least once): Used for file chunks where retransmission is handled at application level
- **QoS 2** (Exactly once): Used for critical control messages like transfer requests/responses

### Optimization Strategies
1. **Parallel Chunk Transmission**: Multiple chunks sent simultaneously to maximize bandwidth
2. **Adaptive Chunk Size**: Adjust based on network conditions
3. **Compression**: Automatic for text/compressible files
4. **Topic Aliases**: Reduce overhead for frequently used topics
5. **Shared Subscriptions**: Load balance across multiple client instances

### Error Recovery
- Automatic reconnection with exponential backoff
- Chunk-level acknowledgments for reliable transfer
- Resume capability from last successful chunk
- Timeout handling for stale transfers