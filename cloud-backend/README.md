# Klardrop Cloud Backend

A cloud-native backend service for Klardrop that enables global device discovery and file sharing using MQTT protocol.

## Architecture Overview

The Klardrop Cloud Backend is built using a microservices architecture with the following components:

### Core Services

1. **Device Registry Service** - Manages device registration, authentication, and metadata
2. **Transfer Service** - Orchestrates file transfers between devices using MQTT
3. **File Storage Service** - Handles file upload/download and storage management
4. **Notification Service** - Manages push notifications for transfer events
5. **Analytics Service** - Tracks usage metrics and system health

### Technology Stack

- **Language**: Kotlin
- **Framework**: Ktor
- **Database**: PostgreSQL
- **Cache**: Redis
- **Message Broker**: MQTT (Eclipse Mosquitto)
- **Message Queue**: Apache Kafka
- **Object Storage**: MinIO (S3-compatible)
- **Container**: Docker
- **Orchestration**: Kubernetes

## Project Structure

```
cloud-backend/
├── device-registry/          # Device management service
├── transfer-service/         # Transfer orchestration service
├── file-storage-service/     # File storage management
├── notification-service/     # Push notification service
├── analytics-service/        # Analytics and monitoring
├── common/                   # Shared libraries and models
├── docker/                   # Docker configurations
├── k8s/                      # Kubernetes manifests
└── scripts/                  # Deployment and utility scripts
```

## Getting Started

### Prerequisites

- JDK 21 or higher
- Docker and Docker Compose
- Gradle 8.11 or higher
- PostgreSQL 16
- Redis 7
- MinIO or S3-compatible storage

### Local Development

1. Clone the repository:
```bash
git clone https://github.com/your-org/klardrop.git
cd klardrop/cloud-backend
```

2. Start infrastructure services:
```bash
docker-compose -f docker/docker-compose.yml up -d postgres redis mosquitto minio kafka zookeeper
```

3. Run database migrations:
```bash
./gradlew :device-registry:flywayMigrate
```

4. Start each service:
```bash
# Terminal 1 - Device Registry
./gradlew :device-registry:run

# Terminal 2 - Transfer Service
./gradlew :transfer-service:run

# Terminal 3 - File Storage Service
./gradlew :file-storage-service:run
```

### Running with Docker Compose

```bash
# Build all services
./scripts/build-all.sh

# Start all services
docker-compose -f docker/docker-compose.yml up -d

# View logs
docker-compose -f docker/docker-compose.yml logs -f

# Stop all services
docker-compose -f docker/docker-compose.yml down
```

## API Documentation

### Device Registry API

#### Register Device
```http
POST /api/v1/devices/register
Content-Type: application/json

{
  "deviceId": "device123",
  "deviceName": "John's iPhone",
  "platform": "IOS",
  "publicKey": "-----BEGIN PUBLIC KEY-----...",
  "capabilities": {
    "maxFileSize": "5GB",
    "supportedFormats": "all"
  }
}
```

#### Get Device
```http
GET /api/v1/devices/{deviceId}
Authorization: Bearer {token}
```

### Transfer API

#### Initiate Transfer
```http
POST /api/v1/transfers/initiate
Content-Type: application/json
Authorization: Bearer {token}

{
  "senderDeviceId": "device123",
  "receiverDeviceId": "device456",
  "files": [
    {
      "fileName": "document.pdf",
      "fileSize": 1024000,
      "mimeType": "application/pdf"
    }
  ]
}
```

## MQTT Topics

The service uses the following MQTT topic structure:

```
klardrop/
├── devices/{deviceId}/
│   ├── presence      # Online/offline status
│   ├── info         # Device information
│   └── command      # Commands to device
├── transfers/{transferId}/
│   ├── initiate     # Transfer initiation
│   ├── accept       # Transfer acceptance
│   ├── reject       # Transfer rejection
│   ├── progress     # Progress updates
│   ├── data         # File data chunks
│   └── complete     # Transfer completion
└── system/
    └── notifications # System-wide notifications
```

## Configuration

Services are configured using environment variables:

```env
# Database
DATABASE_URL=jdbc:postgresql://localhost:5432/klardrop
DATABASE_USER=klardrop
DATABASE_PASSWORD=secure-password

# MQTT
MQTT_BROKER_URL=tcp://localhost:1883
MQTT_USERNAME=klardrop
MQTT_PASSWORD=secure-password

# Storage
MINIO_ENDPOINT=http://localhost:9000
MINIO_ACCESS_KEY=minioadmin
MINIO_SECRET_KEY=minioadmin
MINIO_BUCKET=klardrop-files

# JWT
JWT_SECRET=your-secret-key
JWT_ISSUER=klardrop-cloud
JWT_AUDIENCE=klardrop-devices
```

## Deployment

### Kubernetes Deployment

```bash
# Create namespace
kubectl create namespace klardrop

# Apply configurations
kubectl apply -f k8s/configmap.yaml
kubectl apply -f k8s/secrets.yaml

# Deploy services
kubectl apply -f k8s/

# Check deployment status
kubectl get pods -n klardrop
```

### Production Considerations

1. **Security**:
   - Enable TLS for all services
   - Use strong JWT secrets
   - Implement rate limiting
   - Enable firewall rules

2. **Scaling**:
   - Use horizontal pod autoscaling
   - Configure database connection pooling
   - Implement caching strategies
   - Use CDN for file delivery

3. **Monitoring**:
   - Set up Prometheus alerts
   - Configure Grafana dashboards
   - Enable distributed tracing
   - Implement health checks

## Testing

```bash
# Run unit tests
./gradlew test

# Run integration tests
./gradlew integrationTest

# Run all tests with coverage
./gradlew test integrationTest jacocoTestReport
```

## Contributing

Please read [CONTRIBUTING.md](../CONTRIBUTING.md) for details on our code of conduct and the process for submitting pull requests.

## License

This project is licensed under the MIT License - see the [LICENSE](../LICENSE) file for details.