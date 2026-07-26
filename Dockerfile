FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /workspace
COPY gateway/pom.xml ./
RUN mvn --batch-mode dependency:go-offline
COPY gateway/src ./src
RUN mvn --batch-mode clean package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/target/emporia-gateway-*.jar application.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
