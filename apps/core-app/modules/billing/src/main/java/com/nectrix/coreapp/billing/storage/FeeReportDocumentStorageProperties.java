package com.nectrix.coreapp.billing.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * See application.yml's {@code nectrix.documents.*} block — same bucket {@code
 * trading.storage.AgreementDocumentStorageProperties} uses for signed agreements (different key
 * prefix, {@code fee-reports/} vs {@code agreements/}), each module owning its own small duplicate
 * client rather than a shared cross-module dependency (same "no shared infra module" precedent
 * {@code crypto.kms.AwsEnvelopeKmsClient}/{@code bootstrap.archival.ArchivalBlobStorageClient}
 * already established).
 *
 * <p>Named {@code FeeReport*}, not the bare {@code DocumentStorageProperties} name — see {@code
 * AgreementDocumentStorageProperties}' own Javadoc for why a bare-name collision between the two is
 * a real Spring bean-name conflict, not just a naming preference.
 *
 * @param endpointOverride nullable — set only for MinIO (local dev/CI); used for core-app's own
 *     server-to-server PUT/GET, over the docker-compose network.
 * @param publicEndpointOverride nullable — set only for MinIO; the browser-reachable equivalent the
 *     presigner signs against instead (see {@code AgreementDocumentStorageClient}'s identical
 *     reasoning).
 * @param accessKey nullable — set only for MinIO, which genuinely validates credentials.
 * @param secretKey nullable — set only for MinIO, paired with {@code accessKey}.
 */
@ConfigurationProperties(prefix = "nectrix.documents")
public record FeeReportDocumentStorageProperties(
    String bucket,
    String region,
    String endpointOverride,
    String publicEndpointOverride,
    String accessKey,
    String secretKey) {}
