package com.nectrix.coreapp.trading.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * See application.yml's {@code nectrix.documents.*} block. Same shape as {@code
 * bootstrap.archival.ArchivalStorageProperties} — a separate bucket ({@code nectrix-documents})
 * from the archival one, since these are live documents a Follower/Master needs to fetch on demand,
 * not an audit trail of deleted accounts.
 *
 * <p>Named {@code Agreement*}, not the bare {@code DocumentStorageProperties} name — {@code
 * billing.storage.FeeReportDocumentStorageProperties} binds to the very same {@code
 * nectrix.documents} prefix for its own client, and a bare-name collision between the two is a real
 * Spring bean-name conflict (both would default to {@code documentStorageProperties}), not just a
 * naming preference — confirmed by {@code ConflictingBeanDefinitionException} the one time this
 * wasn't yet renamed.
 *
 * @param endpointOverride nullable — set only for MinIO (local dev/CI). Used for the actual PUT/GET
 *     calls core-app itself makes server-to-server, over the docker-compose network (e.g. {@code
 *     http://minio:9000}).
 * @param publicEndpointOverride nullable — set only for MinIO. Presigned URLs are handed to a
 *     browser, which can't resolve the internal compose-network hostname {@code endpointOverride}
 *     uses — this is the host-reachable equivalent (e.g. {@code http://localhost:9000}) the
 *     presigner signs against instead. Real production S3 needs neither override: its endpoint is
 *     natively reachable from both core-app and a browser.
 * @param accessKey nullable — set only for MinIO, which genuinely validates credentials (unlike
 *     LocalStack for KMS).
 * @param secretKey nullable — set only for MinIO, paired with {@code accessKey}.
 */
@ConfigurationProperties(prefix = "nectrix.documents")
public record AgreementDocumentStorageProperties(
    String bucket,
    String region,
    String endpointOverride,
    String publicEndpointOverride,
    String accessKey,
    String secretKey) {}
