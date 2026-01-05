FROM --platform=linux/arm64 amazoncorretto:21-alpine

# Create non-root user
RUN addgroup -S appuser && adduser -S appuser -G appuser

# Set working directory
WORKDIR /app

# Copy JAR file
COPY target/spring-ai-extended-chat-client-1.0.0-SNAPSHOT.jar app.jar

# Change ownership to non-root user
RUN chown appuser:appuser /app/app.jar

# Switch to non-root user
USER appuser

# Expose port
EXPOSE 8080

# Use exec form (container support is default in JDK 21)
ENTRYPOINT ["java", "-jar", "app.jar"]
