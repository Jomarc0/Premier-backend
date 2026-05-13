# Use official OpenJDK 17 slim image
FROM openjdk:17-jdk-slim

# Set working directory
WORKDIR /app

# Copy Maven wrapper + pom.xml
COPY .mvn/ .mvnw pom.xml ./

# Download dependencies (cached layer)
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

# Copy source code
COPY src ./src

# Build JAR (skip tests for speed)
RUN ./mvnw clean package -DskipTests

# Expose port
EXPOSE 8080

# Run JAR — use shell form so wildcard works
CMD java -jar target/*.jar