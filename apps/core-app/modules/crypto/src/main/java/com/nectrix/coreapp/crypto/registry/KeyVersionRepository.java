package com.nectrix.coreapp.crypto.registry;

import java.util.NoSuchElementException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The application-level key-version registry (docs/17-security-architecture.md §17.2 — "{@code
 * credentials_key_version} supports KEK rotation without a synchronous full-table re-encryption").
 * This is deliberately explicit application state, not just AWS KMS's own opaque internal key
 * rotation: {@link #rotate} is what makes "encrypt under version 1, rotate to version 2, confirm
 * version-1 ciphertext still decrypts" (AC2) a real, testable operation. Plain JDBC, no ORM —
 * matches the convention established across the rest of core-app (see auth's UserRepository).
 */
@Repository
public class KeyVersionRepository {

  private final JdbcTemplate jdbcTemplate;

  public KeyVersionRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** The KMS key new {@code encryptField} calls should use, plus its version tag. */
  public record CurrentKey(short version, String kmsKeyId) {}

  public CurrentKey current() {
    return jdbcTemplate.queryForObject(
        "SELECT version, kms_key_id FROM kms_key_versions WHERE is_current = true",
        (rs, rowNum) -> new CurrentKey(rs.getShort("version"), rs.getString("kms_key_id")));
  }

  public String kmsKeyIdForVersion(short version) {
    String kmsKeyId =
        jdbcTemplate
            .query(
                "SELECT kms_key_id FROM kms_key_versions WHERE version = ?",
                (rs, rowNum) -> rs.getString("kms_key_id"),
                version)
            .stream()
            .findFirst()
            .orElse(null);
    if (kmsKeyId == null) {
      throw new NoSuchElementException("No kms_key_versions row for version " + version);
    }
    return kmsKeyId;
  }

  /**
   * Rotation: demotes whichever row is currently marked current, then inserts {@code newVersion} as
   * the new current row — both in one transaction, so a concurrent {@link #current()} read never
   * observes zero or two current rows.
   */
  @Transactional
  public void rotate(short newVersion, String newKmsKeyId) {
    jdbcTemplate.update("UPDATE kms_key_versions SET is_current = false WHERE is_current = true");
    jdbcTemplate.update(
        "INSERT INTO kms_key_versions (version, kms_key_id, is_current) VALUES (?, ?, true)",
        newVersion,
        newKmsKeyId);
  }
}
