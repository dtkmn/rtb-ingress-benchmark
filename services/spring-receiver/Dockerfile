FROM maven:3.9.16-eclipse-temurin-25 AS build
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
ADD --chmod=644 --checksum=sha256:f4ee993cd31f2a7add380553f87004262c22f1e3d0792f79d0eaf2157e2e3d1b https://raw.githubusercontent.com/tarampampam/microcheck/9588bd438068c512544b9953ce1c1262afa39851/LICENSE /usr/share/licenses/microcheck/LICENSE

ADD --chmod=644 --checksum=sha256:f9bc4423732350eb0b3f7ed7e91d530298476f8fec0c6c427a1c04ade22655af https://git.musl-libc.org/cgit/musl/plain/COPYRIGHT?h=v1.2.5 /usr/share/licenses/microcheck/musl-COPYRIGHT

HEALTHCHECK --interval=10s --timeout=5s --start-period=10s --retries=5 \
    CMD ["/usr/local/bin/httpcheck", "--connect-timeout", "2", "--timeout", "2", "http://127.0.0.1:8080/health"]

EXPOSE 8080

# Note: Distroless has no shell
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
