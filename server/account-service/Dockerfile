# ---- build stage ----
FROM eclipse-temurin:21 AS build

WORKDIR /app

COPY gradle/ ./gradle/
COPY gradlew ./
COPY settings.gradle.kts build.gradle.kts ./
COPY account-service/ ./account-service/
COPY orchestrator-service/ ./orchestrator-service/
COPY transaction-service/ ./transaction-service/

RUN chmod +x gradlew && \
    ./gradlew :account-service:build -x test

# ---- runtime stage (distroless) ----
FROM gcr.io/distroless/java21-debian12

WORKDIR /app

COPY --from=build /app/build/account-service/libs/*.jar /app/app.jar

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
