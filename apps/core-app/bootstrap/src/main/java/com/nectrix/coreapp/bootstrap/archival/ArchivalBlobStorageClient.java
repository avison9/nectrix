package com.nectrix.coreapp.bootstrap.archival;

import java.net.URI;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Real AWS S3 SDK v2 client — same "one code path, real infra locally/in CI via {@code
 * endpointOverride}, real AWS in production" pattern {@code crypto.kms.AwsEnvelopeKmsClient}
 * already established, plus {@code forcePathStyle(true)} — a MinIO-specific requirement (MinIO
 * doesn't support virtual-hosted-style bucket addressing the way real S3 does) KMS's own LocalStack
 * target never needed.
 */
@Component
public class ArchivalBlobStorageClient {

  private final S3Client s3;
  private final String bucket;

  public ArchivalBlobStorageClient(ArchivalStorageProperties props) {
    this.bucket = props.bucket();
    S3ClientBuilder builder = S3Client.builder().region(Region.of(props.region()));
    if (props.endpointOverride() != null && !props.endpointOverride().isBlank()) {
      builder
          .endpointOverride(URI.create(props.endpointOverride()))
          .forcePathStyle(true)
          // Unlike LocalStack (KMS), MinIO genuinely validates credentials — real AWS S3 never
          // takes this branch, its credentials come from the normal provider chain (IRSA
          // in-cluster, ~/.aws/credentials locally, etc).
          .credentialsProvider(
              StaticCredentialsProvider.create(
                  AwsBasicCredentials.create(props.accessKey(), props.secretKey())));
    }
    this.s3 = builder.build();
  }

  /** Uploads {@code body} as {@code key} in the configured archival bucket. */
  public void putObject(String key, byte[] body) {
    s3.putObject(
        PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromBytes(body));
  }
}
