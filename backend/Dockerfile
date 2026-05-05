# syntax=docker/dockerfile:1.6
#
# Production multi-stage build for the SLParcels backend (Spring Boot 4 / Java 26).
#
# - Stage 1 (build):    JDK + Maven, builds the fat jar, then extracts Spring
#                       Boot's layered structure for efficient image caching
# - Stage 2 (runtime):  JRE-only, non-root, ALB-curl, layered jar dropped in
#
# Final image is ~200 MB (JRE-Alpine + extracted layers + curl) vs. the
# ~600 MB Dockerfile.dev (full JDK + Maven cache). ECS Exec works because
# alpine ships with /bin/sh.

# =============================================================================
# Stage 1: build the fat jar + extract layered structure
# =============================================================================

FROM eclipse-temurin:26-jdk-alpine AS build
WORKDIR /workspace

# Cache Maven deps independently of source: changing only src/ should not
# re-trigger the dependency download layer.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

# Build. Tests run in CI, not in the production image build (avoids needing
# Postgres / WireMock / Redis at image-build time).
COPY src/ src/
RUN ./mvnw -B -q package -DskipTests

# Extract Spring Boot's layered jar so the runtime stage can copy each layer
# separately. Layers in cache-friendly order (most stable to most volatile):
#   dependencies/         (third-party deps, rarely change)
#   spring-boot-loader/   (Spring Boot loader classes)
#   snapshot-dependencies/ (in-house snapshot deps if any)
#   application/          (your code, changes every deploy)
RUN java -Djarmode=layertools -jar target/*.jar extract

# =============================================================================
# Stage 2: runtime
# =============================================================================

FROM eclipse-temurin:26-jre-alpine
WORKDIR /app

# curl for ALB healthcheck (the task definition's healthCheck.command shells
# out to `curl -f http://localhost:8080/api/v1/health`). Alpine ships busybox
# wget but the ECS task definition is wired to curl.
RUN apk add --no-cache curl

# Non-root execution — defense in depth for any container escape vector.
RUN addgroup -S slpa && adduser -S slpa -G slpa

# Copy layered jar contents in cache-stable order. Each COPY is a separate
# image layer; deps change rarely, application/ changes every deploy.
COPY --from=build --chown=slpa:slpa /workspace/dependencies/ ./
COPY --from=build --chown=slpa:slpa /workspace/spring-boot-loader/ ./
COPY --from=build --chown=slpa:slpa /workspace/snapshot-dependencies/ ./
COPY --from=build --chown=slpa:slpa /workspace/application/ ./

USER slpa

# JVM container-awareness: use up to 75% of the cgroup memory limit. Fail
# fast on OOM rather than thrashing - ECS will restart the task. Override
# per-environment via the ECS task definition's `environment` block.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080

# Spring Boot 3.2+ moved JarLauncher to .launch namespace; Spring Boot 4 keeps it.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
