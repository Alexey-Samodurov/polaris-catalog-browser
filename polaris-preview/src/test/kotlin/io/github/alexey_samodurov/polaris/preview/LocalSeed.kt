package io.github.alexey_samodurov.polaris.preview

/**
 * Seeds a running local stand (`scripts/local-polaris.sh`) with [PreviewFixture]'s data.
 * Arguments: Polaris base URL, S3 endpoint reachable from this machine, catalog name.
 */
fun main(args: Array<String>) {
    val (base, s3, catalog) = args
    software.amazon.awssdk.services.s3.S3Client.builder()
        .endpointOverride(java.net.URI(s3)).forcePathStyle(true).region(software.amazon.awssdk.regions.Region.US_EAST_1)
        .credentialsProvider(
            software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("polaris", "polaris-secret"),
            ),
        )
        .httpClient(software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient.create())
        .build().use { client ->
            if (client.listBuckets().buckets().none { it.name() == "warehouse" }) client.createBucket { it.bucket("warehouse") }
        }
    val fixture = PreviewFixture(base, s3, catalog)
    val (id, secret) = fixture.setUpCatalog()
    fixture.writeData()
    println("Catalog '$catalog' with table cdc.events written.")
    println("Metadata-only principal: client id $id, secret $secret")
}
