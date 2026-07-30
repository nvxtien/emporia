FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY target/user-preferences-service-*.jar application.jar
USER app
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
