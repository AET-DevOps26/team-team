FROM eclipse-temurin:21 AS build
WORKDIR /app
COPY gradle/ ./gradle/
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY account-service/ ./account-service/
COPY transaction-service/ ./transaction-service/
COPY orchestrator-service/ ./orchestrator-service/
COPY banking-service/ ./banking-service/
RUN chmod +x gradlew && \
    ./gradlew :account-service:build -x test && \
    find build/account-service/libs -name "*.jar" ! -name "*plain*" -exec cp {} account-service.jar \;

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/account-service.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]