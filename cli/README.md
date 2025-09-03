# Klardrop CLI

A command-line interface for Klardrop, enabling file sharing and device discovery from the terminal.

## Building

```bash
./gradlew :cli:build
```

## Running

```bash
./gradlew :cli:jvmRun --args="<command>"
```

## Commands

### Discover nearby devices

```bash
./gradlew :cli:jvmRun --args="discover"
./gradlew :cli:jvmRun --args="discover --timeout=10"
./gradlew :cli:jvmRun --args="discover --debug"
```

### Send text message

```bash
./gradlew :cli:jvmRun --args="send DEVICE_ID --text='Hello World'"
./gradlew :cli:jvmRun --args="send DEVICE_ID 'Hello World'"
```

### Send file

```bash
./gradlew :cli:jvmRun --args="send DEVICE_ID --file=/path/to/file.txt"
./gradlew :cli:jvmRun --args="send DEVICE_ID /path/to/file.txt"
```

### Show status

```bash
./gradlew :cli:jvmRun --args="status"
./gradlew :cli:jvmRun --args="status --debug"
```

## Architecture

- **CLI Module**: Kotlin Multiplatform with JVM target
- **Business Logic**: Reuses existing `common` module
- **Command Framework**: Uses [Clikt](https://ajalt.github.io/clikt/) for argument parsing
- **Platform Support**: Currently JVM-only, designed for future native support

## Key Components

- `Main.kt` - Entry point and command routing
- `CliController.kt` - Bridge between CLI and business logic
- `commands/` - Individual command implementations
    - `DiscoverCommand.kt` - Device discovery
    - `SendCommand.kt` - File/text sending
    - `StatusCommand.kt` - Status display