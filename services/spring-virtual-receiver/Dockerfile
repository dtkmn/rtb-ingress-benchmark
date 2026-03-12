FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /workspace

COPY pom.xml .
RUN for attempt in 1 2 3; do \
      mvn -q -DskipTests dependency:go-offline && exit 0; \
      echo "Retrying Maven dependency resolution ($attempt/3)"; \
      sleep 5; \
    done; \
    exit 1

COPY src src
RUN for attempt in 1 2 3; do \
      mvn -q -DskipTests package && exit 0; \
      echo "Retrying Spring package build ($attempt/3)"; \
      sleep 5; \
    done; \
    exit 1

FROM eclipse-temurin:25-jre
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /workspace/target/spring-virtual-receiver-*.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_TOOL_OPTIONS -jar /app/app.jar"]
