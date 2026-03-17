FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /app

COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -q -DskipTests package dependency:copy-dependencies

FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build /app/target/classes ./classes
COPY --from=build /app/target/dependency ./libs

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=80.0 -Dfile.encoding=UTF-8"

CMD ["java", "-cp", "classes:libs/*", "com.example.reminderbot.Main"]