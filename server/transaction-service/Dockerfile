FROM maven:3.9.9-eclipse-temurin-17 AS build
COPY server/ /app/server/
WORKDIR /app/server/transaction-service
RUN mvn -q dependency:go-offline
COPY server/transaction-service/src ./src
RUN mvn -q clean package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/server/transaction-service/target/transaction-service-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
