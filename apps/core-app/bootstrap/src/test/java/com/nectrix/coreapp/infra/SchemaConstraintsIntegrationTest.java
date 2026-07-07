package com.nectrix.coreapp.infra;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Proves TICKET-004's AC2 (constraints) and AC3 (audit_log write restriction) against the real
 * migrated schema (see apps/core-app/db) — plain JDBC, connecting as the restricted {@code
 * nectrix_app} role (the same one core-app's own datasource uses), no ORM. Requires {@code make
 * db-migrate} to have already run against the ephemeral Postgres this connects to.
 *
 * <p>Fixture rows use a dedicated UUID prefix ({@code ffffffff-...}) distinct from {@code
 * db/src/main/resources/db/changelog/changes/014-seed-dev-data.sql}'s dev-seed UUIDs, so this test
 * is independent of whether dev seed data happens to be present.
 */
@Tag("integration")
class SchemaConstraintsIntegrationTest {

  private static final String URL =
      "jdbc:postgresql://"
          + envOr("POSTGRES_HOST", "localhost")
          + ":"
          + envOr("POSTGRES_PORT", "5432")
          + "/"
          + envOr("POSTGRES_DB", "nectrix");
  private static final String USER = "nectrix_app";
  private static final String PASSWORD = System.getenv("POSTGRES_APP_PASSWORD");

  private static final String USER_A = "ffffffff-0000-0000-0000-000000000001";
  private static final String USER_B = "ffffffff-0000-0000-0000-000000000002";
  private static final String BROKER_ACCOUNT_A = "ffffffff-0000-0000-0000-000000000010";
  private static final String BROKER_ACCOUNT_B = "ffffffff-0000-0000-0000-000000000011";
  private static final String MASTER_PROFILE = "ffffffff-0000-0000-0000-000000000020";
  private static final String MM_PROFILE = "ffffffff-0000-0000-0000-000000000030";
  private static final String RISK_PROFILE = "ffffffff-0000-0000-0000-000000000031";
  private static final String FOLLOW_REQUEST = "ffffffff-0000-0000-0000-000000000040";

  private static String envOr(String key, String fallback) {
    String value = System.getenv(key);
    return value == null || value.isBlank() ? fallback : value;
  }

  private static Connection connect() throws SQLException {
    return DriverManager.getConnection(URL, USER, PASSWORD);
  }

  @BeforeAll
  static void seedFixtures() throws SQLException {
    try (Connection conn = connect();
        Statement stmt = conn.createStatement()) {
      stmt.execute(
          """
          INSERT INTO users (id, email, display_name, status) VALUES
            ('%s', 'schema-test-a@nectrix.dev', 'Schema Test A', 'ACTIVE'),
            ('%s', 'schema-test-b@nectrix.dev', 'Schema Test B', 'ACTIVE')
          """
              .formatted(USER_A, USER_B));
      stmt.execute(
          """
          INSERT INTO broker_accounts
            (id, user_id, broker_type, broker_account_login, is_demo, currency, connection_role, credentials_ciphertext, credentials_key_version)
          VALUES
            ('%s', '%s', 'CTRADER', 'schema-test-master', TRUE, 'USD', 'MASTER_ONLY', '\\x00', 1),
            ('%s', '%s', 'CTRADER', 'schema-test-follower', TRUE, 'USD', 'FOLLOWER_ONLY', '\\x00', 1)
          """
              .formatted(BROKER_ACCOUNT_A, USER_A, BROKER_ACCOUNT_B, USER_B));
      stmt.execute(
          """
          INSERT INTO master_profiles (id, user_id, primary_broker_account_id, display_name)
          VALUES ('%s', '%s', '%s', 'Schema Test Master')
          """
              .formatted(MASTER_PROFILE, USER_A, BROKER_ACCOUNT_A));
      stmt.execute(
          """
          INSERT INTO money_management_profiles (id, method, multiplier)
          VALUES ('%s', 'MULTIPLIER', 1.0)
          """
              .formatted(MM_PROFILE));
      stmt.execute(
          """
          INSERT INTO risk_profiles (id, max_slippage_pips)
          VALUES ('%s', 5)
          """
              .formatted(RISK_PROFILE));
      stmt.execute(
          """
          INSERT INTO follow_requests
            (id, follower_user_id, master_profile_id, follower_broker_account_id, proposed_money_management_profile_id, proposed_risk_profile_id, status)
          VALUES ('%s', '%s', '%s', '%s', '%s', '%s', 'APPROVED')
          """
              .formatted(
                  FOLLOW_REQUEST,
                  USER_B,
                  MASTER_PROFILE,
                  BROKER_ACCOUNT_B,
                  MM_PROFILE,
                  RISK_PROFILE));
    }
  }

  @AfterAll
  static void cleanupFixtures() throws SQLException {
    try (Connection conn = connect();
        Statement stmt = conn.createStatement()) {
      stmt.execute(
          "DELETE FROM copy_relationships WHERE follower_broker_account_id IN ('%s','%s')"
              .formatted(BROKER_ACCOUNT_A, BROKER_ACCOUNT_B));
      stmt.execute("DELETE FROM follow_requests WHERE id = '%s'".formatted(FOLLOW_REQUEST));
      stmt.execute("DELETE FROM risk_profiles WHERE id = '%s'".formatted(RISK_PROFILE));
      stmt.execute("DELETE FROM money_management_profiles WHERE id = '%s'".formatted(MM_PROFILE));
      stmt.execute("DELETE FROM master_profiles WHERE id = '%s'".formatted(MASTER_PROFILE));
      stmt.execute(
          "DELETE FROM broker_accounts WHERE id IN ('%s','%s')"
              .formatted(BROKER_ACCOUNT_A, BROKER_ACCOUNT_B));
      stmt.execute("DELETE FROM users WHERE id IN ('%s','%s')".formatted(USER_A, USER_B));
    }
  }

  // --- AC2: structural — named constraints from the DDL actually exist ---
  // (the doc names exactly these 2 CHECK + 10 deferred-FK constraints; every
  // other constraint in the schema is Postgres-auto-named and not worth
  // asserting on by generated name)

  @Test
  void namedCheckConstraintsExist() throws SQLException {
    assertConstraintExists("copy_relationships", "chk_no_self_copy");
    assertConstraintExists("copy_relationships", "chk_exactly_one_origin");
  }

  @Test
  void namedDeferredForeignKeyConstraintsExist() throws SQLException {
    assertConstraintExists("invitations", "fk_invitations_master");
    assertConstraintExists("invitations", "fk_invitations_ib_link");
    assertConstraintExists("invitations", "fk_invitations_mm_profile");
    assertConstraintExists("invitations", "fk_invitations_risk_profile");
    assertConstraintExists("users", "fk_users_created_via_invitation");
    assertConstraintExists("follow_requests", "fk_followrequests_master");
    assertConstraintExists("follow_requests", "fk_followrequests_broker_acc");
    assertConstraintExists("follow_requests", "fk_followrequests_mm_profile");
    assertConstraintExists("follow_requests", "fk_followrequests_risk_prof");
    assertConstraintExists("broker_ib_links", "fk_broker_ib_links_master");
  }

  private static void assertConstraintExists(String table, String constraintName)
      throws SQLException {
    try (Connection conn = connect();
        Statement stmt = conn.createStatement()) {
      var rs =
          stmt.executeQuery(
              """
              SELECT 1 FROM pg_constraint c
              JOIN pg_class t ON t.oid = c.conrelid
              WHERE t.relname = '%s' AND c.conname = '%s'
              """
                  .formatted(table, constraintName));
      assertTrue(
          rs.next(), () -> "expected constraint " + constraintName + " on " + table + " to exist");
    }
  }

  // --- AC2: behavioral — the ticket's own example, plus one FK and one unique example ---

  @Test
  void chkNoSelfCopyRejectsSelfCopy() {
    SQLException ex =
        assertThrows(
            SQLException.class,
            () -> insertCopyRelationship(BROKER_ACCOUNT_A, BROKER_ACCOUNT_A, FOLLOW_REQUEST));
    assertTrue(ex.getMessage().contains("chk_no_self_copy"), ex.getMessage());
  }

  @Test
  void chkExactlyOneOriginRejectsNeitherOriginSet() {
    SQLException ex =
        assertThrows(
            SQLException.class,
            () -> insertCopyRelationship(BROKER_ACCOUNT_A, BROKER_ACCOUNT_B, null));
    assertTrue(ex.getMessage().contains("chk_exactly_one_origin"), ex.getMessage());
  }

  @Test
  void foreignKeyRejectsUnknownBrokerAccount() {
    SQLException ex =
        assertThrows(
            SQLException.class,
            () ->
                insertCopyRelationship(
                    "ffffffff-dead-dead-dead-000000000000", BROKER_ACCOUNT_B, FOLLOW_REQUEST));
    assertTrue(ex.getMessage().toLowerCase().contains("foreign key"), ex.getMessage());
  }

  @Test
  void uniqueConstraintRejectsDuplicateEmail() throws SQLException {
    try (Connection conn = connect();
        Statement stmt = conn.createStatement()) {
      SQLException ex =
          assertThrows(
              SQLException.class,
              () ->
                  stmt.execute(
                      "INSERT INTO users (email, display_name, status) VALUES ('schema-test-a@nectrix.dev', 'Duplicate', 'ACTIVE')"));
      assertTrue(ex.getMessage().toLowerCase().contains("duplicate key"), ex.getMessage());
    }
  }

  private static void insertCopyRelationship(
      String masterBrokerAccountId,
      String followerBrokerAccountId,
      String originatingFollowRequestId)
      throws SQLException {
    try (Connection conn = connect();
        Statement stmt = conn.createStatement()) {
      String originValue =
          originatingFollowRequestId == null ? "NULL" : "'" + originatingFollowRequestId + "'";
      stmt.execute(
          """
          INSERT INTO copy_relationships
            (master_profile_id, master_broker_account_id, follower_user_id, follower_broker_account_id,
             money_management_profile_id, risk_profile_id, performance_fee_percent, fee_collection_method,
             originating_follow_request_id)
          VALUES ('%s', '%s', '%s', '%s', '%s', '%s', 20.00, 'BROKER_PARTNERSHIP', %s)
          """
              .formatted(
                  MASTER_PROFILE,
                  masterBrokerAccountId,
                  USER_B,
                  followerBrokerAccountId,
                  MM_PROFILE,
                  RISK_PROFILE,
                  originValue));
    }
  }

  // --- AC3: audit_log write restriction (docs/17-security-architecture.md §17.6) ---

  @Test
  void nectrixAppCanInsertAndSelectAuditLog() {
    assertDoesNotThrow(
        () -> {
          try (Connection conn = connect();
              Statement stmt = conn.createStatement()) {
            stmt.execute(
                "INSERT INTO audit_log (actor_type, action) VALUES ('SYSTEM', 'SCHEMA_CONSTRAINTS_TEST')");
            var rs =
                stmt.executeQuery(
                    "SELECT count(*) FROM audit_log WHERE action = 'SCHEMA_CONSTRAINTS_TEST'");
            rs.next();
            assertTrue(rs.getInt(1) >= 1);
          }
        });
  }

  @Test
  void nectrixAppCannotUpdateAuditLog() {
    SQLException ex =
        assertThrows(
            SQLException.class,
            () -> {
              try (Connection conn = connect();
                  Statement stmt = conn.createStatement()) {
                stmt.execute(
                    "UPDATE audit_log SET action = 'TAMPERED' WHERE action = 'SCHEMA_CONSTRAINTS_TEST'");
              }
            });
    assertTrue(ex.getMessage().toLowerCase().contains("permission denied"), ex.getMessage());
  }

  @Test
  void nectrixAppCannotDeleteAuditLog() {
    SQLException ex =
        assertThrows(
            SQLException.class,
            () -> {
              try (Connection conn = connect();
                  Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM audit_log WHERE action = 'SCHEMA_CONSTRAINTS_TEST'");
              }
            });
    assertTrue(ex.getMessage().toLowerCase().contains("permission denied"), ex.getMessage());
  }
}
