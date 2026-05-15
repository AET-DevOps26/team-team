# ---- build stage ----
FROM eclipse-temurin:21 AS build

WORKDIR /app

COPY gradle/ ./gradle/
COPY gradlew ./
COPY settings.gradle.kts build.gradle.kts ./
COPY server/ ./server/

RUN chmod +x gradlew && \
    ./gradlew :orchestrator-service:build -x test

# ---- runtime stage ----
FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build /app/server/orchestrator-service/build/libs/*.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]
