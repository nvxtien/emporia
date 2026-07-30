FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY target/order-management-service-*.jar application.jar
USER app
EXPOSE 8086
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
