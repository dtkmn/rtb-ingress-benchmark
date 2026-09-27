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

# Retry transient Maven distribution/repository failures; retain bootstrap diagnostics.
RUN for attempt in 1 2 3; do \
      MVNW_VERBOSE=true ./mvnw dependency:go-offline -B && exit 0; \
      if [ "$attempt" -lt 3 ]; then \
        echo "Retrying Maven bootstrap/dependency download ($attempt/3)"; \
        sleep 5; \
      fi; \
    done; \
    exit 1

# Copy source code
COPY src ./src

# Build the application
RUN ./mvnw package -DskipTests -B

# Using Distroless Java 25 (LTS) on Debian 13 for minimal attack surface and fewer CVEs
# Note: gcr.io/distroless/java25-debian13 includes libstdc++ needed for RocksDB/Kafka Streams
FROM gcr.io/distroless/java25-debian13:nonroot

ENV LANGUAGE='en_US:en'

WORKDIR /deployments

# Copy the built artifacts from the builder stage
COPY --from=builder /build/target/quarkus-app/lib/ /deployments/lib/
COPY --from=builder /build/target/quarkus-app/*.jar /deployments/
COPY --from=builder /build/target/quarkus-app/app/ /deployments/app/
COPY --from=builder /build/target/quarkus-app/quarkus/ /deployments/quarkus/

# Static HTTP-only probe: no shell, shared libraries, or application helper class.
COPY --from=ghcr.io/tarampampam/microcheck:1.4.0@sha256:c9f79cd408626de7c10f2d487d67339f49adf0ba61dde96ede65343269db1f85 /bin/httpcheck /usr/local/bin/httpcheck
# Retain upstream notices locally so license-host outages cannot break builds.
COPY --chmod=644 THIRD_PARTY_NOTICES /usr/share/licenses/microcheck/THIRD_PARTY_NOTICES

HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=5 \
    CMD ["/usr/local/bin/httpcheck", "--connect-timeout", "2", "--timeout", "2", "http://127.0.0.1:8080/q/health/ready"]

EXPOSE 8080
# Already running as nonroot user in distroless
ENV JAVA_OPTS="-Dquarkus.http.host=0.0.0.0 -Djava.util.logging.manager=org.jboss.logmanager.LogManager"
ENV JAVA_APP_JAR="/deployments/quarkus-run.jar"

# Note: Distroless has no shell, so use direct Java execution
ENTRYPOINT ["java", "-jar", "/deployments/quarkus-run.jar"]
