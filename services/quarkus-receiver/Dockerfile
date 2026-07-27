# Using Java 25 (LTS) for both build and runtime
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

# Using Distroless Java 25 (LTS) on Debian 13 for minimal attack surface and fewer CVEs
FROM gcr.io/distroless/java25-debian13:nonroot

ENV LANGUAGE='en_US:en'

WORKDIR /deployments

# Copy the built artifacts from the builder stage
COPY --from=builder /build/target/quarkus-app/lib/ /deployments/lib/
COPY --from=builder /build/target/quarkus-app/*.jar /deployments/
COPY --from=builder /build/target/quarkus-app/app/ /deployments/app/
COPY --from=builder /build/target/quarkus-app/quarkus/ /deployments/quarkus/

EXPOSE 8080
# Already running as nonroot user in distroless
ENV JAVA_APP_JAR="/deployments/quarkus-run.jar"

# Note: Distroless has no shell, so use direct Java execution
# Health checks must be done via HTTP from outside the container
ENTRYPOINT ["java", "-Dquarkus.http.host=0.0.0.0", "-Djava.util.logging.manager=org.jboss.logmanager.LogManager", "-jar", "/deployments/quarkus-run.jar"]
