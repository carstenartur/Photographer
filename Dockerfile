# syntax=docker/dockerfile:1.7
# NOTE: This Dockerfile uses BuildKit cache mounts (RUN --mount=type=cache).
# BuildKit is the default builder in Docker 23.0+ and is always used by docker buildx.
# If using an older Docker CLI, set DOCKER_BUILDKIT=1 before running docker build.
# Stage 1: Build — Maven (frontend-maven-plugin manages its own Node for React build)
FROM maven:3.9-eclipse-temurin-25 AS build

WORKDIR /app

# Copy all reactor POMs first for better layer caching. Maven validates every
# module listed by the root POM even when -pl selects only the application graph.
COPY pom.xml pom.xml
COPY common-api/pom.xml common-api/pom.xml
COPY plugin-api/pom.xml plugin-api/pom.xml
COPY capture-core/pom.xml capture-core/pom.xml
COPY storage/pom.xml storage/pom.xml
COPY camera-ptp/pom.xml camera-ptp/pom.xml
COPY hdr-core/pom.xml hdr-core/pom.xml
COPY video-core/pom.xml video-core/pom.xml
COPY face-analysis-core/pom.xml face-analysis-core/pom.xml
COPY face-profile-core/pom.xml face-profile-core/pom.xml
COPY session-core/pom.xml session-core/pom.xml
COPY observer-core/pom.xml observer-core/pom.xml
COPY ai-vision/pom.xml ai-vision/pom.xml
COPY astro-core/pom.xml astro-core/pom.xml
COPY frontend-web/pom.xml frontend-web/pom.xml
COPY app/pom.xml app/pom.xml
COPY canon-cr3-container/pom.xml canon-cr3-container/pom.xml
COPY canon-cr3-metadata/pom.xml canon-cr3-metadata/pom.xml
COPY canon-cr3-codec/pom.xml canon-cr3-codec/pom.xml
COPY canon-cr3-processing/pom.xml canon-cr3-processing/pom.xml
COPY canon-cr3-core/pom.xml canon-cr3-core/pom.xml
COPY canon-cr3-reference-testkit/pom.xml canon-cr3-reference-testkit/pom.xml
COPY canon-cr3-research/pom.xml canon-cr3-research/pom.xml
COPY canon-cr3-imageio/pom.xml canon-cr3-imageio/pom.xml
COPY canon-cr3-maven-smoke/pom.xml canon-cr3-maven-smoke/pom.xml
COPY canon-cr3-cli/pom.xml canon-cr3-cli/pom.xml
COPY canon-cr3-benchmark/pom.xml canon-cr3-benchmark/pom.xml

# Download dependencies — retry up to 3 times to handle transient Maven Central failures
RUN --mount=type=cache,target=/root/.m2 \
    for i in 1 2 3; do \
      mvn -B dependency:go-offline \
          -pl common-api,plugin-api,capture-core,storage,camera-ptp,hdr-core,video-core,face-analysis-core,face-profile-core,session-core,observer-core,ai-vision,astro-core,canon-cr3-core,canon-cr3-imageio,canon-cr3-cli,frontend-web,app -am \
          -DskipITs=true \
          -Dmaven.wagon.http.retryHandler.count=5 \
          -Daether.connector.http.retryHandler.count=5 \
          -Dmaven.wagon.http.pool=true \
      && break || { [ "$i" -lt 3 ] && echo "Attempt $i failed, retrying in 15s..." && sleep 15 || exit 1; }; \
    done

# Copy source code. The application graph reaches the CR3 production layers
# through canon-cr3-core/ImageIO; research, reference-test and Maven-smoke sources
# are not required in the runtime image build.
COPY common-api/src common-api/src
COPY plugin-api/src plugin-api/src
COPY capture-core/src capture-core/src
COPY storage/src storage/src
COPY camera-ptp/src camera-ptp/src
COPY hdr-core/src hdr-core/src
COPY video-core/src video-core/src
COPY face-analysis-core/src face-analysis-core/src
COPY face-profile-core/src face-profile-core/src
COPY session-core/src session-core/src
COPY observer-core/src observer-core/src
COPY ai-vision/src ai-vision/src
COPY astro-core/src astro-core/src
COPY canon-cr3-container/src canon-cr3-container/src
COPY canon-cr3-metadata/src canon-cr3-metadata/src
COPY canon-cr3-codec/src canon-cr3-codec/src
COPY canon-cr3-processing/src canon-cr3-processing/src
COPY canon-cr3-core/src canon-cr3-core/src
COPY canon-cr3-imageio/src canon-cr3-imageio/src
COPY canon-cr3-cli/src canon-cr3-cli/src
COPY frontend-web/ frontend-web/
COPY app/src app/src

# Build: compile all modules, build frontend, compile app, copy frontend into static/
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B clean package \
        -pl common-api,plugin-api,capture-core,storage,camera-ptp,hdr-core,video-core,face-analysis-core,face-profile-core,session-core,observer-core,ai-vision,astro-core,canon-cr3-core,canon-cr3-imageio,canon-cr3-cli,frontend-web,app -am \
        -DskipTests -DskipITs=true \
        -Djava.awt.headless=true \
        -Dmaven.wagon.http.retryHandler.count=5 \
        -Daether.connector.http.retryHandler.count=5

# Stage 2: Runtime — slim JRE with fonts for ImageJ headless mode
FROM eclipse-temurin:25-jre-alpine

RUN apk add --no-cache fontconfig freetype ttf-dejavu libusb \
    && addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

COPY --from=build /app/app/target/focusstack.jar app.jar

# Pre-create volume mount-points so they inherit appuser ownership
# when Docker initializes empty named volumes.
RUN mkdir -p /app/data/search-index /app/images /app/plugins \
    && chown -R appuser:appgroup /app
USER appuser

EXPOSE 8080

ENTRYPOINT ["java", \
    "-XX:MaxRAMPercentage=75.0", \
    "-XX:InitialRAMPercentage=50.0", \
    "-XX:+UseG1GC", \
    "-Djava.awt.headless=true", \
    "--enable-native-access=ALL-UNNAMED", \
    "-jar", "app.jar"]
