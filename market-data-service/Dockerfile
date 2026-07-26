FROM maven:3.9-eclipse-temurin-21-alpine AS build

WORKDIR /workspace
COPY emporia/pom.xml emporia/pom.xml
COPY emporia/trading-contracts emporia/trading-contracts
COPY emporia/market-data-service emporia/market-data-service
COPY protobuf/fix protobuf/fix
RUN mvn --batch-mode --non-recursive -f emporia/pom.xml install -DskipTests
RUN mvn --batch-mode -f emporia/trading-contracts/pom.xml install -DskipTests
RUN mvn --batch-mode -f emporia/market-data-service/pom.xml clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build --chown=app:app \
    /workspace/emporia/market-data-service/target/market-data-service-*.jar \
    application.jar
USER app
EXPOSE 8084 50551
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
