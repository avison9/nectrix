package com.nectrix.coreapp.billing.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * TICKET-101 follow-up — {@code management_agreements} archival export, reading it the same way
 * this module already reads {@code copy_relationships}/{@code copied_trades} directly via
 * JdbcTemplate (see this module's own build.gradle.kts). Read-only: the row's own {@code ON DELETE
 * CASCADE} from {@code copy_relationships} means archival never needs to delete it explicitly —
 * only export it before that cascade fires.
 *
 * <p>Named {@code *ArchivalRepository}, not the bare {@code ManagementAgreementRepository} name —
 * {@code trading.repository.ManagementAgreementRepository} is the table's real read/write owner
 * (see that class's own Javadoc for why signing lives in {@code modules:trading}, not here); a
 * bare-name collision between the two is a real Spring bean-name conflict (both default to {@code
 * managementAgreementRepository}), not just a naming preference — confirmed by {@code
 * ConflictingBeanDefinitionException} the one time this wasn't yet renamed.
 */
@Repository
public class ManagementAgreementArchivalRepository {

  private final JdbcTemplate jdbcTemplate;

  public ManagementAgreementArchivalRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public record ManagementAgreementExportRow(
      UUID id,
      UUID copyRelationshipId,
      String agreementVersion,
      String status,
      String documentObjectKey,
      String signatureReference,
      Instant signedAt,
      Instant createdAt) {}

  public List<ManagementAgreementExportRow> findAllForRelationshipIds(
      List<UUID> copyRelationshipIds) {
    if (copyRelationshipIds.isEmpty()) {
      return List.of();
    }
    String placeholders = String.join(",", copyRelationshipIds.stream().map(id -> "?").toList());
    return jdbcTemplate.query(
        "SELECT id, copy_relationship_id, agreement_version, status, document_object_key, "
            + "signature_reference, signed_at, created_at "
            + "FROM management_agreements WHERE copy_relationship_id IN ("
            + placeholders
            + ")",
        (rs, rowNum) ->
            new ManagementAgreementExportRow(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("copy_relationship_id")),
                rs.getString("agreement_version"),
                rs.getString("status"),
                rs.getString("document_object_key"),
                rs.getString("signature_reference"),
                rs.getTimestamp("signed_at") == null
                    ? null
                    : rs.getTimestamp("signed_at").toInstant(),
                rs.getTimestamp("created_at").toInstant()),
        copyRelationshipIds.toArray());
  }
}
