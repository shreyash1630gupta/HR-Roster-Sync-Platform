# Stage 1: Build the application JAR
FROM maven:3.9.9-eclipse-temurin-17-alpine AS builder
WORKDIR /build

# Cache dependency layer
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Build application source
COPY src ./src
RUN mvn package -DskipTests -B

# Stage 2: Minimal runtime image
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

RUN apk add --no-cache curl \
    && addgroup -S roster && adduser -S roster -G roster

USER roster

COPY --from=builder /build/target/hr-roster-sync-*.jar app.jar

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=5s --start-period=20s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
