package com.foleybooks.auth.token.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foleybooks.auth.common.ProblemDetailResponder;
import com.foleybooks.auth.config.JwtProperties;
import com.foleybooks.auth.config.SecurityConfig;
import com.foleybooks.auth.token.service.RsaKeyPairProvider;
import java.math.BigInteger;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * GET /oauth2/jwks (D-03, AU-15): the real SecurityConfig runs in-slice —
 * security is never disabled (C25) — so the anonymous 200 below also proves
 * the endpoint's spot on the constitutional public allowlist (C22, NFR-01).
 * The one-key RFC 7517 set must decode back to the live public key's modulus
 * and exponent (otherwise every downstream signature check would fail), and
 * must never carry private material (C24).
 */
@WebMvcTest(JwksController.class)
@Import({SecurityConfig.class, ProblemDetailResponder.class, JwksControllerTest.DevKeyConfig.class})
class JwksControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RsaKeyPairProvider keyPairProvider;

    @Autowired
    private ObjectMapper objectMapper;

    @TestConfiguration
    static class DevKeyConfig {

        @Bean
        RsaKeyPairProvider rsaKeyPairProvider() {
            // Same path dev takes: no PEMs, an ephemeral pair is generated at construction.
            return new RsaKeyPairProvider(
                    new JwtProperties("http://localhost:8081", Duration.ofMinutes(15), null, null));
        }
    }

    @Test
    void getJwks_whenRequestedAnonymously_returnsOneRsaSigningKey() throws Exception {
        mockMvc.perform(get("/oauth2/jwks"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.keys.length()").value(1))
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].use").value("sig"))
                .andExpect(jsonPath("$.keys[0].alg").value("RS256"))
                .andExpect(jsonPath("$.keys[0].kid").value(keyPairProvider.keyId()))
                .andExpect(header().string("Cache-Control", containsString("max-age=300")))
                .andExpect(header().string("Cache-Control", containsString("public")));
    }

    @Test
    void getJwks_whenRequested_returnsLivePublicModulusAndExponent() throws Exception {
        JsonNode jwk = jwk();

        RSAPublicKey key = keyPairProvider.publicKey();
        BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.get("n").asText()));
        BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.get("e").asText()));
        assertThat(modulus).isEqualTo(key.getModulus());
        assertThat(exponent).isEqualTo(key.getPublicExponent());
    }

    @Test
    void getJwks_whenRequested_exposesNoPrivateMaterial() throws Exception {
        JsonNode jwk = jwk();

        // "d" is the RSA private exponent; a public JWK has it never, and JCA-only
        // fields like "p"/"q" (CRT factors) must not leak either (C24).
        assertThat(jwk.has("d")).isFalse();
        assertThat(jwk.has("p")).isFalse();
        assertThat(jwk.has("q")).isFalse();
    }

    @Test
    void getJwks_whenRequested_usesUnpaddedBase64UrlMembers() throws Exception {
        JsonNode jwk = jwk();

        assertThat(jwk.get("n").asText()).doesNotContain("=", "+", "/");
        // 65537 → the constant every RSA validator recognizes.
        assertThat(jwk.get("e").asText()).isEqualTo("AQAB");
    }

    private JsonNode jwk() throws Exception {
        String body = mockMvc.perform(get("/oauth2/jwks"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("keys").get(0);
    }
}
