# Klardrop

Klardrop is a Kotlin Multiplatform project for cross-platform file sharing and device discovery. It implements nearby sharing functionality similar to AirDrop, supporting Android, iOS, macOS, and desktop platforms.

## Klardrop Protocol

Klardrop uses a custom TCP socket-based protocol with length-prefixed messages and automatic acknowledgments for reliable message delivery.

### Protocol Overview

The protocol operates over raw TCP sockets using a unified server that can handle both Klardrop and Nearby Share protocols automatically.

### Message Format

All messages follow this structure:
```
[4-byte length (big-endian)][1-byte message type][protobuf payload]
```

### Message Types

| Type | ID | Description | Has Payload |
|------|----|-----------|----|
| `HANDSHAKE` | 0 | Device identification during connection setup | No |
| `TEXT` | 1 | Text message sharing | No |
| `FILE` | 2 | File metadata and transfer | Yes |
| `ACK_READY` | 3 | Acknowledgment: ready to receive payload | No |
| `ACK_RECEIVED` | 4 | Acknowledgment: message/payload received | No |

### Connection Establishment Flow

1. **Client Initiates Connection**
   - Method: `Client.connectTo(deviceId)` → `ClientImpl.connectTo()`
   - Creates TCP socket connection to discovered device

2. **Handshake Exchange**
   - Client sends: `HandshakeMessage(deviceId)` 
   - Server responds: `HandshakeMessage(serverDeviceId)`
   - Method: `UnifiedServer.handleKlardropConnection()`

3. **Connection Pool Management**
   - Server: `ConnectionsPool.updateConnection()` stores new connection
   - Client: Connection stored in client's connection pool
   - Both sides start listening: `ConnectionMessenger.acceptIncomingMessages()`

### Message Flow Patterns

#### No-Payload Messages (TextMessage)

```
Client                          Server
   |                              |
   |  1. TextMessage(id: 123)     |
   |---------------------------->|
   |     sendMessage()            |  2. Process message
   |                              |     TextMessageHandler.handleIncoming()
   |                              |
   |  3. ACK_RECEIVED(id: 123)    |
   |<----------------------------|
   |     MessagesRouter sends ACK |
```

**Methods involved:**
- `MessagesRouter.onSendingMessage()` - Client sends message
- `MessagesRouter.onMessageIncoming()` - Server receives and processes
- `TextMessageHandler.handleIncoming()` - Server processes text
- `writeChannel.sendMessage()` - Server sends ACK

#### Payload Messages (FileMessage)

```
Client                          Server
   |                              |
   |  1. FileMessage(id: 456)     |
   |---------------------------->|  2. Validate file metadata
   |     sendMessage()            |     FileMessageHandler.handleIncoming()
   |                              |
   |  3. ACK_READY(id: 456)       |
   |<----------------------------|
   |     Server ready for payload |
   |                              |
   |  4. [File data chunks]       |
   |============================>|  5. Receive and save payload
   |     32KB chunks              |     FileMessageHandler processes
   |                              |
   |  6. ACK_RECEIVED(id: 456)    |
   |<----------------------------|
   |     Transfer complete        |
```

**Methods involved:**
- `FileMessageHandler.handleOutgoing()` - Client sends file metadata + payload
- `MessagesRouter.onMessageIncoming()` - Server receives file header
- `FileMessageHandler.handleIncoming()` - Server processes file + payload
- Server automatically sends `ACK_READY` then `ACK_RECEIVED`

### Error Handling & Reliability

#### Current Implementation (Server-Side ACKs)
- Server automatically sends acknowledgments for all received messages
- ACKs include original message ID for correlation
- Method: `MessagesRouter.onMessageIncoming()` handles ACK generation

#### Planned Enhancement (Client-Side Timeout & Retry)
- Client waits for ACKs with configurable timeouts
- Connection drop detection and automatic reconnection
- Retry logic with exponential backoff
- Method: Enhanced `ConnectionMessenger.send()` with ACK waiting

### Device Discovery

Uses mDNS for local network device discovery:
- **Service Advertisement**: `DiscoveryModule` broadcasts Klardrop service
- **Service Discovery**: `ServiceDiscoveryMdns` finds nearby devices  
- **Device Visibility**: `VisibleDevices` manages discovered device list

### Key Components

- **`UnifiedServer`**: Single server handling multiple protocols
- **`ConnectionMessenger`**: Manages individual socket connections
- **`MessagesRouter`**: Routes messages to appropriate handlers
- **`MessageSerializer`**: Handles Protocol Buffer serialization
- **`ConnectionsPool`**: Manages active connections per device

### Testing

Test UDP discovery:

Receive:
```shell
socat - UDP-RECV:2121
```

Send:
```shell
socat - UDP-DATAGRAM:255.255.255.255:2121,broadcast
```