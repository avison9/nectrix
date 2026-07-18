package com.nectrix.coreapp.billing.repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Plain JDBC, no ORM — the {@code invoices} table (007-billing.sql), Stripe (Option A) path only.
 */
@Repository
public class InvoiceRepository {

  private final JdbcTemplate jdbcTemplate;

  public InvoiceRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public UUID insert(
      UUID userId,
      UUID performanceFeeLedgerId,
      BigDecimal amount,
      String status,
      String paymentProviderRef) {
    return jdbcTemplate.execute(
        """
        INSERT INTO invoices (user_id, source_type, source_id, amount, currency, status, payment_provider_ref)
        VALUES (?, 'PERFORMANCE_FEE', ?, ?, 'USD', ?, ?)
        RETURNING id
        """,
        (PreparedStatement ps) -> {
          int i = 1;
          ps.setObject(i++, userId);
          ps.setObject(i++, performanceFeeLedgerId);
          ps.setBigDecimal(i++, amount);
          ps.setString(i++, status);
          ps.setString(i, paymentProviderRef);
          try (ResultSet rs = ps.executeQuery()) {
            rs.next();
            return UUID.fromString(rs.getString(1));
          }
        });
  }

  public void markPaid(String paymentProviderRef) {
    jdbcTemplate.update(
        "UPDATE invoices SET status = 'PAID', paid_at = now() WHERE payment_provider_ref = ?",
        paymentProviderRef);
  }

  public void markFailed(String paymentProviderRef) {
    jdbcTemplate.update(
        "UPDATE invoices SET status = 'FAILED' WHERE payment_provider_ref = ?", paymentProviderRef);
  }

  public record InvoiceRow(UUID id, UUID userId, BigDecimal amount, String status) {}

  public Optional<InvoiceRow> findByPaymentProviderRef(String paymentProviderRef) {
    return jdbcTemplate
        .query(
            "SELECT id, user_id, amount, status FROM invoices WHERE payment_provider_ref = ?",
            (rs, rowNum) ->
                new InvoiceRow(
                    UUID.fromString(rs.getString("id")),
                    UUID.fromString(rs.getString("user_id")),
                    rs.getBigDecimal("amount"),
                    rs.getString("status")),
            paymentProviderRef)
        .stream()
        .findFirst();
  }
}
