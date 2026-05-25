FROM eclipse-temurin:21 AS build
WORKDIR /app
COPY . .
RUN chmod +x gradlew && \
    ./gradlew :transaction-service:build -x test && \
    find build/transaction-service/libs -name "*.jar" ! -name "*plain*" -exec cp {} transaction-service.jar \;

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/transaction-service.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]