FROM docker.io/library/maven:3.9-eclipse-temurin-21 AS build

WORKDIR /src
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B package -DskipTests

FROM docker.io/library/eclipse-temurin:21-jre
RUN useradd --system --no-create-home app
COPY --from=build /src/target/sensors-data-collector-0.1.0-SNAPSHOT.jar /opt/app/sensors-data-collector.jar
USER app
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "/opt/app/sensors-data-collector.jar"]
