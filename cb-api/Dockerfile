FROM eclipse-temurin:17-jdk-jammy AS builder
WORKDIR /workspace
COPY . .
RUN ./gradlew :cb-api:bootJar --no-daemon -q

FROM eclipse-temurin:17-jre-jammy
RUN groupadd -r cbapp && useradd -r -g cbapp -u 1001 cbapp

# ADR-0002 Track A: RemediationRunService shells out to
# scripts/dependency-remediation/remediate.py (ADR-0001), which itself
# needs git and the gh CLI (for --publish's push/PR-create/email steps).
RUN apt-get update && apt-get install -y --no-install-recommends \
        python3 git curl ca-certificates gnupg \
    && curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg \
        | gpg --dearmor -o /usr/share/keyrings/githubcli-archive-keyring.gpg \
    && chmod go+r /usr/share/keyrings/githubcli-archive-keyring.gpg \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" \
        > /etc/apt/sources.list.d/github-cli.list \
    && apt-get update && apt-get install -y --no-install-recommends gh \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=builder /workspace/cb-api/build/libs/*.jar app.jar
# RemediationRunService's default remediation.script-path
# ("scripts/dependency-remediation/remediate.py") is relative to the
# JVM's working directory, which is /app here.
COPY --from=builder /workspace/scripts/dependency-remediation ./scripts/dependency-remediation
RUN chown -R cbapp:cbapp app.jar scripts
USER cbapp
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", \
            "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
