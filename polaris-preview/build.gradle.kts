plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    // Provided by the IDE at runtime; the plugin must not bundle them.
    compileOnly(kotlin("stdlib"))
    compileOnly(libs.coroutines.core)
    compileOnly(libs.slf4j.api)

    api(project(":polaris-client"))
    api(libs.iceberg.core)
    implementation(libs.iceberg.data)
    implementation(libs.iceberg.parquet)
    implementation(libs.iceberg.aws)
    // Iceberg registers its generic readers for Parquet, ORC and Avro together; without the ORC
    // module the registration fails and reads fall back to a legacy path.
    implementation(libs.iceberg.orc)
    implementation(platform(libs.awssdk.bom))
    implementation(libs.awssdk.s3)
    // iceberg-aws reads KMS and STS types while it parses its properties, even when neither is used.
    implementation(libs.awssdk.kms)
    implementation(libs.awssdk.sts)
    implementation(libs.awssdk.urlconnection)
    // Parquet reads through Hadoop's Configuration and a few input-format classes: hadoop-common
    // without its dependencies, plus what Configuration itself loads, instead of ~48 MB of the
    // shaded Hadoop client.
    implementation(libs.hadoop.common) { isTransitive = false }
    implementation(libs.hadoop.mapreduce.core) { isTransitive = false }
    implementation(libs.hadoop.shaded.guava)
    implementation(libs.woodstox)
    implementation(libs.stax2)
    implementation(libs.commons.collections)

    testImplementation(kotlin("stdlib"))
    testImplementation(libs.coroutines.core)
    testImplementation(libs.serialization.json)
    testImplementation(libs.slf4j.api)
    testRuntimeOnly(libs.slf4j.simple)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.testcontainers)
    testRuntimeOnly(libs.junit.platform.launcher)
}

configurations.all {
    // S3FileIO runs on the URL-connection client; the async Netty client is never used.
    exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    exclude(group = "io.netty")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Seeds a local stand started by scripts/local-polaris.sh with the test data.
tasks.register<JavaExec>("seedLocal") {
    group = "verification"
    description = "Writes the preview test data into a running local Polaris and SeaweedFS"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.alexey_samodurov.polaris.preview.LocalSeedKt")
    args(
        providers.gradleProperty("polarisUrl").orElse("http://localhost:8181").get(),
        providers.gradleProperty("s3Endpoint").orElse("http://localhost:8333").get(),
        providers.gradleProperty("catalog").orElse("demo").get(),
    )
}
