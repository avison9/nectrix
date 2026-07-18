package com.nectrix.coreapp.bootstrap.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nectrix.coreapp.auth.repository.UserRepository;
import com.nectrix.coreapp.auth.service.EmailAlreadyRegisteredException;
import com.nectrix.coreapp.auth.service.RegistrationService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * TICKET-114 — self-serve "Individual" registration grants the base {@code USER} role only, never
 * {@code MASTER}/{@code FOLLOWER} (that reversal from the mock's own literal copy is this ticket's
 * second corrected requirement — see {@link RegistrationService}'s own Javadoc), and never
 * auto-creates a {@code master_profiles} row (still the existing, separate TICKET-111 flow).
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RegistrationIntegrationTest {

  @Autowired private RegistrationService registrationService;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void register_grantsUserRoleOnly_andCreatesNoMasterProfile() {
    String email = "individual-" + UUID.randomUUID() + "@example.com";

    UUID userId =
        registrationService.register(email, "correct horse battery staple", "Alex Morgan");

    assertThat(userRepository.findByEmail(email)).isPresent();
    List<String> roles = userRepository.findRoleNames(userId);
    assertThat(roles).containsExactly("USER");

    Long profileCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM master_profiles WHERE user_id = ?", Long.class, userId);
    assertThat(profileCount).isZero();
  }

  @Test
  void register_duplicateEmail_throwsCleanConflictNotARawConstraintViolation() {
    String email = "dup-" + UUID.randomUUID() + "@example.com";
    registrationService.register(email, "correct horse battery staple", "First");

    assertThatThrownBy(() -> registrationService.register(email, "another password", "Second"))
        .isInstanceOf(EmailAlreadyRegisteredException.class);
  }
}
