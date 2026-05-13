FROM eclipse-temurin:17-jdk-jammy

WORKDIR /app

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

COPY src ./src

RUN ./mvnw clean package -DskipTests

COPY start.sh ./start.sh
RUN chmod +x ./start.sh

EXPOSE 8080

CMD ["java", "-Xmx256m", "-jar", "target/premier-0.0.1-SNAPSHOT.jar"]