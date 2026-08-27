FROM eclipse-temurin:17-jdk AS build

WORKDIR /src
COPY gradle ./gradle
COPY gradlew gradle.properties settings.gradle.kts ./
COPY app ./app
RUN ./gradlew --no-daemon clean installDist

FROM eclipse-temurin:17-jre

RUN apt-get update \
    && apt-get install --no-install-recommends -y ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /src/app/build/install/app /opt/amarr

USER 1000:1000
EXPOSE 8080
ENTRYPOINT ["/opt/amarr/bin/app"]
