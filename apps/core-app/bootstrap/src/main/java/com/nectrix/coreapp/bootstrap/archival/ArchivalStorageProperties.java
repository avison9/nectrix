package com.nectrix.coreapp.bootstrap.archival;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * See application.yml's {@code nectrix.archival.*} block. Same shape as {@code
 * crypto.config.EnvelopeEncryptionProperties} — {@code endpointOverride} is set only for MinIO
 * (local dev/CI, docker-compose.yml); real production blob storage resolves its endpoint from
 * {@code region} alone via the SDK's normal provider chain.
 *
 * @param bucket the archival bucket name (e.g. "nectrix-archival-dev").
 * @param region standard AWS region — required even against MinIO (the SDK validates it's non-null,
 *     though MinIO itself ignores the value).
 * @param endpointOverride nullable — set only for MinIO.
 * @param accessKey nullable — set only for MinIO. Unlike LocalStack (which accepts any dummy value
 *     for KMS, see AwsEnvelopeKmsClient), MinIO genuinely validates credentials against its own
 *     {@code MINIO_ROOT_USER}/{@code MINIO_ROOT_PASSWORD} (docker-compose.yml) — a request with the
 *     wrong access key fails with a real 403, not a silent no-op.
 * @param secretKey nullable — set only for MinIO, paired with {@code accessKey}.
 */
@ConfigurationProperties(prefix = "nectrix.archival")
public record ArchivalStorageProperties(
    String bucket, String region, String endpointOverride, String accessKey, String secretKey) {}
