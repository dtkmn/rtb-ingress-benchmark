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

COPY --from=build --chown=nonroot:nonroot /workspace/target/spring-virtual-receiver-*.jar /app/app.jar

EXPOSE 8080

# Note: Distroless has no shell
# Health checks must be done via HTTP from outside the container
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
