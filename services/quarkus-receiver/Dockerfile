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
ADD --chmod=644 --checksum=sha256:f4ee993cd31f2a7add380553f87004262c22f1e3d0792f79d0eaf2157e2e3d1b https://raw.githubusercontent.com/tarampampam/microcheck/9588bd438068c512544b9953ce1c1262afa39851/LICENSE /usr/share/licenses/microcheck/LICENSE

ADD --chmod=644 --checksum=sha256:f9bc4423732350eb0b3f7ed7e91d530298476f8fec0c6c427a1c04ade22655af https://git.musl-libc.org/cgit/musl/plain/COPYRIGHT?h=v1.2.5 /usr/share/licenses/microcheck/musl-COPYRIGHT

HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=5 \
    CMD ["/usr/local/bin/httpcheck", "--connect-timeout", "2", "--timeout", "2", "http://127.0.0.1:8080/q/health/ready"]

EXPOSE 8080
# Already running as nonroot user in distroless
ENV JAVA_APP_JAR="/deployments/quarkus-run.jar"

# Note: Distroless has no shell, so use direct Java execution
ENTRYPOINT ["java", "-Dquarkus.http.host=0.0.0.0", "-Djava.util.logging.manager=org.jboss.logmanager.LogManager", "-jar", "/deployments/quarkus-run.jar"]
