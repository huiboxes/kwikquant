# syntax=docker/dockerfile:1
# -----------------------------------------------------------------------------
# Production image for the KwikQuant backend.
#
# Multi-stage: build the executable jar, then run it on a minimal JRE as a
# non-root user. Layered extraction keeps dependency layers cached across builds.
#
# Build from the repo root:
#   docker build -f docker/Dockerfile -t kwikquant-app .
#
# No secrets are baked in. ALL secrets are injected at runtime via env.
# -----------------------------------------------------------------------------

# ---- Stage 1: build ----------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Warm the dependency cache on the POM first (better layer caching).
COPY pom.xml .
COPY src/ src/

RUN --mount=type=cache,target=/root/.m2 \
    mvn -q clean package -DskipTests

# Extract Spring Boot layers for cache-friendly copying into the runtime image.
WORKDIR /build/target
RUN java -Djarmode=tools -jar kwikquant-0.0.1-SNAPSHOT.jar extract --layers --launcher --destination extracted

# ---- Stage 2: runtime --------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Non-root runtime user + curl for HEALTHCHECK.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system kwik && useradd --system --gid kwik --no-create-home kwik

# Copy layers least- to most-frequently-changing for better caching.
COPY --from=build --chown=kwik:kwik /build/target/extracted/dependencies/ ./
COPY --from=build --chown=kwik:kwik /build/target/extracted/spring-boot-loader/ ./
COPY --from=build --chown=kwik:kwik /build/target/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=kwik:kwik /build/target/extracted/application/ ./

USER kwik
EXPOSE 8080

# Container-friendly heap sizing (respect cgroup limits).
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0"

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]