FROM maven:3-eclipse-temurin-26 AS build
WORKDIR /workspace

COPY pom.xml .
COPY mvnw mvnw.cmd ./
COPY .mvn .mvn
RUN for attempt in 1 2 3; do \
      ./mvnw -q -DskipTests dependency:go-offline && exit 0; \
      echo "Retrying Maven dependency resolution ($attempt/3)"; \
      sleep 5; \
    done; \
    exit 1

COPY src src
RUN for attempt in 1 2 3; do \
      ./mvnw -q -DskipTests package && exit 0; \
      echo "Retrying Spring package build ($attempt/3)"; \
      sleep 5; \
    done; \
    exit 1

FROM gcr.io/distroless/java25-debian13:nonroot
WORKDIR /app

COPY --from=build --chown=nonroot:nonroot /workspace/target/spring-receiver-*.jar /app/app.jar

# Static HTTP-only probe: no shell, shared libraries, or application helper class.
COPY --from=ghcr.io/tarampampam/microcheck:1.4.0@sha256:c9f79cd408626de7c10f2d487d67339f49adf0ba61dde96ede65343269db1f85 /bin/httpcheck /usr/local/bin/httpcheck
# Retain upstream notices locally so license-host outages cannot break builds.
COPY --chmod=644 THIRD_PARTY_NOTICES /usr/share/licenses/microcheck/THIRD_PARTY_NOTICES

HEALTHCHECK --interval=10s --timeout=5s --start-period=10s --retries=5 \
    CMD ["/usr/local/bin/httpcheck", "--connect-timeout", "2", "--timeout", "2", "http://127.0.0.1:8080/health"]

EXPOSE 8080

# Note: Distroless has no shell
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
