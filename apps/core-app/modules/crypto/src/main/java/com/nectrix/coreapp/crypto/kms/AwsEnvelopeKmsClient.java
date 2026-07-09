package com.nectrix.coreapp.crypto.kms;

import com.nectrix.coreapp.crypto.config.EnvelopeEncryptionProperties;
import java.net.URI;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.KmsClientBuilder;
import software.amazon.awssdk.services.kms.model.DataKeySpec;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.DecryptResponse;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse;

/**
 * Real AWS KMS SDK v2 client — the exact same code path talks to LocalStack locally/in CI ({@code
 * endpointOverride} set, docker-compose.yml) and real AWS KMS in production ({@code
 * endpointOverride} unset, credentials resolve via the SDK's normal provider chain).
 */
@Component
public class AwsEnvelopeKmsClient implements EnvelopeKmsClient {

  private final KmsClient kms;

  public AwsEnvelopeKmsClient(EnvelopeEncryptionProperties props) {
    KmsClientBuilder builder = KmsClient.builder().region(Region.of(props.region()));
    if (props.endpointOverride() != null && !props.endpointOverride().isBlank()) {
      builder
          .endpointOverride(URI.create(props.endpointOverride()))
          // LocalStack ignores credential values entirely but the SDK still
          // requires *something* resolvable — real AWS KMS never takes this
          // branch, its credentials come from the normal provider chain
          // (IRSA in-cluster, ~/.aws/credentials locally, etc).
          .credentialsProvider(
              StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")));
    }
    this.kms = builder.build();
  }

  @Override
  public DataKey generateDataKey(String kmsKeyId) {
    GenerateDataKeyResponse response =
        kms.generateDataKey(
            GenerateDataKeyRequest.builder().keyId(kmsKeyId).keySpec(DataKeySpec.AES_256).build());
    return new DataKey(response.plaintext().asByteArray(), response.ciphertextBlob().asByteArray());
  }

  @Override
  public byte[] decryptDataKey(String kmsKeyId, byte[] encryptedDek) {
    DecryptResponse response =
        kms.decrypt(
            DecryptRequest.builder()
                .keyId(kmsKeyId)
                .ciphertextBlob(SdkBytes.fromByteArray(encryptedDek))
                .build());
    return response.plaintext().asByteArray();
  }
}
