FROM --platform=linux/arm64 amazoncorretto:21-alpine

# Install Python and uv for MCP server support
RUN apk add --no-cache python3 py3-pip curl bash wget

# Install uv (Python package manager)
RUN curl -LsSf https://astral.sh/uv/install.sh | sh
ENV PATH="/root/.local/bin:${PATH}"

# Verify uvx is available
RUN uvx --version

WORKDIR /app

# Copy the application JAR
COPY target/*.jar app.jar

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/ping || exit 1

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
