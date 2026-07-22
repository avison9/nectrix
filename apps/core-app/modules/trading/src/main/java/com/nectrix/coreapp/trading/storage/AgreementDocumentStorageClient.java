package com.nectrix.coreapp.trading.storage;

import java.net.URI;
import java.net.URL;
import java.time.Duration;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

/**
 * Real AWS S3 SDK v2 client — same "one code path, real infra locally/in CI via {@code
 * endpointOverride}, real AWS in production" pattern {@code bootstrap.archival.
 * ArchivalBlobStorageClient} already established, plus real presigned-URL generation (AC2 — "never
 * a public URL"): a signed agreement is only ever handed to a caller as a short-lived {@code
 * S3Presigner} URL, the raw object itself stays private.
 */
@Component
public class AgreementDocumentStorageClient {

  private static final Duration PRESIGN_TTL = Duration.ofMinutes(15);

  private final S3Client s3;
  private final S3Presigner presigner;
  private final String bucket;

  public AgreementDocumentStorageClient(AgreementDocumentStorageProperties props) {
    this.bucket = props.bucket();
    Region region = Region.of(props.region());
    S3ClientBuilder builder = S3Client.builder().region(region);
    S3Presigner.Builder presignerBuilder = S3Presigner.builder().region(region);
    if (props.endpointOverride() != null && !props.endpointOverride().isBlank()) {
      var credentials =
          StaticCredentialsProvider.create(
              AwsBasicCredentials.create(props.accessKey(), props.secretKey()));
      builder
          .endpointOverride(URI.create(props.endpointOverride()))
          .forcePathStyle(true)
          .credentialsProvider(credentials);
      // See AgreementDocumentStorageProperties' own Javadoc for why the presigner needs a
      // DIFFERENT,
      // browser-reachable endpoint than the one core-app itself uses server-to-server.
      String publicEndpoint =
          props.publicEndpointOverride() != null && !props.publicEndpointOverride().isBlank()
              ? props.publicEndpointOverride()
              : props.endpointOverride();
      presignerBuilder
          .endpointOverride(URI.create(publicEndpoint))
          .serviceConfiguration(
              software.amazon.awssdk.services.s3.S3Configuration.builder()
                  .pathStyleAccessEnabled(true)
                  .build())
          .credentialsProvider(credentials);
    }
    this.s3 = builder.build();
    this.presigner = presignerBuilder.build();
  }

  public void putObject(String key, byte[] body) {
    s3.putObject(
        PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromBytes(body));
  }

  /** A {@value #PRESIGN_TTL}-minute signed URL — never the raw object, never a public one (AC2). */
  public URL presignedGetUrl(String key) {
    GetObjectRequest getRequest = GetObjectRequest.builder().bucket(bucket).key(key).build();
    GetObjectPresignRequest presignRequest =
        GetObjectPresignRequest.builder()
            .signatureDuration(PRESIGN_TTL)
            .getObjectRequest(getRequest)
            .build();
    PresignedGetObjectRequest presigned = presigner.presignGetObject(presignRequest);
    return presigned.url();
  }
}
