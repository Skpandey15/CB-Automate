FROM eclipse-temurin:17-jdk-jammy AS builder
WORKDIR /workspace
COPY . .
RUN ./gradlew :cb-pr:bootJar --no-daemon -q

FROM eclipse-temurin:17-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends \
    git && rm -rf /var/lib/apt/lists/*
RUN groupadd -r cbapp && useradd -r -g cbapp -u 1001 cbapp
WORKDIR /app
COPY --from=builder /workspace/cb-pr/build/libs/*.jar app.jar
RUN chown cbapp:cbapp app.jar
RUN mkdir -p /workspace/repo && chown cbapp:cbapp /workspace/repo
USER cbapp
EXPOSE 8084
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8084/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", \
            "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
