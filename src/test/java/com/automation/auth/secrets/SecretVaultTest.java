package com.automation.auth.secrets;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SecretVaultTest {

    @TempDir
    Path tempDir;

    private SecretVault vault() {
        return SecretVault.at(tempDir.resolve("secrets.enc"));
    }

    // ---- Round trip -----------------------------------------------------------------

    @Test
    void putAndGetRoundtrip() throws Exception {
        SecretVault v = vault();
        v.put("MY_TOKEN", "super-secret-value");
        assertEquals("super-secret-value", v.get("MY_TOKEN"));
        assertTrue(v.exists());
    }

    @Test
    void putAllStoresEveryEntry() throws Exception {
        SecretVault v = vault();
        v.putAll(Map.of("API_KEY", "key-123", "BEARER_TOKEN", "token-456"));
        Map<String, String> all = v.loadAll();
        assertEquals(2, all.size());
        assertEquals("key-123", all.get("API_KEY"));
        assertEquals("token-456", all.get("BEARER_TOKEN"));
    }

    @Test
    void putUpdatesExistingValue() throws Exception {
        SecretVault v = vault();
        v.put("TOKEN", "first");
        v.put("TOKEN", "second");
        assertEquals("second", v.get("TOKEN"));
        assertEquals(1, v.loadAll().size());
    }

    @Test
    void blankValueRemovesEntry() throws Exception {
        SecretVault v = vault();
        v.put("TOKEN", "value");
        v.put("TOKEN", "  ");
        assertFalse(v.loadAll().containsKey("TOKEN"));
    }

    @Test
    void removeDeletesEntry() throws Exception {
        SecretVault v = vault();
        v.put("TOKEN", "value");
        assertTrue(v.remove("TOKEN"));
        assertFalse(v.loadAll().containsKey("TOKEN"));
    }

    @Test
    void removeMissingEntryReturnsFalse() throws Exception {
        assertFalse(vault().remove("NEVER_SET"));
    }

    @Test
    void emptyVaultReportsNoFile() throws Exception {
        SecretVault v = vault();
        assertFalse(v.exists());
        assertTrue(v.loadAll().isEmpty());
    }

    // ---- Listing (never leaks values) ------------------------------------------------

    @Test
    void listReturnsNamesAndMaskedPreviewsOnly() throws Exception {
        SecretVault v = vault();
        v.put("MY_TOKEN", "abcdefghijklmnop");

        List<Map<String, Object>> listed = v.list();
        assertEquals(1, listed.size());
        Map<String, Object> entry = listed.get(0);
        assertEquals("MY_TOKEN", entry.get("name"));
        assertEquals(16, entry.get("length"));

        String preview = entry.get("preview").toString();
        assertTrue(preview.endsWith("mnop"), "The preview should keep a short readable tail");
        assertFalse(preview.contains("abcdefghijkl"), "The preview must not reveal the secret body");
        assertFalse(entry.containsKey("value"), "The listing must not carry the raw value");
    }

    @Test
    void listIsSortedByName() throws Exception {
        SecretVault v = vault();
        v.putAll(Map.of("ZEBRA", "1", "APPLE", "2", "MANGO", "3"));
        List<Map<String, Object>> listed = v.list();
        assertEquals(List.of("APPLE", "MANGO", "ZEBRA"), listed.stream().map(e -> e.get("name").toString()).toList());
    }

    // ---- Name validation -------------------------------------------------------------

    @Test
    void invalidNamesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> SecretVault.requireValidName(""));
        assertThrows(IllegalArgumentException.class, () -> SecretVault.requireValidName("   "));
        assertThrows(IllegalArgumentException.class, () -> SecretVault.requireValidName("has space"));
        assertThrows(IllegalArgumentException.class, () -> SecretVault.requireValidName("has-dash"));
        assertThrows(IllegalArgumentException.class, () -> SecretVault.requireValidName("{{BRACED}}"));
    }

    @Test
    void validNamesAreAccepted() {
        assertEquals("MY_TOKEN", SecretVault.requireValidName("MY_TOKEN"));
        assertEquals("apiKey2", SecretVault.requireValidName("  apiKey2  "));
    }

    @Test
    void osEnvironmentVariableNamesAreRejected() {
        // PATH is present in essentially every environment; using it would shadow the OS value.
        assertThrows(IllegalArgumentException.class, () -> SecretVault.requireValidName("PATH"));
    }

    // ---- Template references ---------------------------------------------------------

    @Test
    void referencedNamesFindsTemplateVariables() {
        Set<String> names = SecretVault.referencedNames(
                "{{BASE_URL}}/users/{{USER_ID}}", null, "{\"key\": \"{{API_KEY}}\"}", "no templates here");
        assertEquals(Set.of("BASE_URL", "USER_ID", "API_KEY"), names);
    }

    @Test
    void referencedNamesToleratesSpacing() {
        assertEquals(Set.of("TOKEN"), SecretVault.referencedNames("Bearer {{ TOKEN }}"));
    }

    // ---- Encryption ------------------------------------------------------------------

    @Test
    void storedFileDoesNotContainPlaintextSecrets() throws Exception {
        SecretVault v = vault();
        v.put("MY_TOKEN", "plaintext-should-not-appear");
        String content = Files.readString(tempDir.resolve("secrets.enc"));
        assertFalse(content.contains("plaintext-should-not-appear"),
                "Secret values must never be readable from the vault file");
    }

    @Test
    void storedFileDoesNotRevealVariableNames() throws Exception {
        SecretVault v = vault();
        v.put("MY_DISTINCTIVE_NAME", "value");
        String content = Files.readString(tempDir.resolve("secrets.enc"));
        assertFalse(content.contains("MY_DISTINCTIVE_NAME"),
                "The encrypted blob should not expose stored variable names");
    }

    @Test
    void clearingTheLastSecretRemovesTheFile() throws Exception {
        SecretVault v = vault();
        v.put("TOKEN", "value");
        assertTrue(v.exists());
        v.put("TOKEN", "");
        assertFalse(v.exists(), "Removing the final secret should not leave an empty vault behind");
    }

    // ---- Typed credentials -----------------------------------------------------------

    @Test
    void typedCredentialRoundTripsWithItsFields() throws Exception {
        SecretVault v = vault();
        v.putCredentials(Map.of("OPS_BASIC", new SecretVault.Credential("OPS_BASIC", "basic",
                Map.of("username", "ops-user", "password", "ops-pass"))));

        SecretVault.Credential stored = v.getCredential("OPS_BASIC");
        assertEquals("basic", stored.type());
        assertEquals("ops-user", stored.values().get("username"));
        assertEquals("ops-pass", stored.values().get("password"));
    }

    @Test
    void typedCredentialPublishesRoleAndPlainNameVariables() throws Exception {
        SecretVault v = vault();
        v.putCredentials(Map.of("OPS_BASIC", new SecretVault.Credential("OPS_BASIC", "basic",
                Map.of("username", "ops-user", "password", "ops-pass"))));

        Map<String, String> flat = v.loadAll();
        assertEquals("ops-user", flat.get("OPS_BASIC"), "The first field should stay addressable as {{NAME}}");
        assertEquals("ops-user", flat.get("OPS_BASIC_USERNAME"));
        assertEquals("ops-pass", flat.get("OPS_BASIC_PASSWORD"));
    }

    @Test
    void rawCredentialDoesNotPublishRoleVariables() throws Exception {
        SecretVault v = vault();
        v.put("MY_TOKEN", "token-value");
        Map<String, String> flat = v.loadAll();
        assertEquals("token-value", flat.get("MY_TOKEN"));
        assertFalse(flat.containsKey("MY_TOKEN_VALUE"), "A raw value needs no role alias");
    }

    @Test
    void listReportsTypeAndMaskedFieldsWithoutValues() throws Exception {
        SecretVault v = vault();
        v.putCredentials(Map.of("INT_KEY", new SecretVault.Credential("INT_KEY", "apikey",
                Map.of("key", "X-Client-Id", "value", "abcdefghijklmnop", "in", "header"))));

        Map<String, Object> entry = v.list().get(0);
        assertEquals("apikey", entry.get("type"));
        String rendered = entry.toString();
        assertFalse(rendered.contains("abcdefghijkl"), "The listing must not reveal the secret body");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) entry.get("fields");
        assertTrue(fields.stream().anyMatch(f -> "value".equals(f.get("key")) && (int) f.get("length") == 16),
                "Each field should be reported with its masked length");
    }

    @Test
    void noauthCredentialNeedsNoValue() throws Exception {
        SecretVault v = vault();
        v.putCredentials(Map.of("PUBLIC_ENDPOINT", new SecretVault.Credential("PUBLIC_ENDPOINT", "noauth", Map.of())));
        assertEquals("noauth", v.getCredential("PUBLIC_ENDPOINT").type());
    }

    @Test
    void credentialWithNoValuesIsRejected() {
        SecretVault v = vault();
        assertThrows(IllegalArgumentException.class, () -> v.putCredentials(Map.of(
                "EMPTY_TOKEN", new SecretVault.Credential("EMPTY_TOKEN", "bearer", Map.of("token", "  ")))));
    }

    @Test
    void unknownAuthTypeFallsBackToRaw() {
        assertEquals("raw", SecretVault.normalizeType("oauth2"));
        assertEquals("bearer", SecretVault.normalizeType("BEARER"));
    }

    @Test
    void legacyFlatVaultIsStillReadable() throws Exception {
        // A vault written before typed credentials existed holds a flat name-to-value map.
        Path path = tempDir.resolve("legacy.enc");
        SecretVault legacyWriter = SecretVault.at(path);
        legacyWriter.put("OLD_TOKEN", "legacy-value");
        // Rewrite the encrypted payload in the legacy flat shape using the vault's own key derivation.
        rewriteAsFlat(path, Map.of("OLD_TOKEN", "legacy-value"));

        SecretVault v = SecretVault.at(path);
        assertEquals("legacy-value", v.get("OLD_TOKEN"));
        assertEquals("raw", v.getCredential("OLD_TOKEN").type());
    }

    /**
     * Writes a vault file in the pre-typed-credential format: the decrypted payload is a flat
     * name-to-value map rather than an object holding a {@code credentials} array.
     */
    private static void rewriteAsFlat(Path path, Map<String, String> secrets) throws Exception {
        byte[] salt = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        byte[] iv = new byte[12];
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, SecretVault.deriveKey(salt), new GCMParameterSpec(128, iv));
        byte[] ciphertext = cipher.doFinal(new ObjectMapper().writeValueAsBytes(secrets));

        SecretVault.VaultWrapper wrapper = new SecretVault.VaultWrapper();
        wrapper.version = 1;
        wrapper.salt = Base64.getEncoder().encodeToString(salt);
        wrapper.iv = Base64.getEncoder().encodeToString(iv);
        wrapper.data = Base64.getEncoder().encodeToString(ciphertext);
        new ObjectMapper().writeValue(path.toFile(), wrapper);
    }
}
