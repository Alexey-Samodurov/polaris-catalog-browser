#!/usr/bin/env bash
# A local stand for trying the plugin, the way the tests build theirs:
#   * SeaweedFS 4.28 with STS off on localhost:8333, S3 identities polaris (read-write) and
#     reader / reader-secret (read-only, for the Data tab);
#   * apache/polaris:1.4.1 on localhost:8181, realm POLARIS, root / s3cr3t;
#   * catalog "it" (FILE storage): namespaces a and a.b, table a.b.events with two snapshots,
#     view a.recent, and a principal "reader" that may not list catalogs;
#   * catalog "demo" (S3 storage, STS off): table cdc.events, v3, rows deleted and updated
#     through deletion vectors, tag before-merge on the first state.
# Stop with: docker rm -f polaris-browser-local seaweed-browser-local; docker network rm polaris-browser-net
set -euo pipefail

cd "$(dirname "$0")/.."
NAME=polaris-browser-local
SEAWEED=seaweed-browser-local
NET=polaris-browser-net
BASE=http://localhost:8181
docker rm -f "$NAME" "$SEAWEED" >/dev/null 2>&1 || true
docker network create "$NET" >/dev/null 2>&1 || true

S3_CONFIG=$(mktemp)
cat > "$S3_CONFIG" <<'JSON'
{"identities":[
  {"name":"polaris","credentials":[{"accessKey":"polaris","secretKey":"polaris-secret"}],"actions":["Admin","Read","Write","List","Tagging"]},
  {"name":"reader","credentials":[{"accessKey":"reader","secretKey":"reader-secret"}],"actions":["Read","List"]}
]}
JSON
docker create --name "$SEAWEED" --network "$NET" --network-alias seaweedfs -p 8333:8333 -p 9333:9333 \
  chrislusf/seaweedfs:4.28 server -s3 -s3.config=/etc/seaweedfs/s3_config.json -filer -dir=/data \
  -master.volumeSizeLimitMB=64 >/dev/null
chmod 644 "$S3_CONFIG"  # mktemp makes it 0600, which the SeaweedFS user cannot read
docker cp "$S3_CONFIG" "$SEAWEED:/etc/seaweedfs/s3_config.json"
rm -f "$S3_CONFIG"
docker start "$SEAWEED" >/dev/null

docker run -d --name "$NAME" --network "$NET" -p 8181:8181 -p 8182:8182 \
  -e POLARIS_BOOTSTRAP_CREDENTIALS=POLARIS,root,s3cr3t \
  -e polaris.realm-context.realms=POLARIS \
  -e AWS_REGION=us-east-1 -e AWS_ACCESS_KEY_ID=polaris -e AWS_SECRET_ACCESS_KEY=polaris-secret \
  -e AWS_ENDPOINT_URL_S3=http://seaweedfs:8333 -e QUARKUS_S3_ENDPOINT_OVERRIDE=http://seaweedfs:8333 \
  -e QUARKUS_S3_PATH_STYLE_ACCESS=true \
  -e 'polaris.features."ALLOW_INSECURE_STORAGE_TYPES"=true' \
  -e 'polaris.features."SUPPORTED_CATALOG_STORAGE_TYPES"=["FILE","S3"]' \
  -e polaris.readiness.ignore-severe-issues=true \
  apache/polaris:1.4.1 >/dev/null

wait_for() { # what url
  for _ in $(seq 120); do curl -s -o /dev/null "$2" && return 0; sleep 1; done
  echo "$1 did not start; docker logs of it follow" >&2; return 1
}
wait_for Polaris http://localhost:8182/q/health || { docker logs --tail 20 "$NAME" >&2; exit 1; }
wait_for SeaweedFS http://localhost:8333 || { docker logs --tail 20 "$SEAWEED" >&2; exit 1; }

TOKEN=$(curl -sf "$BASE/api/catalog/v1/oauth/tokens" -H 'Polaris-Realm: POLARIS' \
  -d grant_type=client_credentials -d client_id=root -d client_secret=s3cr3t -d scope=PRINCIPAL_ROLE:ALL \
  | sed -E 's/.*"access_token":"([^"]+)".*/\1/')

call() { # method path body
  curl -sf -X "$1" "$BASE$2" -H "Authorization: Bearer $TOKEN" -H 'Polaris-Realm: POLARIS' \
    -H 'Content-Type: application/json' -d "$3"
}

M=/api/management/v1
R=/api/catalog/v1/it
call POST $M/catalogs '{"catalog":{"name":"it","type":"INTERNAL","properties":{"default-base-location":"file:///tmp/polaris/it"},"storageConfigInfo":{"storageType":"FILE","allowedLocations":["file:///tmp/polaris/"]}}}' >/dev/null
call POST $R/namespaces '{"namespace":["a"]}' >/dev/null
call POST $R/namespaces '{"namespace":["a","b"]}' >/dev/null
call POST "$R/namespaces/a%1Fb/tables" '{"name":"events","schema":{"type":"struct","schema-id":0,"identifier-field-ids":[1],"fields":[{"id":1,"name":"id","required":true,"type":"long"},{"id":2,"name":"ts","required":true,"type":"timestamp"},{"id":3,"name":"tags","required":false,"type":{"type":"list","element-id":4,"element":"string","element-required":false}}]},"partition-spec":{"spec-id":0,"fields":[{"source-id":2,"field-id":1000,"name":"ts_day","transform":"day"}]},"write-order":{"order-id":1,"fields":[{"transform":"identity","source-id":1,"direction":"asc","null-order":"nulls-first"}]},"properties":{"format-version":"3","owner":"local","s3.secret-access-key":"shown-masked"}}' >/dev/null
for id in 1 2; do
  parent=""; [ "$id" = 2 ] && parent=',"parent-snapshot-id":1'
  ts=$(( $(date +%s) * 1000 + id * 1000 ))
  call POST "$R/namespaces/a%1Fb/tables/events" "{\"requirements\":[],\"updates\":[{\"action\":\"add-snapshot\",\"snapshot\":{\"snapshot-id\":$id$parent,\"sequence-number\":$id,\"timestamp-ms\":$ts,\"schema-id\":0,\"first-row-id\":0,\"added-rows\":0,\"manifest-list\":\"file:///tmp/polaris/it/a/b/events/metadata/snap-$id.avro\",\"summary\":{\"operation\":\"append\"}}},{\"action\":\"set-snapshot-ref\",\"ref-name\":\"main\",\"type\":\"branch\",\"snapshot-id\":$id}]}" >/dev/null
done
call POST $R/namespaces/a/views '{"name":"recent","schema":{"type":"struct","schema-id":0,"fields":[{"id":1,"name":"id","required":false,"type":"long"}]},"view-version":{"version-id":1,"timestamp-ms":1700000000000,"schema-id":0,"summary":{"engine-name":"spark"},"representations":[{"type":"sql","sql":"select id from a.b.events","dialect":"spark"}],"default-namespace":["a"]},"properties":{}}' >/dev/null

READER=$(call POST $M/principals '{"principal":{"name":"reader"}}')
call POST $M/principal-roles '{"principalRole":{"name":"reader_role"}}' >/dev/null
call PUT $M/principals/reader/principal-roles '{"principalRole":{"name":"reader_role"}}' >/dev/null
call POST $M/catalogs/it/catalog-roles '{"catalogRole":{"name":"reader_cat"}}' >/dev/null
for p in CATALOG_READ_PROPERTIES NAMESPACE_LIST NAMESPACE_READ_PROPERTIES TABLE_LIST TABLE_READ_PROPERTIES VIEW_LIST VIEW_READ_PROPERTIES; do
  call PUT $M/catalogs/it/catalog-roles/reader_cat/grants "{\"grant\":{\"type\":\"catalog\",\"privilege\":\"$p\"}}" >/dev/null
done
call PUT $M/principal-roles/reader_role/catalog-roles/it '{"catalogRole":{"name":"reader_cat"}}' >/dev/null

JAVA_HOME=$(/usr/libexec/java_home -v 25 2>/dev/null || echo "$JAVA_HOME") ./gradlew -q --console=plain :polaris-preview:seedLocal \
  -PpolarisUrl="$BASE" -Ps3Endpoint=http://localhost:8333 -Pcatalog=demo

echo "Polaris is up at $BASE (realm POLARIS)."
echo "Data tab: S3 access key id reader, secret reader-secret, endpoint http://localhost:8333, path-style."
echo "root / s3cr3t lists catalogs."
echo "reader may not list catalogs; name catalog 'it' by hand:"
echo "$READER" | sed -E 's/.*"clientId":"([^"]+)".*"clientSecret":"([^"]+)".*/  client id \1  secret \2/'
echo "Stop with: docker rm -f $NAME $SEAWEED; docker network rm $NET"
