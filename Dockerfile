# syntax=docker/dockerfile:1
# One Dockerfile for all three services:  docker build --build-arg SERVICE=ledger-service .
# docker-compose.yml uses it for the "apps" profile.

# ---- build stage: compile inside Docker, so the host needs no JDK ----------------
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
# Strip Windows line endings in case the repo was checked out on Windows.
RUN sed -i 's/\r$//' mvnw
COPY payment-service/pom.xml payment-service/
COPY ledger-service/pom.xml ledger-service/
COPY fraud-service/pom.xml fraud-service/
ARG SERVICE
COPY ${SERVICE}/src ${SERVICE}/src
# The cache mount keeps downloaded Maven dependencies between builds.
RUN --mount=type=cache,target=/root/.m2 sh ./mvnw -B -q -pl ${SERVICE} package -DskipTests \
    && cp ${SERVICE}/target/${SERVICE}-*.jar /src/app.jar

# ---- run stage: JRE only, non-root user -----------------------------------------
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /src/app.jar app.jar
USER app
# Size the heap from the container's memory limit, not the host's.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
