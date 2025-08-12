# This Dockerfile is used by cdc-core to build the Debezium Image for the Shopify CDC in that buildkite pipeline

# From the Buildkite Agent
ARG SHOPIFY_DEBEZIUM_URL

FROM ${SHOPIFY_DEBEZIUM_URL} as debezium_builder

USER root

WORKDIR /app/src/yugabyte
COPY . /app/src/yugabyte

# Install the components needed to build the project
RUN --mount=type=secret,id=maven_read,dst=/root/.m2/settings.xml \
    --mount=type=cache,target=/root/.m2 \
    mvn clean install -DskipITs -DskipTests
