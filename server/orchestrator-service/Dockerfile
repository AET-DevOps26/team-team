FROM maven:3.9.9-eclipse-temurin-17 AS build
COPY server/ /app/server/
WORKDIR /app/server/orchestrator-service
RUN mvn -q dependency:go-offline
COPY server/orchestrator-service/src ./src
RUN mvn -q clean package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/server/orchestrator-service/target/orchestrator-service-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
