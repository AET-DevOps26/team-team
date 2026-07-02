package com.team.bank.banking.client;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.team.bank.banking.config.EnableBankingConfig;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Mints the bearer tokens used to authenticate against Enable Banking. The RSA private key is
 * loaded once at construction; {@link #sign()} then issues a short-lived RS256 JWT per request. If
 * the key isn't configured the service still starts (key loading is best-effort) but signing fails
 * fast the first time it is attempted.
 */
@Component
public class EnableBankingJwtSigner {

  private static final Logger log = LoggerFactory.getLogger(EnableBankingJwtSigner.class);

  private final EnableBankingConfig config;
  private final RSAPrivateKey privateKey;

  @SuppressFBWarnings("CT_CONSTRUCTOR_THROW")
  public EnableBankingJwtSigner(EnableBankingConfig config) {
    this.config = config;
    this.privateKey = loadPrivateKey(config.getPrivateKeyPath());
  }

  private RSAPrivateKey loadPrivateKey(String path) {
    if (path == null || path.isBlank()) {
      log.warn(
          "Enable Banking private key path is not configured; JWT signing will be unavailable");
      return null;
    }
    try {
      // Strip the PEM armor and whitespace to recover the raw base64 PKCS#8 DER bytes.
      String pem = Files.readString(Path.of(path));
      String base64 =
          pem.replace("-----BEGIN PRIVATE KEY-----", "")
              .replace("-----END PRIVATE KEY-----", "")
              .replaceAll("\\s+", "");
      byte[] decoded = Base64.getDecoder().decode(base64);
      PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(decoded);
      KeyFactory keyFactory = KeyFactory.getInstance("RSA");
      return (RSAPrivateKey) keyFactory.generatePrivate(keySpec);
    } catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException e) {
      log.warn("Failed to load Enable Banking private key from {}: {}", path, e.getMessage());
      return null;
    }
  }

  public String sign() {
    if (privateKey == null) {
      throw new IllegalStateException("Enable Banking private key not configured");
    }
    try {
      Instant now = Instant.now();
      JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(config.getAppId()).build();
      // Enable Banking authenticates the caller via these JWT claims: a fixed issuer and
      // the API host as audience (taken from the configured base URL, defaulting to the
      // canonical host). Token is valid for one hour.
      String audience = java.net.URI.create(config.getBaseUrl()).getHost();
      JWTClaimsSet claims =
          new JWTClaimsSet.Builder()
              .issuer("enablebanking.com")
              .audience(audience != null ? audience : "api.enablebanking.com")
              .issueTime(Date.from(now))
              .expirationTime(Date.from(now.plusSeconds(3600)))
              .build();
      SignedJWT signedJWT = new SignedJWT(header, claims);
      signedJWT.sign(new RSASSASigner(privateKey));
      return signedJWT.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException("Failed to sign JWT", e);
    }
  }
}
