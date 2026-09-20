package com.foleybooks.auth.token.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.foleybooks.auth.config.JwtProperties;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Keypair sourcing rules of AU-15 (D-03, AGENTS.md §5, C19): dev auto-generates
 * when no PEM is configured, PEM env values load exactly, public-only configs
 * and mismatched pairs fail fast at startup, and the kid is the deterministic
 * RFC 7638 thumbprint the JWKS will publish.
 */
class RsaKeyPairProviderTest {

    private static JwtProperties properties(String privatePem, String publicPem) {
        return new JwtProperties("http://localhost:8081", Duration.ofMinutes(15), privatePem, publicPem);
    }

    private static KeyPair generateTestPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String pem(String type, byte[] der) {
        String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }

    @Test
    void constructor_whenNoPemsConfigured_autoGeneratesUsableRsaPair() throws Exception {
        RsaKeyPairProvider provider = new RsaKeyPairProvider(properties(null, null));

        assertThat(provider.publicKey().getAlgorithm()).isEqualTo("RSA");
        assertThat(provider.publicKey().getModulus().bitLength()).isGreaterThanOrEqualTo(2048);

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(provider.privateKey());
        signer.update("foley".getBytes());
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(provider.publicKey());
        verifier.update("foley".getBytes());
        assertThat(verifier.verify(signer.sign())).isTrue();
    }

    @Test
    void constructor_whenNoPemsConfigured_eachInstanceGetsItsOwnPair() {
        RsaKeyPairProvider first = new RsaKeyPairProvider(properties("", " "));
        RsaKeyPairProvider second = new RsaKeyPairProvider(properties(null, null));

        assertThat(first.publicKey().getModulus()).isNotEqualTo(second.publicKey().getModulus());
        assertThat(first.keyId()).isNotEqualTo(second.keyId());
    }

    @Test
    void constructor_whenPrivatePemConfigured_derivesMatchingPublicAndStableKid() throws Exception {
        KeyPair pair = generateTestPair();
        String privatePem = pem("PRIVATE KEY", pair.getPrivate().getEncoded());

        RsaKeyPairProvider provider = new RsaKeyPairProvider(properties(privatePem, null));

        assertThat(((RSAPublicKey) pair.getPublic()).getModulus())
                .isEqualTo(provider.publicKey().getModulus());
        // Same key material in → same kid out, so restarts do not invalidate cached JWKS mappings.
        assertThat(new RsaKeyPairProvider(properties(privatePem, null)).keyId())
                .isEqualTo(provider.keyId());
    }

    @Test
    void constructor_whenBothPemsConfigured_loadsExactPair() throws Exception {
        KeyPair pair = generateTestPair();
        String privatePem = pem("PRIVATE KEY", pair.getPrivate().getEncoded());
        String publicPem = pem("PUBLIC KEY", pair.getPublic().getEncoded());

        RsaKeyPairProvider provider = new RsaKeyPairProvider(properties(privatePem, publicPem));

        assertThat(((RSAPublicKey) pair.getPublic()).getModulus())
                .isEqualTo(provider.publicKey().getModulus());
        assertThat(((RSAPublicKey) pair.getPublic()).getPublicExponent())
                .isEqualTo(provider.publicKey().getPublicExponent());
    }

    @Test
    void constructor_whenPemsCarryLiteralNewlineEscapes_stillParses() throws Exception {
        KeyPair pair = generateTestPair();
        String singleLine = pem("PRIVATE KEY", pair.getPrivate().getEncoded())
                .replace("\n", "\\n");

        RsaKeyPairProvider provider = new RsaKeyPairProvider(properties(singleLine, null));

        assertThat(provider.publicKey().getModulus())
                .isEqualTo(((RSAPublicKey) pair.getPublic()).getModulus());
    }

    @Test
    void constructor_whenPublicPemDoesNotMatchPrivateKey_failsFast() throws Exception {
        KeyPair signing = generateTestPair();
        KeyPair other = generateTestPair();

        assertThatThrownBy(() -> new RsaKeyPairProvider(properties(
                pem("PRIVATE KEY", signing.getPrivate().getEncoded()),
                pem("PUBLIC KEY", other.getPublic().getEncoded()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void constructor_whenOnlyPublicPemConfigured_failsFast() throws Exception {
        KeyPair pair = generateTestPair();

        assertThatThrownBy(() -> new RsaKeyPairProvider(properties(
                null, pem("PUBLIC KEY", pair.getPublic().getEncoded()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot sign");
    }

    @Test
    void constructor_whenPrivatePemIsGarbage_failsFast() {
        assertThatThrownBy(() -> new RsaKeyPairProvider(properties("-----BEGIN PRIVATE KEY-----\nnope\n", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_PRIVATE_KEY");
    }

    @Test
    void keyId_whenGenerated_isRfc7638ThumbprintOfPublicJwk() throws Exception {
        KeyPair pair = generateTestPair();
        RSAPublicKey publicKey = (RSAPublicKey) pair.getPublic();
        RsaKeyPairProvider provider = new RsaKeyPairProvider(
                properties(pem("PRIVATE KEY", pair.getPrivate().getEncoded()), null));

        // Independent recomputation of RFC 7638 §3: {"e":...,"kty":"RSA","n":...}, SHA-256, base64url.
        String canonical = "{\"e\":\"%s\",\"kty\":\"RSA\",\"n\":\"%s\"}".formatted(
                encode(publicKey.getPublicExponent()), encode(publicKey.getModulus()));
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);

        assertThat(provider.keyId()).isEqualTo(expected);
    }

    @Test
    void base64UrlUnsigned_whenValueHasHighBit_dropsSignByte() {
        // 0x80 serializes as [0x00, 0x80] via toByteArray(); the JWK form is just [0x80].
        assertThat(RsaKeyPairProvider.base64UrlUnsigned(new BigInteger("128")))
                .isEqualTo("gA");
        assertThat(RsaKeyPairProvider.base64UrlUnsigned(BigInteger.ONE)).isEqualTo("AQ");
    }

    private static String encode(BigInteger value) {
        // Deliberately NOT RsaKeyPairProvider.base64UrlUnsigned — the test recomputes
        // the RFC 7638 thumbprint independently so a broken encoder cannot hide.
        byte[] bytes = value.toByteArray();
        byte[] magnitude = bytes[0] == 0 ? Arrays.copyOfRange(bytes, 1, bytes.length) : bytes;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(magnitude);
    }
}
