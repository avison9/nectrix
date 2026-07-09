package com.nectrix.coreapp.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import com.nectrix.coreapp.crypto.api.EncryptedField;
import com.nectrix.coreapp.crypto.config.EnvelopeEncryptionProperties;
import com.nectrix.coreapp.crypto.kms.AwsEnvelopeKmsClient;
import com.nectrix.coreapp.crypto.registry.KeyVersionRepository;
import java.net.URI;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.CreateKeyRequest;

/**
 * TICKET-011's real, hands-on verification of AC1/AC2 against real LocalStack KMS + Postgres
 * (docker-compose.yml, infra/localstack/init-kms.sh). Hand-constructs its collaborators directly
 * (no Spring context) since modules:crypto isn't itself a Spring Boot application module.
 */
@Tag("integration")
class EnvelopeEncryptionServiceIntegrationTest {

  private JdbcTemplate jdbcTemplate;
  private KeyVersionRepository keyVersionRepository;
  private EnvelopeEncryptionServiceImpl service;
  private KmsClient rawKmsClientForTestSetup;
  private Short rotatedVersion;

  @BeforeEach
  void setUp() {
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setServerNames(new String[] {envOr("POSTGRES_HOST", "localhost")});
    dataSource.setPortNumbers(new int[] {Integer.parseInt(envOr("POSTGRES_PORT", "5432"))});
    dataSource.setDatabaseName(envOr("POSTGRES_DB", "nectrix"));
    dataSource.setUser("nectrix_app");
    dataSource.setPassword(System.getenv("POSTGRES_APP_PASSWORD"));
    jdbcTemplate = new JdbcTemplate(dataSource);
    keyVersionRepository = new KeyVersionRepository(jdbcTemplate);

    EnvelopeEncryptionProperties props =
        new EnvelopeEncryptionProperties(
            envOr("AWS_REGION", "us-east-1"),
            envOr("KMS_ENDPOINT_OVERRIDE", "http://localhost:4566"));
    service =
        new EnvelopeEncryptionServiceImpl(new AwsEnvelopeKmsClient(props), keyVersionRepository);

    // Raw AWS SDK client, test-setup only — creates a genuinely separate KMS
    // key for AC2's rotation (not just a new version number pointing at the
    // same underlying key), matching what a real KEK rotation actually is.
    rawKmsClientForTestSetup =
        KmsClient.builder()
            .region(Region.of(props.region()))
            .endpointOverride(URI.create(props.endpointOverride()))
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
            .build();
  }

  @AfterEach
  void tearDown() {
    // Restore version 1 as current and remove the rotated-to row entirely,
    // so a repeat run of this test against the same persistent dev Postgres
    // never collides on the version PRIMARY KEY and always starts from the
    // same baseline (version 1 current) infra/localstack/init-kms.sh seeds.
    if (rotatedVersion != null) {
      // Delete the rotated-to row *before* re-marking version 1 current —
      // both can't be is_current=true at once (idx_kms_key_versions_current),
      // and the rotated row is still marked true at this point.
      jdbcTemplate.update("DELETE FROM kms_key_versions WHERE version = ?", rotatedVersion);
      jdbcTemplate.update("UPDATE kms_key_versions SET is_current = true WHERE version = 1");
    }
  }

  // AC1: "A round-trip encrypt→decrypt test passes for a sample field."
  @Test
  void ac1_encryptThenDecrypt_roundTripsToTheOriginalPlaintext() {
    String plaintext = "sample-secret-" + UUID.randomUUID();

    EncryptedField encrypted = service.encryptField(plaintext);
    assertThat(encrypted.ciphertext()).isNotEqualTo(plaintext);
    assertThat(encrypted.ciphertext()).isNotBlank();

    String decrypted = service.decryptField(encrypted.ciphertext(), encrypted.keyVersion());
    assertThat(decrypted).isEqualTo(plaintext);
  }

  // AC2: "Simulated KEK rotation: encrypt a value under key version 1,
  // rotate to version 2, confirm the version-1-encrypted value still
  // decrypts correctly, and new encryptions use version 2."
  @Test
  void
      ac2_rotatingTheCurrentKeyVersion_keepsOldCiphertextDecryptableAndTagsNewOnesWithTheNewVersion() {
    KeyVersionRepository.CurrentKey before = keyVersionRepository.current();
    String plaintext = "pre-rotation-secret-" + UUID.randomUUID();
    EncryptedField encryptedBeforeRotation = service.encryptField(plaintext);
    assertThat(encryptedBeforeRotation.keyVersion()).isEqualTo(before.version());

    String newKmsKeyId =
        rawKmsClientForTestSetup
            .createKey(
                CreateKeyRequest.builder()
                    .description("nectrix test — AC2 rotation target")
                    .build())
            .keyMetadata()
            .keyId();
    rotatedVersion = (short) (1000 + new Random().nextInt(30000));
    keyVersionRepository.rotate(rotatedVersion, newKmsKeyId);

    // Old ciphertext (tagged with the pre-rotation version) must still decrypt.
    String decrypted =
        service.decryptField(
            encryptedBeforeRotation.ciphertext(), encryptedBeforeRotation.keyVersion());
    assertThat(decrypted).isEqualTo(plaintext);

    // A fresh encryption must now be tagged with the new current version.
    EncryptedField encryptedAfterRotation = service.encryptField("post-rotation-secret");
    assertThat(encryptedAfterRotation.keyVersion()).isEqualTo(rotatedVersion);
  }

  private static String envOr(String key, String fallback) {
    String value = System.getenv(key);
    return value == null || value.isBlank() ? fallback : value;
  }
}
