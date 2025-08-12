# This Dockerfile is used by cdc-core to build the Debezium Image for the Shopify CDC in that buildkite pipeline
FROM us-docker.pkg.dev/shopify-docker-images/containers/cloud/maven-gradle-sbt-jdk

USER root

WORKDIR /app/src
COPY . /app/src/

# Install the components needed to build the project
RUN --mount=type=secret,id=maven_read,dst=/root/.m2/settings.xml \
    --mount=type=cache,target=/root/.m2 \
    mvn clean install -DskipITs -DskipTests
