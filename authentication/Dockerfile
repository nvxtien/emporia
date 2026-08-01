FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY target/authentication-*.jar application.jar
USER app
EXPOSE 9000
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
