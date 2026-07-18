package com.nectrix.coreapp.invitations.repository;

import com.nectrix.coreapp.invitations.domain.SymbolMapping;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Plain JDBC, no ORM — matches the convention established across core-app (TICKET-103). */
@Repository
public class SymbolMappingRepository {

  private static final RowMapper<SymbolMapping> ROW_MAPPER =
      (rs, rowNum) -> {
        Timestamp confirmedAt = rs.getTimestamp("confirmed_at");
        String confirmedByUserId = rs.getString("confirmed_by_user_id");
        return new SymbolMapping(
            rs.getLong("id"),
            UUID.fromString(rs.getString("broker_account_id")),
            rs.getString("canonical_symbol"),
            rs.getString("broker_symbol_name"),
            rs.getDouble("contract_size"),
            rs.getDouble("lot_step"),
            rs.getDouble("min_lot"),
            rs.getDouble("max_lot"),
            rs.getDouble("pip_size"),
            rs.getShort("digits"),
            rs.getString("margin_currency"),
            rs.getBoolean("is_confirmed"),
            confirmedAt == null ? null : confirmedAt.toInstant(),
            confirmedByUserId == null ? null : UUID.fromString(confirmedByUserId));
      };

  private final JdbcTemplate jdbcTemplate;

  public SymbolMappingRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public List<SymbolMapping> findByBrokerAccountId(UUID brokerAccountId) {
    return jdbcTemplate.query(
        "SELECT * FROM symbol_mappings WHERE broker_account_id = ? ORDER BY canonical_symbol",
        ROW_MAPPER,
        brokerAccountId);
  }

  public Optional<SymbolMapping> findByBrokerAccountIdAndCanonicalSymbol(
      UUID brokerAccountId, String canonicalSymbol) {
    return jdbcTemplate
        .query(
            "SELECT * FROM symbol_mappings WHERE broker_account_id = ? AND canonical_symbol = ?",
            ROW_MAPPER,
            brokerAccountId,
            canonicalSymbol)
        .stream()
        .findFirst();
  }

  /**
   * Upserts an auto-suggested (unconfirmed) row. The {@code WHERE symbol_mappings.is_confirmed =
   * FALSE} guard on the update clause is the whole point: a re-run of auto-suggestion (e.g. the EA
   * reconnecting) refreshes a still-unconfirmed suggestion's spec numbers, but must NEVER clobber a
   * row a user already confirmed — {@code is_confirmed} itself is never touched by this method,
   * only spec fields and {@code updated_at}.
   */
  public void upsertSuggested(
      UUID brokerAccountId,
      String canonicalSymbol,
      String brokerSymbolName,
      double contractSize,
      double lotStep,
      double minLot,
      double maxLot,
      double pipSize,
      short digits,
      String marginCurrency) {
    jdbcTemplate.update(
        """
        INSERT INTO symbol_mappings
          (broker_account_id, canonical_symbol, broker_symbol_name, contract_size, lot_step,
           min_lot, max_lot, pip_size, digits, margin_currency)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (broker_account_id, canonical_symbol) DO UPDATE SET
          broker_symbol_name = EXCLUDED.broker_symbol_name,
          contract_size = EXCLUDED.contract_size,
          lot_step = EXCLUDED.lot_step,
          min_lot = EXCLUDED.min_lot,
          max_lot = EXCLUDED.max_lot,
          pip_size = EXCLUDED.pip_size,
          digits = EXCLUDED.digits,
          margin_currency = EXCLUDED.margin_currency,
          updated_at = now()
        WHERE symbol_mappings.is_confirmed = FALSE
        """,
        brokerAccountId,
        canonicalSymbol,
        brokerSymbolName,
        contractSize,
        lotStep,
        minLot,
        maxLot,
        pipSize,
        digits,
        marginCurrency);
  }

  /**
   * TICKET-116 — the manual fallback's own write: unlike {@link #upsertSuggested}/{@link #confirm}
   * (which require a pre-existing auto-suggested row), this creates a brand-new, already-confirmed
   * row outright — safe because the caller (see {@code
   * SymbolMappingService#createOrConfirmMapping}) only calls this AFTER a live broker-adapter round
   * trip has verified {@code brokerSymbolName} is real and returned its spec numbers. Also
   * overwrites an existing row (even an already-confirmed one) if present — a user manually
   * re-verifying a symbol should always win over a stale row.
   */
  public void upsertConfirmed(
      UUID brokerAccountId,
      String canonicalSymbol,
      String brokerSymbolName,
      double contractSize,
      double lotStep,
      double minLot,
      double maxLot,
      double pipSize,
      short digits,
      String marginCurrency,
      UUID confirmedByUserId) {
    jdbcTemplate.update(
        """
        INSERT INTO symbol_mappings
          (broker_account_id, canonical_symbol, broker_symbol_name, contract_size, lot_step,
           min_lot, max_lot, pip_size, digits, margin_currency, is_confirmed, confirmed_at,
           confirmed_by_user_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, now(), ?)
        ON CONFLICT (broker_account_id, canonical_symbol) DO UPDATE SET
          broker_symbol_name = EXCLUDED.broker_symbol_name,
          contract_size = EXCLUDED.contract_size,
          lot_step = EXCLUDED.lot_step,
          min_lot = EXCLUDED.min_lot,
          max_lot = EXCLUDED.max_lot,
          pip_size = EXCLUDED.pip_size,
          digits = EXCLUDED.digits,
          margin_currency = EXCLUDED.margin_currency,
          is_confirmed = TRUE,
          confirmed_at = now(),
          confirmed_by_user_id = EXCLUDED.confirmed_by_user_id,
          updated_at = now()
        """,
        brokerAccountId,
        canonicalSymbol,
        brokerSymbolName,
        contractSize,
        lotStep,
        minLot,
        maxLot,
        pipSize,
        digits,
        marginCurrency,
        confirmedByUserId);
  }

  /**
   * Confirms an existing row — {@code brokerSymbolName} is the only field a user can override (the
   * spec numbers came from a real, adapter-verified {@code GetSymbolSpecification} call at
   * suggestion time, never hand-typed). Returns the number of rows updated (0 if no row exists yet
   * for this broker account + canonical symbol — the caller maps that to 404, see
   * SymbolMappingService).
   */
  public int confirm(
      UUID brokerAccountId,
      String canonicalSymbol,
      String brokerSymbolName,
      UUID confirmedByUserId) {
    return jdbcTemplate.update(
        """
        UPDATE symbol_mappings
        SET broker_symbol_name = ?, is_confirmed = TRUE, confirmed_at = now(),
            confirmed_by_user_id = ?, updated_at = now()
        WHERE broker_account_id = ? AND canonical_symbol = ?
        """,
        brokerSymbolName,
        confirmedByUserId,
        brokerAccountId,
        canonicalSymbol);
  }
}
