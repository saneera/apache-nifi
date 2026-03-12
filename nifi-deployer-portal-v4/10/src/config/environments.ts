
export const environments = {
 DEV: {
  nifi: "http://localhost:8080",
  registry: "http://localhost:18080",
  bucket: "bucket_id"
 },
 TEST: {
  nifi: "http://test-nifi:8080",
  registry: "http://test-registry:18080",
  bucket: "bucket_id"
 },
 PROD: {
  nifi: "http://prod-nifi:8080",
  registry: "http://prod-registry:18080",
  bucket: "bucket_id"
 }
}
