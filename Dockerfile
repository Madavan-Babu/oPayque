# Using Multi-Stage build to keep it light for AWS ECR (and my wallet)
# Stage 1: Build the JAR
FROM maven:3.8.5-openjdk-17-slim AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Light-weight Runtime
FROM openjdk:17-jdk-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
# This keeps the image size under 200MB
ENTRYPOINT ["java", "-jar", "app.jar"]