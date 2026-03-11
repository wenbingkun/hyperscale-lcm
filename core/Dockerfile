# Build stage
FROM gradle:8.5-jdk21 AS build

WORKDIR /app

# Copy gradle files first for caching
COPY build.gradle settings.gradle gradle.properties ./
COPY gradle ./gradle

# Download dependencies
RUN gradle dependencies --no-daemon

# Copy source code
COPY src ./src

# Build the application
RUN gradle build -x test --no-daemon

# Runtime stage
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Copy the built artifact
COPY --from=build /app/build/quarkus-app/lib/ ./lib/
COPY --from=build /app/build/quarkus-app/*.jar ./
COPY --from=build /app/build/quarkus-app/app/ ./app/
COPY --from=build /app/build/quarkus-app/quarkus/ ./quarkus/

# Expose ports
EXPOSE 8080 9000

# Environment variables
ENV JAVA_OPTS="-Xmx512m -Xms256m"

# Health check (wget is available in jre-jammy, curl is not)
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
    CMD wget -qO- http://localhost:8080/health/live || exit 1

# Run the application (shell form to allow JAVA_OPTS expansion)
ENTRYPOINT java ${JAVA_OPTS} -jar quarkus-run.jar
