FROM --platform=linux/arm64 amazoncorretto:21-alpine

# Create non-root user
RUN addgroup -S appuser && adduser -S appuser -G appuser

WORKDIR /app

# Copy the jar file
COPY target/spring-ai-extended-chat-client-1.0.0-SNAPSHOT.jar app.jar

# Change ownership
RUN chown appuser:appuser /app/app.jar

# Switch to non-root user
USER appuser

# Expose port
EXPOSE 8080

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
