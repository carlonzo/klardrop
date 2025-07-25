# Klardrop Cloud Backend Architecture

## Overview

The Klardrop cloud backend provides a scalable, cloud-native solution for extending the local file sharing capabilities to work across networks using MQTT as the transport protocol. This architecture enables devices to discover and share files globally while maintaining the simplicity of the existing Klardrop protocol.

## Architecture Decision: Microservices

We recommend a **microservices architecture** for the following reasons:

1. **Scalability**: Individual services can scale independently based on load
2. **Technology flexibility**: Different services can use optimal tech stacks
3. **Fault isolation**: Service failures don't cascade to the entire system
4. **Team independence**: Teams can work on services independently
5. **Cloud-native**: Better suited for container orchestration platforms

## Technology Stack

### Core Technologies
- **Language**: Kotlin
- **Framework**: Ktor (lightweight, coroutine-native, excellent for microservices)
- **MQTT Broker**: Eclipse Mosquitto (containerized) or managed cloud MQTT services
- **Database**: PostgreSQL for relational data, Redis for caching
- **File Storage**: MinIO (S3-compatible) or cloud storage (AWS S3, GCS, Azure Blob)
- **Message Queue**: Apache Kafka for inter-service communication
- **Container**: Docker with multi-stage builds
- **Orchestration**: Kubernetes
- **API Gateway**: Kong or cloud-native solutions
- **Monitoring**: Prometheus + Grafana
- **Logging**: ELK Stack (Elasticsearch, Logstash, Kibana)
- **Tracing**: Jaeger

### Key Kotlin Libraries
```toml
[versions]
ktor = "3.2.1"
exposed = "0.60.0"
hikari = "6.2.1"
paho-mqtt = "1.2.5"
kafka = "3.10.0"
minio = "8.6.6"
micrometer = "1.15.2"
logback = "1.6.2"
testcontainers = "1.20.3"

[libraries]
# Ktor
ktor-server-core = { module = "io.ktor:ktor-server-core", version.ref = "ktor" }
ktor-server-netty = { module = "io.ktor:ktor-server-netty", version.ref = "ktor" }
ktor-server-content-negotiation = { module = "io.ktor:ktor-server-content-negotiation", version.ref = "ktor" }
ktor-serialization-kotlinx-json = { module = "io.ktor:ktor-serialization-kotlinx-json", version.ref = "ktor" }
ktor-server-auth = { module = "io.ktor:ktor-server-auth", version.ref = "ktor" }
ktor-server-auth-jwt = { module = "io.ktor:ktor-server-auth-jwt", version.ref = "ktor" }
ktor-server-metrics-micrometer = { module = "io.ktor:ktor-server-metrics-micrometer", version.ref = "ktor" }

# Database
exposed-core = { module = "org.jetbrains.exposed:exposed-core", version.ref = "exposed" }
exposed-dao = { module = "org.jetbrains.exposed:exposed-dao", version.ref = "exposed" }
exposed-jdbc = { module = "org.jetbrains.exposed:exposed-jdbc", version.ref = "exposed" }
hikaricp = { module = "com.zaxxer:HikariCP", version.ref = "hikari" }
postgresql = { module = "org.postgresql:postgresql", version = "42.8.0" }

# MQTT
paho-mqtt = { module = "org.eclipse.paho:org.eclipse.paho.client.mqttv3", version.ref = "paho-mqtt" }

# Messaging
kafka-clients = { module = "org.apache.kafka:kafka-clients", version.ref = "kafka" }

# Storage
minio = { module = "io.minio:minio", version.ref = "minio" }

# Monitoring
micrometer-registry-prometheus = { module = "io.micrometer:micrometer-registry-prometheus", version.ref = "micrometer" }

# Testing
testcontainers = { module = "org.testcontainers:testcontainers", version.ref = "testcontainers" }
testcontainers-postgresql = { module = "org.testcontainers:postgresql", version.ref = "testcontainers" }
```

## Microservices Architecture

### 1. Device Registry Service
Manages device registration, authentication, and metadata.

### 2. Transfer Service
Orchestrates file transfers between devices using MQTT.

### 3. File Storage Service
Handles file upload/download and storage management.

### 4. Notification Service
Manages push notifications for transfer events.

### 5. Analytics Service
Tracks usage metrics and system health.

## Database Schema

```sql
-- Device Registry
CREATE TABLE devices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id VARCHAR(255) UNIQUE NOT NULL,
    device_name VARCHAR(255) NOT NULL,
    platform VARCHAR(50) NOT NULL,
    public_key TEXT NOT NULL,
    mqtt_client_id VARCHAR(255) UNIQUE NOT NULL,
    last_seen TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE device_capabilities (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id UUID REFERENCES devices(id) ON DELETE CASCADE,
    capability VARCHAR(100) NOT NULL,
    value JSONB,
    UNIQUE(device_id, capability)
);

-- Transfer Management
CREATE TABLE transfers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sender_device_id UUID REFERENCES devices(id),
    receiver_device_id UUID REFERENCES devices(id),
    status VARCHAR(50) NOT NULL, -- INITIATED, IN_PROGRESS, COMPLETED, FAILED, CANCELLED
    transfer_type VARCHAR(50) NOT NULL, -- FILE, TEXT, WIFI_CREDENTIALS
    metadata JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE transfer_files (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transfer_id UUID REFERENCES transfers(id) ON DELETE CASCADE,
    file_name VARCHAR(500) NOT NULL,
    file_size BIGINT NOT NULL,
    mime_type VARCHAR(255),
    storage_key VARCHAR(500) UNIQUE NOT NULL,
    checksum VARCHAR(64),
    status VARCHAR(50) NOT NULL,
    bytes_transferred BIGINT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- MQTT Session Management
CREATE TABLE mqtt_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id UUID REFERENCES devices(id) ON DELETE CASCADE,
    session_id VARCHAR(255) UNIQUE NOT NULL,
    topic_prefix VARCHAR(255) NOT NULL,
    connected_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    disconnected_at TIMESTAMP WITH TIME ZONE,
    last_ping TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Analytics
CREATE TABLE transfer_analytics (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transfer_id UUID REFERENCES transfers(id),
    event_type VARCHAR(100) NOT NULL,
    event_data JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for performance
CREATE INDEX idx_devices_device_id ON devices(device_id);
CREATE INDEX idx_devices_mqtt_client_id ON devices(mqtt_client_id);
CREATE INDEX idx_transfers_sender ON transfers(sender_device_id);
CREATE INDEX idx_transfers_receiver ON transfers(receiver_device_id);
CREATE INDEX idx_transfers_status ON transfers(status);
CREATE INDEX idx_transfer_files_transfer_id ON transfer_files(transfer_id);
CREATE INDEX idx_mqtt_sessions_device_id ON mqtt_sessions(device_id);
CREATE INDEX idx_transfer_analytics_transfer_id ON transfer_analytics(transfer_id);
```

## API Endpoints Specification

### Device Registry Service

```kotlin
// Device Registration
POST   /api/v1/devices/register
GET    /api/v1/devices/{deviceId}
PUT    /api/v1/devices/{deviceId}
DELETE /api/v1/devices/{deviceId}
GET    /api/v1/devices/{deviceId}/capabilities
POST   /api/v1/devices/{deviceId}/heartbeat

// Device Discovery
GET    /api/v1/devices/discover
POST   /api/v1/devices/search
```

### Transfer Service

```kotlin
// Transfer Management
POST   /api/v1/transfers/initiate
GET    /api/v1/transfers/{transferId}
PUT    /api/v1/transfers/{transferId}/accept
PUT    /api/v1/transfers/{transferId}/reject
PUT    /api/v1/transfers/{transferId}/cancel
GET    /api/v1/transfers/device/{deviceId}

// Transfer Progress
POST   /api/v1/transfers/{transferId}/progress
GET    /api/v1/transfers/{transferId}/status
```

### File Storage Service

```kotlin
// File Operations
POST   /api/v1/files/upload
GET    /api/v1/files/{fileId}/download
DELETE /api/v1/files/{fileId}
GET    /api/v1/files/{fileId}/metadata
POST   /api/v1/files/multipart/initiate
POST   /api/v1/files/multipart/{uploadId}/part
POST   /api/v1/files/multipart/{uploadId}/complete
```

## MQTT Topic Structure

```
klardrop/
├── devices/
│   ├── {deviceId}/
│   │   ├── presence      # Device online/offline status
│   │   ├── info         # Device information updates
│   │   └── command      # Commands to device
│   └── broadcast/       # Broadcast messages
├── transfers/
│   ├── {transferId}/
│   │   ├── initiate     # Transfer initiation
│   │   ├── accept       # Transfer acceptance
│   │   ├── reject       # Transfer rejection
│   │   ├── progress     # Transfer progress updates
│   │   ├── data         # Actual file data chunks
│   │   └── complete     # Transfer completion
│   └── requests/
│       └── {deviceId}/  # Transfer requests for device
└── system/
    ├── notifications/   # System notifications
    └── maintenance/     # Maintenance messages
```

## File Storage Strategy

We recommend a **hybrid approach**:

1. **Small files (< 10MB)**: Direct transfer via MQTT
2. **Large files (>= 10MB)**: Store in object storage with MQTT reference

This approach provides:
- Efficient MQTT broker utilization
- Scalable file storage
- Flexible transfer options
- Better error recovery for large files

## Configuration Management

Using Kotlin and Ktor's configuration:

```kotlin
// application.conf
ktor {
    deployment {
        port = ${PORT}
        port = ${?PORT}
    }
}

app {
    mqtt {
        broker = ${MQTT_BROKER_URL}
        username = ${MQTT_USERNAME}
        password = ${MQTT_PASSWORD}
        clientIdPrefix = "klardrop-cloud"
        qos = 1
    }
    
    database {
        url = ${DATABASE_URL}
        driver = "org.postgresql.Driver"
        maxPoolSize = 10
    }
    
    storage {
        type = ${STORAGE_TYPE} # "minio" or "s3"
        endpoint = ${STORAGE_ENDPOINT}
        accessKey = ${STORAGE_ACCESS_KEY}
        secretKey = ${STORAGE_SECRET_KEY}
        bucket = ${STORAGE_BUCKET}
    }
    
    jwt {
        secret = ${JWT_SECRET}
        issuer = "klardrop-cloud"
        audience = "klardrop-devices"
        realm = "klardrop"
    }
}
```

## Monitoring and Logging

### Metrics to Monitor
- Device registration rate
- Active devices count
- Transfer success/failure rate
- File storage usage
- MQTT message throughput
- API response times
- Database query performance

### Logging Strategy
- Structured logging with correlation IDs
- Log levels: ERROR, WARN, INFO, DEBUG
- Centralized log aggregation
- Log retention policies

## Security Considerations

1. **Device Authentication**: JWT tokens with device certificates
2. **MQTT Security**: TLS encryption, username/password auth
3. **API Security**: Rate limiting, API keys, OAuth2
4. **Data Encryption**: At-rest and in-transit encryption
5. **File Scanning**: Malware scanning for uploaded files

## Deployment Strategy

1. **Container Orchestration**: Kubernetes with Helm charts
2. **Service Mesh**: Istio for inter-service communication
3. **Load Balancing**: NGINX or cloud load balancers
4. **Auto-scaling**: Horizontal Pod Autoscaler
5. **CI/CD**: GitLab CI or GitHub Actions

## Disaster Recovery

1. **Database Backups**: Daily automated backups
2. **File Storage Replication**: Cross-region replication
3. **MQTT Broker Clustering**: High availability setup
4. **Service Health Checks**: Automated recovery
5. **Monitoring Alerts**: PagerDuty integration