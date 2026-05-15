# ---- build stage ----
FROM eclipse-temurin:17 AS build

WORKDIR /app

COPY gradle/ ./gradle/
COPY gradlew ./
COPY settings.gradle.kts build.gradle.kts ./
COPY server/ ./server/

RUN chmod +x gradlew && \
    ./gradlew :transaction-service:build -x test

# ---- runtime stage ----
FROM eclipse-temurin:17-jre

WORKDIR /app

COPY --from=build /app/server/transaction-service/build/libs/*.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]
