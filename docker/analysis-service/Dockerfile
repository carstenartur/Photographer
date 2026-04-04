# Stage 1: Build the entire backend (common-api + analysis-service)
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# Copy parent POM and all module POMs first for dependency caching
COPY backend/pom.xml backend/pom.xml
COPY backend/common-api/pom.xml backend/common-api/pom.xml
COPY backend/camera-service/pom.xml backend/camera-service/pom.xml
COPY backend/analysis-service/pom.xml backend/analysis-service/pom.xml

# Download dependencies (cached unless POMs change)
RUN mvn -B -f backend/pom.xml dependency:go-offline -pl common-api,analysis-service -am

# Copy source code
COPY backend/common-api/src backend/common-api/src
COPY backend/analysis-service/src backend/analysis-service/src

# Build common-api and analysis-service
RUN mvn -B -f backend/pom.xml clean package -pl common-api,analysis-service -am -DskipTests

# Stage 2: Runtime with headless AWT support for ImageJ
FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache fontconfig freetype ttf-dejavu \
    && addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

COPY --from=build /app/backend/analysis-service/target/analysis-service-*.jar app.jar

RUN chown -R appuser:appgroup /app
USER appuser

EXPOSE 8081

ENTRYPOINT ["java", "-Djava.awt.headless=true", "-jar", "app.jar"]
