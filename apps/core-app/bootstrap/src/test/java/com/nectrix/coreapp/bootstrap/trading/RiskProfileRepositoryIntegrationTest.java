package com.nectrix.coreapp.bootstrap.trading;

import static org.assertj.core.api.Assertions.assertThat;

import com.nectrix.coreapp.trading.domain.RiskProfile;
import com.nectrix.coreapp.trading.repository.RiskProfileRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * TICKET-105 — real, hands-on verification of {@link RiskProfileRepository} against live local
 * Postgres (no mocks), mirroring TICKET-104's own {@code
 * MoneyManagementProfileRepositoryIntegrationTest} discipline. No HTTP layer exists yet for {@code
 * RiskProfile} (deferred to whichever ticket builds real {@code CopyRelationship} creation), so
 * this exercises the repository directly rather than through a controller.
 */
@Tag("integration")
@SpringBootTest
class RiskProfileRepositoryIntegrationTest {

  @Autowired private RiskProfileRepository repository;

  private UUID createdId;

  @AfterEach
  void cleanup() {
    if (createdId != null) {
      repository.delete(createdId);
      createdId = null;
    }
  }

  @Test
  void insertThenFindById_allFieldsSet_roundTrips() {
    createdId =
        repository.insert(
            new BigDecimal("5.0000"),
            20,
            new BigDecimal("10.0000"),
            new BigDecimal("20.0000"),
            new BigDecimal("3.00"));

    Optional<RiskProfile> found = repository.findById(createdId);
    assertThat(found).isPresent();
    RiskProfile profile = found.orElseThrow();
    assertThat(profile.maxLotPerTrade()).isEqualByComparingTo("5.0000");
    assertThat(profile.maxOpenPositions()).isEqualTo(20);
    assertThat(profile.maxExposurePerSymbolLots()).isEqualByComparingTo("10.0000");
    assertThat(profile.maxTotalExposureLots()).isEqualByComparingTo("20.0000");
    assertThat(profile.maxSlippagePips()).isEqualByComparingTo("3.00");
    assertThat(profile.drawdownPausePct()).isNull();
    assertThat(profile.drawdownCloseAllPct()).isNull();
    assertThat(profile.createdAt()).isNotNull();
  }

  @Test
  void insertThenFindById_allNullableFieldsNull_roundTrips() {
    createdId = repository.insert(null, null, null, null, null);

    RiskProfile profile = repository.findById(createdId).orElseThrow();
    assertThat(profile.maxLotPerTrade()).isNull();
    assertThat(profile.maxOpenPositions()).isNull();
    assertThat(profile.maxExposurePerSymbolLots()).isNull();
    assertThat(profile.maxTotalExposureLots()).isNull();
  }

  @Test
  void insert_withNullMaxSlippagePips_defaultsToFive() {
    createdId = repository.insert(new BigDecimal("1.0"), 10, null, null, null);

    RiskProfile profile = repository.findById(createdId).orElseThrow();
    assertThat(profile.maxSlippagePips()).isEqualByComparingTo("5");
  }

  @Test
  void update_overwritesExistingRow() {
    createdId = repository.insert(new BigDecimal("1.0"), 5, null, null, new BigDecimal("2.00"));

    int updatedRows =
        repository.update(
            createdId,
            new BigDecimal("3.0"),
            10,
            new BigDecimal("15.0"),
            null,
            new BigDecimal("4.00"));
    assertThat(updatedRows).isEqualTo(1);

    RiskProfile profile = repository.findById(createdId).orElseThrow();
    assertThat(profile.maxLotPerTrade()).isEqualByComparingTo("3.0");
    assertThat(profile.maxOpenPositions()).isEqualTo(10);
    assertThat(profile.maxExposurePerSymbolLots()).isEqualByComparingTo("15.0");
    assertThat(profile.maxSlippagePips()).isEqualByComparingTo("4.00");
  }

  @Test
  void delete_thenFindById_returnsEmpty() {
    UUID id = repository.insert(new BigDecimal("1.0"), 5, null, null, null);

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
