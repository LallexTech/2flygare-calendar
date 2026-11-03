FROM node:24-alpine AS frontend
WORKDIR /frontend
COPY package.json package-lock.json ./
RUN npm ci
COPY frontend ./frontend
RUN npm run build

FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src ./src
COPY --from=frontend /frontend/src/main/resources/static/planner ./src/main/resources/static/planner
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre-jammy
RUN groupadd --system flygare && useradd --system --gid flygare --home-dir /app flygare
WORKDIR /app
COPY --from=build --chown=flygare:flygare /build/target/flygare-0.0.1-SNAPSHOT.jar app.jar
USER flygare
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65", "-jar", "/app/app.jar"]
