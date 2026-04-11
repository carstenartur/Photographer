# Stage 1: Build — Maven (frontend-maven-plugin manages its own Node for React build)
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# Copy all POMs first for better layer caching
COPY pom.xml pom.xml
COPY common-api/pom.xml common-api/pom.xml
COPY camera-ptp/pom.xml camera-ptp/pom.xml
COPY frontend-web/pom.xml frontend-web/pom.xml
COPY app/pom.xml app/pom.xml

# Download dependencies
RUN mvn -B dependency:go-offline -pl common-api,camera-ptp,app -am -DskipITs=true

# Copy source code
COPY common-api/src common-api/src
COPY camera-ptp/src camera-ptp/src
COPY frontend-web/ frontend-web/
COPY app/src app/src

# Build: compile common-api, camera-ptp, build frontend, compile app, copy frontend into static/
RUN mvn -B clean package -pl common-api,camera-ptp,frontend-web,app -am \
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
