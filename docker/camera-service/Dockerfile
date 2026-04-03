# Stage 1: Build the entire backend (common-api + camera-service)
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# Copy parent POM and all module POMs first for dependency caching
COPY backend/pom.xml backend/pom.xml
COPY backend/common-api/pom.xml backend/common-api/pom.xml
COPY backend/camera-service/pom.xml backend/camera-service/pom.xml
COPY backend/analysis-service/pom.xml backend/analysis-service/pom.xml

# Download dependencies (cached unless POMs change)
RUN mvn -B -f backend/pom.xml dependency:go-offline -pl common-api,camera-service -am

# Copy source code
COPY backend/common-api/src backend/common-api/src
COPY backend/camera-service/src backend/camera-service/src

# Build common-api and camera-service
RUN mvn -B -f backend/pom.xml clean package -pl common-api,camera-service -am -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

COPY --from=build /app/backend/camera-service/target/camera-service-*.jar app.jar

RUN chown -R appuser:appgroup /app
USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
