# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Klardrop is a Kotlin Multiplatform project for cross-platform file sharing and device discovery. It implements nearby sharing functionality similar to AirDrop, supporting Android, iOS, macOS, and desktop platforms.

The discovery mechanism uses mDNS to find devices on the local network.

## Project Structure

The project is organized into several modules:

- **`common/`** - Core business logic and platform abstractions
  - Communication layer (Client/Server, WebSocket messaging)
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
- **Server/Client** - WebSocket-based communication layer using Ktor
- **FileManager** - Cross-platform file operations using kotlinx-io

### Communication Protocols

The app supports multiple sharing protocols:
- **Klardrop protocol** - Custom WebSocket-based protocol
- **Nearby Share** - Google's nearby sharing protocol

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
- **Ktor** - Networking and WebSocket communication
- **kotlinx.coroutines** - Asynchronous programming
- **kotlinx.serialization** - Data serialization with Protocol Buffers
- **Dagger** - Dependency injection
- **jmDNS** - Service discovery
- **Okio** - File I/O operations

## Common Development Commands

Please provide the gradle commands for building, testing, and linting as they are not included in the existing documentation.