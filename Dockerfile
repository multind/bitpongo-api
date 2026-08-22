FROM eclipse-temurin:26-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src src
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -DskipTests clean package

FROM eclipse-temurin:26-jre-alpine
WORKDIR /app
COPY --from=build /workspace/target/bitpongo-api-*.jar app.jar
USER 10001:10001
EXPOSE 8000
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
