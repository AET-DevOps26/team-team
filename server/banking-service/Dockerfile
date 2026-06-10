FROM eclipse-temurin:21 AS build
WORKDIR /app
COPY gradle/ ./gradle/
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY account-service/ ./account-service/
COPY transaction-service/ ./transaction-service/
COPY orchestrator-service/ ./orchestrator-service/
COPY banking-service/ ./banking-service/
RUN chmod +x gradlew && \
    ./gradlew :banking-service:build -x test && \
    find build/banking-service/libs -name "*.jar" ! -name "*plain*" -exec cp {} banking-service.jar \;

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/banking-service.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
