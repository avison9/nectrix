package com.nectrix.coreapp.auth.service;

import com.nectrix.coreapp.auth.config.AuthProperties;
import com.nectrix.coreapp.auth.domain.TwoFactorEnrollment;
import com.nectrix.coreapp.auth.domain.User;
import com.nectrix.coreapp.auth.repository.UserRepository;
import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.qr.QrGenerator;
import dev.samstevens.totp.qr.ZxingPngQrGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;
import dev.samstevens.totp.util.Utils;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * TOTP enrollment/verification — docs/14-api-specification.md §14.2's {@code /2fa/enable}
 * (secret+QR) and {@code /2fa/verify} (confirms enrollment, flips {@code
 * users.two_factor_enabled}).
 *
 * <p>Since {@code /2fa/verify}'s request body is just {@code {totp_code}} (no secret — the API spec
 * gives it no field for one), the server has to remember the pending secret between the two calls
 * itself. Simplest option without adding session/Redis state for this one flow: {@link
 * #beginEnrollment} persists the (encrypted) secret immediately with {@code
 * two_factor_enabled=false}; {@link #confirmEnrollment} re-reads that same stored secret, checks
 * the submitted code against it, and only then flips the flag to {@code true}. A user who abandons
 * enrollment just leaves a disabled, never-activated secret sitting in place — harmless, and
 * overwritten cleanly by a subsequent {@code /2fa/enable} call.
 */
@Service
public class TwoFactorService {

  private final UserRepository userRepository;
  private final TwoFactorSecretCipher cipher;
  private final AuthProperties props;
  private final CodeVerifier codeVerifier;
  private final QrGenerator qrGenerator = new ZxingPngQrGenerator();

  public TwoFactorService(
      UserRepository userRepository, TwoFactorSecretCipher cipher, AuthProperties props) {
    this.userRepository = userRepository;
    this.cipher = cipher;
    this.props = props;
    TimeProvider timeProvider = new SystemTimeProvider();
    this.codeVerifier =
        new DefaultCodeVerifier(new DefaultCodeGenerator(HashingAlgorithm.SHA1), timeProvider);
  }

  public TwoFactorEnrollment beginEnrollment(UUID userId, String email) {
    String secret = new DefaultSecretGenerator().generate();
    QrData qrData =
        new QrData.Builder()
            .label(email)
            .secret(secret)
            .issuer(props.twoFactor().issuer())
            .algorithm(HashingAlgorithm.SHA1)
            .digits(6)
            .period(30)
            .build();
    String qrCodeUri;
    try {
      qrCodeUri =
          Utils.getDataUriForImage(qrGenerator.generate(qrData), qrGenerator.getImageMimeType());
    } catch (Exception e) {
      throw new IllegalStateException("Failed to generate 2FA QR code", e);
    }
    // Stored disabled — a correct /2fa/verify call is what activates it.
    userRepository.updateTwoFactor(userId, false, cipher.encrypt(secret));
    return new TwoFactorEnrollment(secret, qrCodeUri);
  }

  /**
   * Returns true and activates 2FA if {@code totpCode} matches the pending stored secret; false
   * otherwise (secret stays disabled).
   */
  public boolean confirmEnrollment(UUID userId, String totpCode) {
    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new NoSuchElementException("No such user: " + userId));
    if (user.twoFactorSecretCiphertext() == null) {
      return false; // never began enrollment
    }
    String secret = cipher.decrypt(user.twoFactorSecretCiphertext());
    if (!codeVerifier.isValidCode(secret, totpCode)) {
      return false;
    }
    userRepository.updateTwoFactor(userId, true, user.twoFactorSecretCiphertext());
    return true;
  }

  /**
   * For the login-time challenge — checks a code against an already-*activated* user's stored
   * secret.
   */
  public boolean verifyLoginCode(User user, String totpCode) {
    if (!user.twoFactorEnabled() || user.twoFactorSecretCiphertext() == null) {
      return false;
    }
    String secret = cipher.decrypt(user.twoFactorSecretCiphertext());
    return codeVerifier.isValidCode(secret, totpCode);
  }
}
