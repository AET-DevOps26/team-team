FROM eclipse-temurin:21 AS build
WORKDIR /app
COPY gradle/ ./gradle/
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY account-service/ ./account-service/
COPY transaction-service/ ./transaction-service/
COPY orchestrator-service/ ./orchestrator-service/
RUN chmod +x gradlew && \
    ./gradlew :orchestrator-service:build -x test && \
    find build/orchestrator-service/libs -name "*.jar" ! -name "*plain*" -exec cp {} orchestrator-service.jar \;

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/orchestrator-service.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]