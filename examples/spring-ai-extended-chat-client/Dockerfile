FROM --platform=linux/arm64 amazoncorretto:21-alpine

RUN addgroup -S appuser && adduser -S appuser -G appuser
WORKDIR /app
COPY target/spring-ai-extended-chat-client.jar app.jar
RUN chown appuser:appuser /app/app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
