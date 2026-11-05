# Single image: Vue UI is built into the Spring Boot jar's static resources.
# On an air-gapped network, point npm and Maven at your internal mirrors
# (e.g. --build-arg NPM_REGISTRY=https://nexus.local/repository/npm/ and a settings.xml).

# ---- 1. UI ----
FROM node:22-alpine AS ui
ARG NPM_REGISTRY=https://registry.npmjs.org/
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm config set registry "$NPM_REGISTRY" && npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npx vue-tsc -b && npx vite build --outDir /ui-dist

# ---- 2. Backend ----
FROM maven:3.9-eclipse-temurin-21 AS api
WORKDIR /src/backend
COPY backend/pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY backend/src ./src
COPY --from=ui /ui-dist ./src/main/resources/static
RUN mvn -q -B package -DskipTests=false

# ---- 3. Runtime ----
FROM eclipse-temurin:21-jre
RUN useradd -u 10001 -r -s /sbin/nologin app
WORKDIR /app
COPY --from=api /src/backend/target/flow-deployer-*.jar app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Djava.io.tmpdir=/tmp", "-jar", "/app/app.jar"]
