# Build stage
FROM gradle:8.5-jdk17 AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/home/gradle/.gradle gradle :server:buildFatJar --no-daemon

# Runtime stage
FROM eclipse-temurin:17-jre-jammy
RUN groupadd -r app && useradd -r -g app app && apt-get update && apt-get install -y --no-install-recommends wget && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/server/build/libs/*-all.jar /app/server.jar
USER app
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --retries=3 CMD wget -qO- http://localhost:8080/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/server.jar"]
