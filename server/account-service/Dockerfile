FROM eclipse-temurin:21 AS build
WORKDIR /app
COPY server/gradle/ ./gradle/
COPY server/gradlew ./
COPY server/settings.gradle.kts server/build.gradle.kts ./
COPY server/ ./server/
RUN chmod +x gradlew && \
    ./gradlew :account-service:build -x test && \
    find build/account-service/libs -name "*.jar" ! -name "*plain*" -exec cp {} account-service.jar \;

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/account-service.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]