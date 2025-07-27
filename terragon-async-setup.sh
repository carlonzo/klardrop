#!/bin/bash
# terragon-async-setup.sh - Asynchronous setup script for Terragon environment
# Run this script before executing Gradle commands to setup the environment

set -e

echo "Starting Terragon environment setup..."

# Install dependencies and Java 17
echo "Installing dependencies and Java 17..."
apt update && apt install -y openjdk-17-jdk wget unzip

# Download and setup Android SDK
echo "Setting up Android SDK..."
ANDROID_SDK_ROOT="/opt/android-sdk"
ANDROID_CMDLINE_TOOLS_VERSION="11076708"

# Create SDK directory
mkdir -p $ANDROID_SDK_ROOT/cmdline-tools

# Download Android command line tools
cd /tmp
wget -q "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS_VERSION}_latest.zip"
unzip -q "commandlinetools-linux-${ANDROID_CMDLINE_TOOLS_VERSION}_latest.zip"
mv cmdline-tools $ANDROID_SDK_ROOT/cmdline-tools/latest

# Set up environment variables
export ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT
export ANDROID_HOME=$ANDROID_SDK_ROOT
export PATH=$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools

# Accept licenses and install essential SDK components
echo "Installing Android SDK components..."
yes | sdkmanager --licenses >/dev/null 2>&1
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0" >/dev/null 2>&1

# Add environment variables to shell profiles
echo "export ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT" >> /etc/environment
echo "export ANDROID_HOME=$ANDROID_SDK_ROOT" >> /etc/environment
echo "export PATH=\$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools" >> /etc/environment

# Also add to current session
echo "export ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT" >> ~/.bashrc
echo "export ANDROID_HOME=$ANDROID_SDK_ROOT" >> ~/.bashrc
echo "export PATH=\$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools" >> ~/.bashrc

echo "Terragon environment setup complete!"
echo "Android SDK installed at: $ANDROID_SDK_ROOT"
echo "Environment variables set. Source ~/.bashrc or start a new shell session."
