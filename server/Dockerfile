FROM eclipse-temurin:17 AS build

WORKDIR /app

COPY gradle/ ./gradle/
COPY gradlew ./
COPY settings.gradle.kts build.gradle.kts ./
COPY server/ ./server/

ARG SERVICE_NAME

RUN chmod +x gradlew && \
    ./gradlew :${SERVICE_NAME}:build -x test

FROM eclipse-temurin:17-jre

WORKDIR /app

ARG SERVICE_NAME

COPY --from=build /app/server/${SERVICE_NAME}/build/libs/*.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]