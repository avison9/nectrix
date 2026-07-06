FROM mcr.microsoft.com/devcontainers/base:ubuntu-24.04

ARG GO_VERSION=1.23.4
ARG NODE_MAJOR=22
ARG GRADLE_VERSION=9.6.1

RUN apt-get update && apt-get install -y --no-install-recommends \
    make \
    unzip \
    postgresql-client \
    redis-tools \
    openjdk-21-jdk-headless \
    ca-certificates \
    curl \
    gnupg \
    protobuf-compiler \
    && rm -rf /var/lib/apt/lists/*

# Go — installed from the official tarball (pinned version, matches docs/13-technology-stack.md)
RUN curl -fsSL "https://go.dev/dl/go${GO_VERSION}.linux-$(dpkg --print-architecture).tar.gz" -o /tmp/go.tar.gz \
    && tar -C /usr/local -xzf /tmp/go.tar.gz \
    && rm /tmp/go.tar.gz
ENV PATH="/usr/local/go/bin:${PATH}"
ENV GOPATH="/home/vscode/go"
ENV PATH="${GOPATH}/bin:${PATH}"

# protoc-gen-go and golangci-lint — Go-ecosystem build/lint tools
RUN go install google.golang.org/protobuf/cmd/protoc-gen-go@latest
RUN go install github.com/golangci/golangci-lint/v2/cmd/golangci-lint@latest

# Node — NodeSource distribution, pinned major version
RUN curl -fsSL https://deb.nodesource.com/setup_${NODE_MAJOR}.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/*

RUN ln -s /usr/lib/jvm/java-21-openjdk-* /usr/lib/jvm/java-21-openjdk
ENV JAVA_HOME="/usr/lib/jvm/java-21-openjdk"
ENV PATH="${JAVA_HOME}/bin:${PATH}"

# Gradle — installed standalone so `gradle wrapper` can bootstrap each project's own gradlew
RUN curl -fsSL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o /tmp/gradle.zip \
    && unzip -q /tmp/gradle.zip -d /opt \
    && ln -s /opt/gradle-${GRADLE_VERSION} /opt/gradle \
    && rm /tmp/gradle.zip
ENV PATH="/opt/gradle/bin:${PATH}"
