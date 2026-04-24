####
# Multi-stage Dockerfile for Quarkus Receiver (JVM mode)
# This Dockerfile builds the application and creates the container image in one step
#
# Usage:
# docker build -t quarkus-receiver:benchmark-local .
# docker run -i --rm -p 8080:8080 quarkus-receiver
####

# Stage 1: Build the application with Maven
FROM registry.access.redhat.com/ubi9/openjdk-25:1.24 AS builder

USER root

# Install Maven and required utilities for Maven wrapper
RUN microdnf install -y maven gzip tar && microdnf clean all

WORKDIR /build

# Copy Maven wrapper and pom.xml first for better layer caching
COPY mvnw mvnw.cmd ./
COPY .mvn .mvn
COPY pom.xml ./

# Download dependencies (this layer will be cached if pom.xml doesn't change)
RUN ./mvnw dependency:go-offline -B

# Copy source code
COPY src ./src

# Build the application
RUN ./mvnw package -DskipTests -B

# Stage 2: Create the runtime image
FROM eclipse-temurin:25-jre-alpine

# Install wget for health checks
RUN apk add --no-cache wget

ENV LANGUAGE='en_US:en'

WORKDIR /deployments

# Copy the built artifacts from the builder stage
COPY --from=builder /build/target/quarkus-app/lib/ /deployments/lib/
COPY --from=builder /build/target/quarkus-app/*.jar /deployments/
COPY --from=builder /build/target/quarkus-app/app/ /deployments/app/
COPY --from=builder /build/target/quarkus-app/quarkus/ /deployments/quarkus/

EXPOSE 8080
USER 1000
ENV JAVA_APP_JAR="/deployments/quarkus-run.jar"

ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS:-} -Dquarkus.http.host=0.0.0.0 -Djava.util.logging.manager=org.jboss.logmanager.LogManager -jar ${JAVA_APP_JAR}"]
