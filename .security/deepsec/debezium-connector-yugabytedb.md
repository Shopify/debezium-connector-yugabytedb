# debezium-connector-yugabytedb

## What this codebase does

A Kafka Connect source connector, a fork of the YugabyteDB Debezium connector (Debezium 1.9.x), that streams row changes from YugabyteDB into Kafka. The gRPC connector (`YugabyteDBgRPCConnector`, `YugabyteDBConnectorTask`) talks to the YugabyteDB master and tablet servers through the `YBClient` library (`YBClientUtils.getYbClient`), lists the tablets of a CDC stream (`database.streamid`), polls `GetChanges` per tablet and commits checkpoints back to the server (`YugabyteDBStreamingChangeEventSource`). Records pass through `YugabyteDBChangeRecordEmitter`, the value converters and the event dispatcher, then Kafka Connect writes them to Kafka. Optional transforms live in `connector/yugabytedb/transforms` and `io/aiven/kafka/connect/transforms`. A JDBC path (`YugabyteDBConnection`, `YugabyteDBReplicationConnection`) reads metadata over YSQL. When a connector uses a replication slot instead of `database.streamid`, `YugabyteDBConnectorConfig` and `YugabyteDBTablePoller` query `pg_replication_slots` and `pg_publication_tables` and create or alter publications and slots over YSQL. Initial snapshots run through `YugabyteDBSnapshotChangeEventSource` and the `snapshot/` snapshotters; stream position is kept in `YugabyteDBOffsetContext` and `SourceInfo` in Kafka Connect offsets. The protobuf classes for `src/main/proto/pg_logicaldec.proto` are generated at build time into `generated-sources/` and are not checked in. There is no HTTP API and no end-user traffic. The data handled is whatever rows the captured tables hold, which may include personal data.

## What ships and what does not

- `main` is not the deployed code. The production image is built from the `production-vdz.1.9.5.yb.grpc.2026.1.2.0.1` branch with its `.shopify-build/Dockerfile`, which does not exist on `main`. That Dockerfile runs `mvn clean package` in a Maven/JDK build image and downloads the PostgreSQL JDBC jar; the Kafka Connect runtime image that consumes the jar is built outside this repository.
- The production branch carries patches on top of `main` in `YBClientUtils`, `YugabyteDBChangeRecordEmitter`, `YugabyteDBConsistentStreamingSource`, `YugabyteDBStreamingChangeEventSource` and `YugabyteDBgRPCConnector`. Findings in those classes apply to production; check them against that branch when the code differs.
- The top-level `Dockerfile` builds on `quay.io/yugabyte/debezium-connect-base:latest` and is built by the `docker-maven-plugin` in `pom.xml` as `quay.io/yugabyte/debezium-connector`, which `.github/workflows/build-and-push.yml` pushes. It is not the production runtime image and sets no `USER`, so it inherits the base image's user.
- `base_source_image/Dockerfile` builds a Kafka Connect base image that creates and switches to a non-root `kafka` user (UID 1001). It is not the production runtime image either.
- Treat findings in these Dockerfiles and the GitHub workflows as affecting the published community images and CI, not the production deployment.

## Auth shape

- No user auth. The connector authenticates to YugabyteDB with the `database.user` and `database.password` settings (`Type.PASSWORD` fields in `YugabyteDBConnectorConfig`) for the JDBC path.
- The gRPC path uses TLS files named in config: `database.sslrootcert`, `database.sslcert`, `database.sslkey` (see `sslRootCert()`, `sslClientCert()`, `sslClientKey()` and the `.sslCertFile(...)` / `.sslClientCertFiles(...)` calls in `YBClientUtils`). `database.sslmode` and `database.sslfactory` configure the JDBC driver. `database.sslfactory` may name `NonValidatingFactory`, which disables certificate validation.
- Kafka credentials and the Connect worker config come from the deployment environment and `base_source_image/docker-entrypoint.sh`.
- The top-level `Dockerfile` sets `-Djdk.tls.client.protocols=TLSv1.2` through `KAFKA_OPTS`; the production runtime image is not built here, so its user and JVM options are not visible in this repository.

## Threat model

1. Credential exposure: database passwords, the SSL key password or key file paths, and Kafka credentials written to logs, exception messages, JMX attributes or image layers.
2. Row data leaking to logs or metrics: change event values, keys or `Struct`s logged at any level.
3. Unvalidated TLS: code paths that skip server certificate verification (`sslmode` handling, `NonValidatingFactory`, trust-all managers) or silently fall back to plaintext when the TLS files are unset.
4. Integrity of stream position: checkpoints committed for the wrong tablet or stream (`YBClientUtils.setCheckpoint`, offset contexts) cause lost or replayed changes.
5. Supply chain of the images and CI: unpinned downloads in the Dockerfiles (`curl` of jars with no checksum), GitHub workflows with broad token permissions or secrets reachable from pull requests.
6. SQL built from config values: the slot and publication helpers in `YugabyteDBConnectorConfig` and `YugabyteDBTablePoller` concatenate `slot.name`, `publication.name` and the table include list into SQL strings. `slot.name` is validated against `[a-z0-9_]{1,63}`; `publication.name` has no validator. Connector config is set by whoever can call the Kafka Connect REST API.
7. Untrusted input into decoders: `connection/wal2json`, `connection/pgproto` and `connection/pgoutput` parse server messages (JSON, protobuf, binary); the server is trusted but malformed data should not crash or exhaust memory.

## Project-specific patterns to flag

- A `LOGGER` call that includes a record, a value, a `Struct`, the `Configuration` object or the full connector config map (config contains `database.password` and `database.sslpassword`).
- An error path that builds an exception message from config values or connection strings that carry a password.
- TLS options read from config but not passed on to `AsyncYBClientBuilder`, or an SSL mode that reads as "required" but results in no certificate check.
- Reflection or `Class.forName` driven by a config value other than the documented `database.sslfactory`, or deserialization of data received from the server.
- Dockerfile steps that download artifacts over the network without a pinned digest, or that run as root; workflow steps with `permissions: write-all` or `pull_request_target` combined with secrets.
- SQL assembled with `+` or `String.format` from a config value or table name instead of a `PreparedStatement` parameter or a quoted identifier.
- The Aiven `ExtractTopic` transform sets the record topic from a key or value field, so row content picks the destination topic.
- `base_source_image/Dockerfile` downloads `kafka-connect-start.sh` from the `main` branch of the upstream Debezium container-images repository at build time; `base_source_image/docker-entrypoint.sh` copies everything in `$KAFKA_CONNECT_PLUGINS` into the plugin path at start.
- `.github/workflows/build-and-push.yml` runs on `workflow_dispatch` with `permissions: write-all` and uses the registry and `GITHUB_TOKEN` secrets; the `pull_request` workflows log in to the image registry with repository secrets.
- JMX or metrics tags that include credentials, hostnames of the database or other sensitive config (see `metrics/` and `metrics.yml`).

## Known false-positives

- `src/test/**`: fixtures and local test harnesses that connect to a throwaway YugabyteDB.
- `README.md`, `NOTES.md`, `RDS.md` and other docs show example passwords and hostnames.
- Fields typed `Type.PASSWORD` are masked by Kafka Connect in config output by design.
- `database.sslfactory` accepting `NonValidatingFactory` is an upstream documented option for development; flag it only if a default or a production path selects it.
- The wal2json, pgoutput and pgproto decoders are inherited from upstream Debezium and receive data from the trusted database server, not from end users.
- Version strings and Maven coordinates in `pom.xml` are not secrets.
- Upstream-only code outside the priority paths is reviewed as usual; do not report style or deprecation issues.
