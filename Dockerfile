# Stage 1: Build
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests -B

# Stage 2: Runtime
FROM eclipse-temurin:17-jre
WORKDIR /app

RUN groupadd -r appuser && useradd -r -g appuser appuser

COPY --from=builder /build/target/*.jar app.jar

# JVM tuning:
#   MaxRAMPercentage=75       — let the JVM see container memory and use 75% of it
#                               (default 25% wastes most of a t3.small's RAM).
#   ExitOnOutOfMemoryError    — fail fast on OOM so the container restarts and is
#                               surfaced by Docker; otherwise the app limps along
#                               in a half-broken state.
#   HeapDumpOnOutOfMemoryError — writes a hprof to /tmp on OOM. Mount /tmp as a
#                               volume in production if you want the dump to survive
#                               container restart.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp"

RUN chown -R appuser:appuser /app
USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
