# Development

How to use the plugin: [README.md](README.md).

## Build

Gradle needs JDK 17+ to start; the build itself uses a JDK 25 toolchain.

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 25)   # macOS; any JDK 25 elsewhere
./gradlew build                       # compile, unit tests, Polaris 1.4.1 and SeaweedFS in Testcontainers
./gradlew :plugin:verifyPlugin        # Plugin Verifier, 2026.2.x and 2026.3.x
./gradlew :plugin:runIde              # an IDE with the plugin installed, in a sandbox
./gradlew :plugin:buildPlugin         # the zip in plugin/build/distributions/
```

The integration tests need Docker; without it they are reported as skipped.

To try the plugin against a local stand (Polaris 1.4.1 and SeaweedFS 4.28 with
STS off, seeded with the test data):

```sh
./scripts/local-polaris.sh
```

Connect with `http://localhost:8181`, realm `POLARIS`, client `root` / `s3cr3t`;
for the Data tab, S3 key `reader` / `reader-secret`, endpoint
`http://localhost:8333`, path-style. Catalog `demo` holds `cdc.events`, whose
rows were deleted and updated through deletion vectors.

## Layout

| Module | What |
| --- | --- |
| `polaris-client` | Plain Kotlin, no IntelliJ dependency: OAuth2 token, Iceberg REST and management API subset, metadata model |
| `polaris-preview` | Plain Kotlin: reads rows with the Iceberg Java SDK 1.12.0, reading sessions, S3 access |
| `plugin` | Tool window, tree, details, settings, data preview |

Design notes worth keeping in mind:

- Metadata is read by `polaris-client`, a thin REST client with no Jackson and
  no Iceberg; rows are read by `polaris-preview` with the Iceberg Java SDK, so
  deletion vectors, position and equality deletes are applied.
- The IDE provides Kotlin, kotlinx.coroutines, kotlinx.serialization and SLF4J;
  the plugin never bundles them. `unzip -l` on the zip must not list them.
- Iceberg and the AWS SDK load classes through the thread's context class
  loader; preview calls run with the plugin's loader as context loader
  (`OwnClassLoader`), or they fail inside the IDE while tests pass.
- A preview is a session: "Load More" continues the same scan, because two
  scans of one snapshot do not return rows in the same order.

## IDE versions

The plugin supports the two latest major IDE releases. When a new one ships,
move `pycharmCompile`, `pycharmLatestPrevious` and `pycharmLatestCurrent` in
`gradle/libs.versions.toml` and `sinceBuild` in `plugin/build.gradle.kts` up by
one release, then run `verifyPlugin`.

## Release

1. Set `version` in `gradle.properties` and add the release to `CHANGELOG.md`.
2. Regenerate the third-party list:
   `./gradlew :plugin:buildPlugin && python3 -I scripts/third-party.py > THIRD-PARTY.md`.
3. Tag `vX.Y.Z` and push the tag. The `release` workflow builds, verifies,
   signs and publishes to JetBrains Marketplace, and attaches the zip to a
   GitHub release.

The first upload of a new plugin to Marketplace is manual: Upload plugin on
<https://plugins.jetbrains.com>, with the zip from `buildPlugin`. Later versions
come from the workflow.

Repository secrets the workflow uses:

| Secret | What |
| --- | --- |
| `PUBLISH_TOKEN` | A JetBrains Marketplace token (profile → My Tokens) |
| `CERTIFICATE_CHAIN` | The signing certificate chain, PEM |
| `PRIVATE_KEY` | The signing private key, PEM |
| `PRIVATE_KEY_PASSWORD` | Its password |

To create a signing key and certificate:

```sh
openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096
openssl rsa -in private_encrypted.pem -out private.pem
openssl req -key private.pem -new -x509 -days 3650 -out chain.crt
```

Keep the key out of the repository. Locally, `./gradlew :plugin:signPlugin`
and `:plugin:publishPlugin` read the same values from the environment.
