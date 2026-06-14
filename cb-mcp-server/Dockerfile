FROM eclipse-temurin:17-jdk-jammy AS builder
WORKDIR /workspace
COPY . .
RUN ./gradlew :cb-mcp-server:bootJar --no-daemon -q

FROM eclipse-temurin:17-jre-jammy
RUN groupadd -r cbapp && useradd -r -g cbapp -u 1001 cbapp
WORKDIR /app
COPY --from=builder /workspace/cb-mcp-server/build/libs/*.jar app.jar
RUN chown cbapp:cbapp app.jar
USER cbapp
EXPOSE 8086
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8086/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", \
            "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
