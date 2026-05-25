FROM eclipse-temurin:21 AS build
WORKDIR /app
COPY . .
RUN chmod +x gradlew && \
    ./gradlew :orchestrator-service:build -x test && \
    find build/orchestrator-service/libs -name "*.jar" ! -name "*plain*" -exec cp {} orchestrator-service.jar \;

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/orchestrator-service.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]