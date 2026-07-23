package com.nectrix.coreapp.social.repository;

import com.nectrix.coreapp.social.domain.MasterProfile;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Plain JDBC, no ORM — matches the convention established across core-app. {@code strategy_tags} is
 * a real Postgres {@code TEXT[]} column — no prior precedent for this in the codebase (every other
 * array-shaped need so far has been an {@code IN (?, ?, ...)} query filter, not a stored array
 * column), so read/write goes through {@code java.sql.Array} directly rather than the
 * placeholder-list trick {@code BrokerAccountRepository#findByStatusAndBrokerType} uses for that
 * different problem.
 */
@Repository
public class MasterProfileRepository {

  private static final RowMapper<MasterProfile> ROW_MAPPER = MasterProfileRepository::mapRow;

  private final JdbcTemplate jdbcTemplate;

  public MasterProfileRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  private static MasterProfile mapRow(ResultSet rs, int rowNum) throws java.sql.SQLException {
    Array tagsArray = rs.getArray("strategy_tags");
    List<String> tags = tagsArray != null ? List.of((String[]) tagsArray.getArray()) : List.of();
    Timestamp verifiedAt = rs.getTimestamp("verified_at");
    return new MasterProfile(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("user_id")),
        UUID.fromString(rs.getString("primary_broker_account_id")),
        rs.getString("display_name"),
        rs.getString("bio"),
        tags,
        rs.getBigDecimal("performance_fee_percent"),
        rs.getString("fee_collection_method"),
        rs.getBoolean("is_public"),
        verifiedAt != null ? verifiedAt.toInstant() : null,
        rs.getTimestamp("created_at").toInstant());
  }

  public Optional<MasterProfile> findById(UUID id) {
    return jdbcTemplate.query("SELECT * FROM master_profiles WHERE id = ?", ROW_MAPPER, id).stream()
        .findFirst();
  }

  public Optional<MasterProfile> findByUserId(UUID userId) {
    return jdbcTemplate
        .query("SELECT * FROM master_profiles WHERE user_id = ?", ROW_MAPPER, userId)
        .stream()
        .findFirst();
  }

  /**
   * Bugfix — lets a caller ask "is this broker account currently anyone's primary?" without knowing
   * the master_profile id up front. {@code primary_broker_account_id} isn't unique at the schema
   * level, but is in practice (a broker account belongs to exactly one user, and {@link
   * #findByUserId} is itself a one-row-per-user lookup) — {@code findFirst} is a defensive
   * safeguard, not a real multi-row case.
   */
  public Optional<MasterProfile> findByPrimaryBrokerAccountId(UUID brokerAccountId) {
    return jdbcTemplate
        .query(
            "SELECT * FROM master_profiles WHERE primary_broker_account_id = ?",
            ROW_MAPPER,
            brokerAccountId)
        .stream()
        .findFirst();
  }

  /**
   * {@code feeCollectionMethod}/{@code performanceFeePercent} may be null — column defaults apply.
   * Defaults {@code isPublic} to {@code true} (the column's own DB default) — the real Master
   * self-service creation path.
   */
  public UUID insert(
      UUID userId,
      UUID primaryBrokerAccountId,
      String displayName,
      String bio,
      List<String> strategyTags,
      BigDecimal performanceFeePercent,
      String feeCollectionMethod) {
    return insert(
        userId,
        primaryBrokerAccountId,
        displayName,
        bio,
        strategyTags,
        performanceFeePercent,
        feeCollectionMethod,
        true);
  }

  /**
   * TICKET-114 — {@code isPublic=false} for an Individual-mode user's system-created private
   * profile (never appears in {@code DiscoveryRepository}'s {@code WHERE is_public = true}
   * queries).
   */
  public UUID insert(
      UUID userId,
      UUID primaryBrokerAccountId,
      String displayName,
      String bio,
      List<String> strategyTags,
      BigDecimal performanceFeePercent,
      String feeCollectionMethod,
      boolean isPublic) {
    return jdbcTemplate.execute(
        """
        INSERT INTO master_profiles
          (user_id, primary_broker_account_id, display_name, bio, strategy_tags,
           performance_fee_percent, fee_collection_method, is_public)
        VALUES (?, ?, ?, ?, ?, COALESCE(?, 20.00), COALESCE(?, 'BROKER_PARTNERSHIP'), ?)
        RETURNING id
        """,
        (PreparedStatement ps) -> {
          int i = 1;
          ps.setObject(i++, userId);
          ps.setObject(i++, primaryBrokerAccountId);
          ps.setString(i++, displayName);
          ps.setString(i++, bio);
          ps.setArray(i++, toTextArray(ps, strategyTags));
          ps.setBigDecimal(i++, performanceFeePercent);
          ps.setString(i++, feeCollectionMethod);
          ps.setBoolean(i, isPublic);
          try (ResultSet rs = ps.executeQuery()) {
            rs.next();
            return UUID.fromString(rs.getString(1));
          }
        });
  }

  /** Full-row settings update — self-service profile editing (PATCH /master-profiles/{id}). */
  public int update(
      UUID id,
      String displayName,
      String bio,
      List<String> strategyTags,
      BigDecimal performanceFeePercent,
      boolean isPublic) {
    return jdbcTemplate.execute(
        """
        UPDATE master_profiles
        SET display_name = ?, bio = ?, strategy_tags = ?, performance_fee_percent = ?, is_public = ?
        WHERE id = ?
        """,
        (PreparedStatement ps) -> {
          int i = 1;
          ps.setString(i++, displayName);
          ps.setString(i++, bio);
          ps.setArray(i++, toTextArray(ps, strategyTags));
          ps.setBigDecimal(i++, performanceFeePercent);
          ps.setBoolean(i++, isPublic);
          ps.setObject(i, id);
          return ps.executeUpdate();
        });
  }

  /**
   * Bugfix — lets a Master change which of their own broker accounts is their primary one after
   * profile creation (previously set exactly once, at {@link #insert} time, with no update path at
   * all). Caller is responsible for the ownership check (see {@code
   * MasterProfileService#changePrimaryBrokerAccount}) — this method just writes the column.
   */
  public void updatePrimaryBrokerAccount(UUID id, UUID brokerAccountId) {
    jdbcTemplate.update(
        "UPDATE master_profiles SET primary_broker_account_id = ? WHERE id = ?",
        brokerAccountId,
        id);
  }

  private static Array toTextArray(PreparedStatement ps, List<String> tags)
      throws java.sql.SQLException {
    return ps.getConnection().createArrayOf("text", tags != null ? tags.toArray() : new Object[0]);
  }
}
