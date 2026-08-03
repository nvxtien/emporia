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
# JAVA_OPTS lets a service pass JVM flags it cannot do without. execution-service
# needs the Chronicle module-access flags (see its pom.xml): without them
# exchange-core's matching engine throws IllegalAccessError and orders never
# fill, while still returning 201 and resting as LIVE.
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/application.jar"]
