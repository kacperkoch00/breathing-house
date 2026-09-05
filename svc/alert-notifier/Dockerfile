FROM docker.io/library/maven:3.9-eclipse-temurin-21 AS build

WORKDIR /src
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B package -DskipTests

FROM docker.io/library/eclipse-temurin:21-jre
RUN useradd --system --no-create-home app
COPY --from=build /src/target/alert-notifier-0.1.0-SNAPSHOT.jar /opt/app/alert-notifier.jar
USER app
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/opt/app/alert-notifier.jar"]
