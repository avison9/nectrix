package com.nectrix.coreapp.trading.repository;

import com.nectrix.coreapp.trading.domain.ManagementAgreement;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Plain JDBC, no ORM — matches the convention established across core-app. TICKET-120 — see {@code
 * ManagementAgreement}'s own Javadoc for why this table's real write path lives here rather than
 * {@code modules:billing}.
 */
@Repository
public class ManagementAgreementRepository {

  private static final String AGREEMENT_VERSION = "v1";

  private static final RowMapper<ManagementAgreement> ROW_MAPPER =
      (rs, rowNum) -> {
        Timestamp signedAt = rs.getTimestamp("signed_at");
        return new ManagementAgreement(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("copy_relationship_id")),
            rs.getString("agreement_version"),
            rs.getString("status"),
            rs.getString("document_object_key"),
            rs.getString("signature_reference"),
            signedAt != null ? signedAt.toInstant() : null,
            rs.getTimestamp("created_at").toInstant());
      };

  private final JdbcTemplate jdbcTemplate;

  public ManagementAgreementRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Optional<ManagementAgreement> findByCopyRelationshipId(UUID copyRelationshipId) {
    return jdbcTemplate
        .query(
            "SELECT * FROM management_agreements WHERE copy_relationship_id = ?",
            ROW_MAPPER,
            copyRelationshipId)
        .stream()
        .findFirst();
  }

  /**
   * TICKET-120 — the whole row is created SIGNED, in one step: "a simple in-app 'I have read and
   * agree' affirmative action" (this ticket's own scope note) IS the signature, there's no separate
   * PENDING_SIGNATURE draft stage a user fills in over time the way an e-signature provider flow
   * would have. {@code signatureReference} records what stood in for a real signature — the calling
   * user's id and the moment of the affirmative click, not a third-party provider's reference id
   * (see this ticket's own "no e-signature provider required" out-of-scope note).
   */
  public UUID insertSigned(
      UUID copyRelationshipId, String documentObjectKey, String signatureReference) {
    return jdbcTemplate.queryForObject(
        """
        INSERT INTO management_agreements
          (copy_relationship_id, agreement_version, status, document_object_key, signature_reference, signed_at)
        VALUES (?, ?, 'SIGNED', ?, ?, now())
        RETURNING id
        """,
        UUID.class,
        copyRelationshipId,
        AGREEMENT_VERSION,
        documentObjectKey,
        signatureReference);
  }
}
