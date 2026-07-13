package com.nectrix.coreapp.bootstrap.trading;

import static org.assertj.core.api.Assertions.assertThat;

import com.nectrix.coreapp.trading.domain.MoneyManagementProfile;
import com.nectrix.coreapp.trading.repository.MoneyManagementProfileRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * TICKET-104 — real, hands-on verification of {@link MoneyManagementProfileRepository} against live
 * local Postgres (no mocks), mirroring TICKET-103's own DB-integration-test discipline. No HTTP
 * layer exists yet for {@code MoneyManagementProfile} (see this ticket's plan — deferred to
 * whichever ticket builds real {@code CopyRelationship} creation), so this exercises the repository
 * directly rather than through a controller.
 */
@Tag("integration")
@SpringBootTest
class MoneyManagementProfileRepositoryIntegrationTest {

  @Autowired private MoneyManagementProfileRepository repository;

  private UUID createdId;

  @AfterEach
  void cleanup() {
    if (createdId != null) {
      repository.delete(createdId);
      createdId = null;
    }
  }

  @Test
  void insertThenFindById_fixedLot_roundTrips() {
    createdId = repository.insert("FIXED_LOT", new BigDecimal("0.5000"), null, null, null, "DOWN");

    Optional<MoneyManagementProfile> found = repository.findById(createdId);
    assertThat(found).isPresent();
    MoneyManagementProfile profile = found.orElseThrow();
    assertThat(profile.method()).isEqualTo("FIXED_LOT");
    assertThat(profile.fixedLotSize()).isEqualByComparingTo("0.5000");
    assertThat(profile.multiplier()).isNull();
    assertThat(profile.riskPercent()).isNull();
    assertThat(profile.customFormulaExpr()).isNull();
    assertThat(profile.roundingMode()).isEqualTo("DOWN");
    assertThat(profile.createdAt()).isNotNull();
  }

  @Test
  void insertThenFindById_riskPercent_roundTrips() {
    createdId =
        repository.insert("RISK_PERCENT", null, null, new BigDecimal("2.500"), null, "NEAREST");

    MoneyManagementProfile profile = repository.findById(createdId).orElseThrow();
    assertThat(profile.method()).isEqualTo("RISK_PERCENT");
    assertThat(profile.riskPercent()).isEqualByComparingTo("2.500");
    assertThat(profile.roundingMode()).isEqualTo("NEAREST");
  }

  @Test
  void insertThenFindById_customFormula_roundTrips() {
    String expr =
        "min(master_open_volume_lots * 2, follower_account_equity / master_account_equity)";
    createdId = repository.insert("CUSTOM_FORMULA", null, null, null, expr, "UP");

    MoneyManagementProfile profile = repository.findById(createdId).orElseThrow();
    assertThat(profile.customFormulaExpr()).isEqualTo(expr);
    assertThat(profile.roundingMode()).isEqualTo("UP");
  }

  @Test
  void insert_withNullRoundingMode_defaultsToDown() {
    createdId = repository.insert("MULTIPLIER", null, new BigDecimal("1.5"), null, null, null);

    MoneyManagementProfile profile = repository.findById(createdId).orElseThrow();
    assertThat(profile.roundingMode()).isEqualTo("DOWN");
  }

  @Test
  void update_overwritesExistingRow() {
    createdId = repository.insert("MULTIPLIER", null, new BigDecimal("1.0"), null, null, "DOWN");

    int updatedRows =
        repository.update(createdId, "MULTIPLIER", null, new BigDecimal("3.0"), null, null, "UP");
    assertThat(updatedRows).isEqualTo(1);

    MoneyManagementProfile profile = repository.findById(createdId).orElseThrow();
    assertThat(profile.multiplier()).isEqualByComparingTo("3.0");
    assertThat(profile.roundingMode()).isEqualTo("UP");
  }

  @Test
  void delete_thenFindById_returnsEmpty() {
    UUID id = repository.insert("FIXED_LOT", new BigDecimal("1.0"), null, null, null, "DOWN");

    int deletedRows = repository.delete(id);
    assertThat(deletedRows).isEqualTo(1);
    assertThat(repository.findById(id)).isEmpty();
    // Already deleted -- do not let @AfterEach attempt a second delete.
  }

  @Test
  void findById_unknownId_returnsEmpty() {
    assertThat(repository.findById(UUID.randomUUID())).isEmpty();
  }
}
