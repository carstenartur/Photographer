# Stage 1: Build — Maven (frontend-maven-plugin manages its own Node for React build)
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# Copy all POMs first for better layer caching
COPY pom.xml pom.xml
COPY common-api/pom.xml common-api/pom.xml
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
COPY frontend-web/pom.xml frontend-web/pom.xml
COPY app/pom.xml app/pom.xml

# Download dependencies
RUN mvn -B dependency:go-offline -pl common-api,capture-core,storage,camera-ptp,hdr-core,video-core,face-analysis-core,face-profile-core,session-core,observer-core,ai-vision,app -am -DskipITs=true

# Copy source code
COPY common-api/src common-api/src
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
COPY frontend-web/ frontend-web/
COPY app/src app/src

# Build: compile all modules, build frontend, compile app, copy frontend into static/
RUN mvn -B clean package -pl common-api,capture-core,storage,camera-ptp,hdr-core,video-core,face-analysis-core,face-profile-core,session-core,observer-core,ai-vision,frontend-web,app -am \
    -DskipTests -DskipITs=true \
    -Djava.awt.headless=true

# Stage 2: Runtime — slim JRE with fonts for ImageJ headless mode
FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache fontconfig freetype ttf-dejavu \
    && addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

COPY --from=build /app/app/target/focusstack.jar app.jar

# Pre-create volume mount-points so they inherit appuser ownership
# when Docker initializes empty named volumes.
RUN mkdir -p /app/data/search-index /app/images \
    && chown -R appuser:appgroup /app
USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-Djava.awt.headless=true", "-jar", "app.jar"]
