# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Klardrop is a Kotlin Multiplatform project for cross-platform file sharing and device discovery. It implements nearby sharing functionality similar to AirDrop, supporting Android, iOS, macOS, and desktop platforms.

The discovery mechanism uses mDNS to find devices on the local network.

## Project Structure

The project is organized into several modules:

- **`common/`** - Core business logic and platform abstractions
  - Communication layer (Client/Server, raw TCP socket messaging)
  - Device discovery (mDNS, nearby share protocols)
  - File management and transfer
  - Dependency injection with Dagger
  - Platform-specific implementations for Android, iOS, macOS, desktop

- **`common-ui/`** - Shared UI components built with Compose Multiplatform
  - Discovery screen and device list
  - Cross-platform UI that adapts to different screen sizes

- **`protos/`** - Protocol Buffer definitions for wire formats used by the Nearby Share protocol

Platform-specific modules:
- **`android/`** - Android app with share extension support
- **`desktop/`** - JVM desktop application
- **`macos/`** - Native macOS application
- **`iosApp/`** - iOS application with share extension

## Architecture

### Core Components

- **Klardrop** (`common/src/commonMain/kotlin/com/carlom/klardrop/common/Klardrop.kt`) - Main entry point that initializes servers and discovery
- **CommonComponent** - Main dependency injection component providing all services
- **DiscoveryNetwork** - Handles device discovery via mDNS for multiple protocols
- **Server/Client** - Raw TCP socket-based communication layer using Ktor
- **FileManager** - Cross-platform file operations using kotlinx-io

### Communication Protocols

The app supports multiple sharing protocols:
- **Klardrop protocol** - Custom raw TCP socket protocol with length-prefixed messages
- **Nearby Share** - Google's nearby sharing protocol

#### Klardrop Socket Protocol

The Klardrop protocol uses raw TCP sockets with a simple length-prefixed message format:

```
[4 bytes: message length][message data: type_id + protobuf_payload]
```

**Key Features:**
- **Connection**: Direct TCP socket connection using Ktor network
- **Handshake**: Device identification exchange using shortened device IDs (8 chars)
- **Messages**: Protocol Buffer serialized with type prefix (TEXT, FILE, HANDSHAKE)
- **File Transfer**: Streaming transfer with 32KB chunks and progress tracking
- **Multiplexing**: Single connection handles all message types bidirectionally

**Benefits over WebSocket:**
- Lower overhead (no WebSocket framing)
- Simpler debugging and monitoring
- Better cross-platform compatibility
- Reduced dependency footprint

### Platform Dependencies

Each platform provides implementations for:
- **ServiceDiscoveryMdns** - mDNS service discovery
- **ClipboardReaderWriter** - Clipboard operations
- **PlatformFileSystem** - File system access
- **InternalPlatformDependencies** - Platform-specific services

## Build System

- Uses Gradle with Kotlin DSL
- Kotlin Multiplatform with shared code
- Compose Multiplatform for UI
- Protocol Buffers for serialization
- Dependency management via `gradle/dependencies.toml`

## Key Technologies

- **Kotlin Multiplatform** - Code sharing across platforms
- **Compose Multiplatform** - Cross-platform UI framework
- **Ktor** - Networking and raw TCP socket communication
- **kotlinx.coroutines** - Asynchronous programming
- **kotlinx.serialization** - Data serialization with Protocol Buffers
- **Dagger** - Dependency injection
- **jmDNS** - Service discovery
- **Okio** - File I/O operations

## Common Development Commands

- Compile sources for common and jvm platform: run the `:desktop:compileKotlinJvm` gradle task.
- Run unit tests for common and jvm platform: run the `desktopJvmTest` gradle task.
- To Run the desktop application: run the `:desktop:run` gradle task.