output "key_ring_id" {
  value = google_kms_key_ring.envelope.id
}

output "crypto_key_id" {
  value = google_kms_crypto_key.envelope_v1.id
}

output "crypto_key_name" {
  value = google_kms_crypto_key.envelope_v1.name
}
