FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /app
COPY pom.xml ./
COPY common/pom.xml common/
COPY edge-node/pom.xml edge-node/
COPY central-server/pom.xml central-server/
COPY sim/pom.xml sim/
COPY common/src common/src
COPY central-server/src central-server/src
RUN mvn clean package -DskipTests -pl common,central-server -am

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/central-server/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
