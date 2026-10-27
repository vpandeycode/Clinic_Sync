FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /app
COPY pom.xml ./
COPY common/pom.xml common/
COPY edge-node/pom.xml edge-node/
COPY central-server/pom.xml central-server/
COPY sim/pom.xml sim/
COPY common/src common/src
COPY edge-node/src edge-node/src
RUN mvn clean package -DskipTests -pl common,edge-node -am

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/edge-node/target/*.jar app.jar
RUN mkdir -p /app/data
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
