# Stage 1: Build with Maven (includes frontend build via frontend-maven-plugin)
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# Copy all module POMs first for better layer caching
COPY pom.xml pom.xml
COPY common-api/pom.xml common-api/pom.xml
COPY frontend-web/pom.xml frontend-web/pom.xml
COPY app/pom.xml app/pom.xml

# Pre-download backend dependencies (cached unless POMs change)
RUN mvn -B dependency:go-offline -pl common-api,app -am -q

# Copy sources
COPY common-api/src common-api/src
COPY frontend-web/ frontend-web/
COPY app/src app/src

# Full build: frontend (npm install + vite build) + Java (compile + package)
RUN mvn -B clean package -DskipTests -Djava.awt.headless=true

# Stage 2: Runtime image
FROM eclipse-temurin:21-jre-alpine

# Install fonts for headless ImageJ (needed for image rendering)
RUN apk add --no-cache fontconfig freetype ttf-dejavu \
    && addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

COPY --from=build /app/app/target/app-*.jar focusstack.jar

# Ensure data directories exist and are writable
RUN mkdir -p /app/data/images /app/data/search-index \
    && chown -R appuser:appgroup /app

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-Djava.awt.headless=true", "-jar", "focusstack.jar"]
