# debezium-connector-yugabytedb

## What this codebase does

A Kafka Connect source connector, a fork of the YugabyteDB Debezium connector (Debezium 1.9.x), that streams row changes from YugabyteDB into Kafka. The gRPC connector (`YugabyteDBgRPCConnector`, `YugabyteDBConnectorTask`) talks to the YugabyteDB master and tablet servers through the `YBClient` library (`YBClientUtils.getYbClient`), lists the tablets of a CDC stream (`database.streamid`), polls `GetChanges` per tablet and commits checkpoints back to the server (`YugabyteDBStreamingChangeEventSource`). Records pass through `YugabyteDBChangeRecordEmitter`, the value converters and the event dispatcher, then Kafka Connect writes them to Kafka. Optional transforms live in `connector/yugabytedb/transforms` and `io/aiven/kafka/connect/transforms`. A JDBC path (`YugabyteDBConnection`, `YugabyteDBReplicationConnection`) reads metadata over YSQL. The repository also builds two container images: `base_source_image/` (Kafka Connect runtime) and the top-level `Dockerfile` (adds the connector jar, JDBC drivers and the JMX Prometheus agent). There is no HTTP API and no end-user traffic. The data handled is whatever rows the captured tables hold, which may include personal data.

## Auth shape

- No user auth. The connector authenticates to YugabyteDB with the `database.user` and `database.password` settings (`Type.PASSWORD` fields in `YugabyteDBConnectorConfig`) for the JDBC path.
- The gRPC path uses TLS files named in config: `database.sslrootcert`, `database.sslcert`, `database.sslkey` (see `sslRootCert()`, `sslClientCert()`, `sslClientKey()` and the `.sslCertFile(...)` / `.sslClientCertFiles(...)` calls in `YBClientUtils`). `database.sslmode` and `database.sslfactory` configure the JDBC driver. `database.sslfactory` may name `NonValidatingFactory`, which disables certificate validation.
- Kafka credentials and the Connect worker config come from the deployment environment and `base_source_image/docker-entrypoint.sh`.
- The Docker image runs as the non-root `kafka` user and sets `-Djdk.tls.client.protocols=TLSv1.2` through `KAFKA_OPTS`.

## Threat model

1. Credential exposure: database passwords, the SSL key password or key file paths, and Kafka credentials written to logs, exception messages, JMX attributes or image layers.
2. Row data leaking to logs or metrics: change event values, keys or `Struct`s logged at any level.
3. Unvalidated TLS: code paths that skip server certificate verification (`sslmode` handling, `NonValidatingFactory`, trust-all managers) or silently fall back to plaintext when the TLS files are unset.
4. Integrity of stream position: checkpoints committed for the wrong tablet or stream (`YBClientUtils.setCheckpoint`, offset contexts) cause lost or replayed changes.
5. Supply chain of the images and CI: unpinned downloads in the Dockerfiles (`curl` of jars with no checksum), GitHub workflows with broad token permissions or secrets reachable from pull requests.
6. Untrusted input into decoders: `connection/wal2json`, `connection/pgproto` and `connection/pgoutput` parse server messages (JSON, protobuf, binary); the server is trusted but malformed data should not crash or exhaust memory.

## Project-specific patterns to flag

- A `LOGGER` call that includes a record, a value, a `Struct`, the `Configuration` object or the full connector config map (config contains `database.password` and `database.sslpassword`).
- An error path that builds an exception message from config values or connection strings that carry a password.
- TLS options read from config but not passed on to `AsyncYBClientBuilder`, or an SSL mode that reads as "required" but results in no certificate check.
- Reflection or `Class.forName` driven by a config value other than the documented `database.sslfactory`, or deserialization of data received from the server.
- Dockerfile steps that download artifacts over the network without a pinned digest, or that run as root; workflow steps with `permissions: write-all` or `pull_request_target` combined with secrets.
- JMX or metrics tags that include credentials, hostnames of the database or other sensitive config (see `metrics/` and `metrics.yml`).

## Known false-positives

- `src/test/**`: fixtures and local test harnesses that connect to a throwaway YugabyteDB.
- `README.md`, `NOTES.md`, `RDS.md` and other docs show example passwords and hostnames.
- Fields typed `Type.PASSWORD` are masked by Kafka Connect in config output by design.
- `database.sslfactory` accepting `NonValidatingFactory` is an upstream documented option for development; flag it only if a default or a production path selects it.
- The wal2json, pgoutput and pgproto decoders are inherited from upstream Debezium and receive data from the trusted database server, not from end users.
- Version strings and Maven coordinates in `pom.xml` are not secrets.
- Upstream-only code outside the priority paths is reviewed as usual; do not report style or deprecation issues.
