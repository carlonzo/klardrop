# Multi-stage Dockerfile for Klardrop Cloud Services

# Stage 1: Build stage
FROM gradle:8.11-jdk21 AS builder

WORKDIR /app

# Copy gradle files first for better caching
COPY gradle/ gradle/
COPY gradlew gradlew.bat gradle.properties ./
COPY settings.gradle.kts build.gradle.kts ./

# Download dependencies
RUN ./gradlew dependencies --no-daemon

# Copy source code
COPY src/ src/

# Build the application
RUN ./gradlew shadowJar --no-daemon

# Stage 2: Runtime stage
FROM eclipse-temurin:21-jre-alpine

# Install necessary packages
RUN apk add --no-cache \
    curl \
    jq \
    && rm -rf /var/cache/apk/*

# Create non-root user
RUN addgroup -g 1000 klardrop && \
    adduser -D -u 1000 -G klardrop klardrop

WORKDIR /app

# Copy the built jar from builder stage
COPY --from=builder /app/build/libs/*-all.jar app.jar

# Copy configuration files
COPY --chown=klardrop:klardrop config/ config/

# Create necessary directories
RUN mkdir -p /app/logs /app/data && \
    chown -R klardrop:klardrop /app

# Switch to non-root user
USER klardrop

# Expose port (configurable via environment variable)
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:8080/health || exit 1

# JVM options for containerized environment
ENV JAVA_OPTS="-XX:+UseContainerSupport \
    -XX:MaxRAMPercentage=75.0 \
    -XX:InitialRAMPercentage=50.0 \
    -XX:+UseG1GC \
    -XX:+HeapDumpOnOutOfMemoryError \
    -XX:HeapDumpPath=/app/logs/heap-dump.hprof"

# Run the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]