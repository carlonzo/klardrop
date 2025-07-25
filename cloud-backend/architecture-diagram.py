#!/usr/bin/env python3
"""
Generate architecture diagrams for Klardrop Cloud Backend
Requires: pip install diagrams
"""

from diagrams import Diagram, Cluster, Edge
from diagrams.onprem.container import Docker
from diagrams.onprem.database import PostgreSQL
from diagrams.onprem.inmemory import Redis
from diagrams.onprem.queue import Kafka
from diagrams.onprem.network import Kong
from diagrams.onprem.monitoring import Prometheus, Grafana
from diagrams.onprem.logging import Loki
from diagrams.onprem.storage import Minio
from diagrams.generic.device import Mobile, Tablet
from diagrams.programming.framework import Spring
from diagrams.generic.network import Switch

# High-level architecture diagram
with Diagram("Klardrop Cloud Architecture", filename="klardrop_cloud_architecture", show=False, direction="TB"):
    
    # Client devices
    with Cluster("Client Devices"):
        android = Mobile("Android")
        ios = Mobile("iOS")
        desktop = Tablet("Desktop")
    
    # API Gateway
    gateway = Kong("API Gateway")
    
    # Microservices
    with Cluster("Microservices"):
        device_registry = Spring("Device Registry\n(Ktor)")
        transfer_service = Spring("Transfer Service\n(Ktor)")
        file_storage = Spring("File Storage\n(Ktor)")
        notification = Spring("Notification\n(Ktor)")
        analytics = Spring("Analytics\n(Ktor)")
    
    # MQTT Broker
    mqtt = Switch("MQTT Broker\n(Mosquitto)")
    
    # Data Layer
    with Cluster("Data Layer"):
        postgres = PostgreSQL("PostgreSQL")
        redis = Redis("Redis Cache")
        minio = Minio("MinIO\n(Object Storage)")
        kafka_cluster = Kafka("Kafka")
    
    # Monitoring
    with Cluster("Monitoring & Logging"):
        prometheus = Prometheus("Prometheus")
        grafana = Grafana("Grafana")
        loki = Loki("Loki")
    
    # Connections
    [android, ios, desktop] >> gateway
    gateway >> [device_registry, transfer_service, file_storage]
    
    # MQTT connections
    [android, ios, desktop] >> Edge(style="dashed", label="MQTT") >> mqtt
    mqtt >> Edge(style="dashed") >> transfer_service
    
    # Service connections
    device_registry >> [postgres, redis]
    transfer_service >> [postgres, mqtt, kafka_cluster]
    file_storage >> [postgres, minio]
    notification >> [postgres, kafka_cluster]
    analytics >> [postgres, kafka_cluster]
    
    # Monitoring connections
    [device_registry, transfer_service, file_storage] >> Edge(style="dotted") >> prometheus
    prometheus >> grafana
    [device_registry, transfer_service, file_storage] >> Edge(style="dotted") >> loki

# Microservices communication diagram
with Diagram("Microservices Communication", filename="klardrop_microservices", show=False, direction="LR"):
    
    with Cluster("External"):
        client = Mobile("Client Device")
        api_gw = Kong("API Gateway")
    
    with Cluster("Service Mesh"):
        device_svc = Spring("Device\nRegistry")
        transfer_svc = Spring("Transfer\nService")
        file_svc = Spring("File\nStorage")
        
        kafka = Kafka("Kafka")
        
        # Service interactions
        client >> api_gw
        api_gw >> device_svc
        api_gw >> transfer_svc
        api_gw >> file_svc
        
        # Event streaming
        device_svc >> Edge(label="device.registered") >> kafka
        transfer_svc >> Edge(label="transfer.initiated") >> kafka
        file_svc >> Edge(label="file.uploaded") >> kafka
        
        kafka >> Edge(label="events") >> [device_svc, transfer_svc, file_svc]

# MQTT Transfer Flow
with Diagram("MQTT Transfer Flow", filename="klardrop_mqtt_flow", show=False, direction="TB"):
    
    sender = Mobile("Sender Device")
    receiver = Mobile("Receiver Device")
    
    with Cluster("Cloud Backend"):
        mqtt_broker = Switch("MQTT Broker")
        transfer_service = Spring("Transfer Service")
        file_storage = Minio("File Storage")
        
    # Transfer flow
    sender >> Edge(label="1. Initiate Transfer") >> transfer_service
    transfer_service >> Edge(label="2. Create Transfer ID") >> mqtt_broker
    mqtt_broker >> Edge(label="3. Notify Receiver") >> receiver
    receiver >> Edge(label="4. Accept Transfer") >> mqtt_broker
    mqtt_broker >> Edge(label="5. Notify Sender") >> sender
    
    sender >> Edge(label="6. Upload File", style="bold") >> file_storage
    file_storage >> Edge(label="7. Store & Generate URL") >> transfer_service
    transfer_service >> Edge(label="8. Send Download URL") >> mqtt_broker
    mqtt_broker >> Edge(label="9. Receive URL") >> receiver
    receiver >> Edge(label="10. Download File", style="bold") >> file_storage

# Database Schema Relationships
with Diagram("Database Schema", filename="klardrop_database", show=False, direction="LR"):
    
    with Cluster("Device Registry"):
        devices = PostgreSQL("devices")
        capabilities = PostgreSQL("device_capabilities")
        mqtt_sessions = PostgreSQL("mqtt_sessions")
        
        devices >> Edge(label="1:n") >> capabilities
        devices >> Edge(label="1:n") >> mqtt_sessions
    
    with Cluster("Transfer Management"):
        transfers = PostgreSQL("transfers")
        transfer_files = PostgreSQL("transfer_files")
        analytics = PostgreSQL("transfer_analytics")
        
        transfers >> Edge(label="1:n") >> transfer_files
        transfers >> Edge(label="1:n") >> analytics
        
    # Cross-domain relationships
    devices >> Edge(label="sender", style="dashed") >> transfers
    devices >> Edge(label="receiver", style="dashed") >> transfers

print("Architecture diagrams generated successfully!")
print("Files created:")
print("- klardrop_cloud_architecture.png")
print("- klardrop_microservices.png")
print("- klardrop_mqtt_flow.png")
print("- klardrop_database.png")