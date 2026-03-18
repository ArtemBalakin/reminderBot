FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
COPY data ./data
COPY sample ./sample
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/reminderbot-7.0.0.jar /app/app.jar
COPY --from=build /app/data /app/data
ENV PORT=8080 \
    APP_PORT=8080 \
    BOT_ZONE=Asia/Almaty \
    BOT_STATE_FILE=/app/data/state.json \
    BOT_CATALOG_FILE=/app/data/catalog.json
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
