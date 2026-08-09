FROM eclipse-temurin:26-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -DskipTests dependency:go-offline
COPY src src
RUN ./mvnw -B -DskipTests clean package

FROM eclipse-temurin:26-jre-alpine
WORKDIR /app
COPY --from=build /workspace/target/zhitoubao-*.jar app.jar
USER 10001:10001
EXPOSE 8000
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
