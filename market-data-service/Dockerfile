FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY target/market-data-service-*.jar application.jar
USER app
EXPOSE 8084 50551
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
