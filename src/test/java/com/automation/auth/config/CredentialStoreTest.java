package com.automation.auth.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CredentialStoreTest {

    @TempDir
    Path tempDir;

    private CredentialStore store() {
        return CredentialStore.at(tempDir.resolve("credentials.enc"));
    }

    private static UserProfile profile(String name) {
        return new UserProfile(name, "user@" + name + ".com", "pass-" + name,
                "token-" + name, "key-" + name, "X-Api-Key", "2026-01-01T00:00:00Z");
    }

    // ---- exists() -------------------------------------------------------------------

    @Test
    void existsReturnsFalseBeforeFirstSave() {
        assertFalse(store().exists());
    }

    @Test
    void existsReturnsTrueAfterFirstSave() throws Exception {
        CredentialStore s = store();
        s.save(profile("alice"));
        assertTrue(s.exists());
    }

    // ---- save() + loadAll() ---------------------------------------------------------

    @Test
    void saveAndLoadRoundtrip() throws Exception {
        CredentialStore s = store();
        UserProfile original = profile("alice");
        s.save(original);

        Map<String, UserProfile> all = s.loadAll();
        assertEquals(1, all.size());
        UserProfile loaded = all.get("alice");
        assertNotNull(loaded);
        assertEquals("alice", loaded.profileName());
        assertEquals("user@alice.com", loaded.apiUsername());
        assertEquals("pass-alice", loaded.apiPassword());
        assertEquals("token-alice", loaded.bearerToken());
        assertEquals("key-alice", loaded.apiKey());
        assertEquals("X-Api-Key", loaded.apiKeyHeader());
    }

    @Test
    void saveMultipleProfilesRoundtrip() throws Exception {
        CredentialStore s = store();
        s.save(profile("alice"));
        s.save(profile("bob"));
        s.save(profile("carol"));

        Map<String, UserProfile> all = s.loadAll();
        assertEquals(3, all.size());
        assertTrue(all.containsKey("alice"));
        assertTrue(all.containsKey("bob"));
        assertTrue(all.containsKey("carol"));
    }

    @Test
    void saveUpdatesExistingProfile() throws Exception {
        CredentialStore s = store();
        s.save(profile("alice"));

        UserProfile updated = new UserProfile("alice", "newalice@example.com", "newpass",
                null, null, null, "2026-02-01T00:00:00Z");
        s.save(updated);

        UserProfile loaded = s.loadAll().get("alice");
        assertEquals("newalice@example.com", loaded.apiUsername());
        assertEquals("newpass", loaded.apiPassword());
        assertNull(loaded.bearerToken());
    }

    // ---- Active user ---------------------------------------------------------------

    @Test
    void firstSavedProfileBecomesActiveAutomatically() throws Exception {
        CredentialStore s = store();
        s.save(profile("alice"));
        assertEquals("alice", s.getActiveUsername());
    }

    @Test
    void subsequentProfilesDoNotChangeActiveUser() throws Exception {
        CredentialStore s = store();
        s.save(profile("alice"));
        s.save(profile("bob"));
        assertEquals("alice", s.getActiveUsername());
    }

    @Test
    void getActiveReturnsActiveProfile() throws Exception {
        CredentialStore s = store();
        s.save(profile("alice"));
        Optional<UserProfile> active = s.getActive();
        assertTrue(active.isPresent());
        assertEquals("alice", active.get().profileName());
    }

    @Test
    void getActiveReturnsEmptyWhenNoStoreExists() throws Exception {
        assertTrue(store().getActive().isEmpty());
    }

    // ---- setActiveUser() -----------------------------------------------------------

    @Test
    void setActiveUserSwitchesActiveProfile() throws Exception {
        CredentialStore s = store();
        s.save(profile("alice"));
        s.save(profile("bob"));
        s.setActiveUser("bob");
        assertEquals("bob", s.getActiveUsername());
    }

    @Test
    void setActiveUserThrowsForUnknownProfile() {
        CredentialStore s = store();
        assertThrows(Exception.class, () -> s.setActiveUser("nobody"));
    }

    // ---- listUsernames() -----------------------------------------------------------

    @Test
    void listUsernamesReturnsSortedNames() throws Exception {
        CredentialStore s = store();
        s.save(profile("charlie"));
        s.save(profile("alice"));
        s.save(profile("bob"));

        List<String> names = s.listUsernames();
        assertEquals(List.of("alice", "bob", "charlie"), names);
    }

    @Test
    void listUsernamesReturnsEmptyWhenNoStore() throws Exception {
        assertTrue(store().listUsernames().isEmpty());
    }

    // ---- delete() ------------------------------------------------------------------

    @Test
    void deleteRemovesProfile() throws Exception {
        CredentialStore s = store();
        s.save(profile("alice"));
        s.save(profile("bob"));
        s.delete("alice");

        Map<String, UserProfile> all = s.loadAll();
        assertEquals(1, all.size());
        assertFalse(all.containsKey("alice"));
        assertTrue(all.containsKey("bob"));
    }

    @Test
    void deleteActiveUserClearsActiveUsername() throws Exception {
        CredentialStore s = store();
        s.save(profile("alice"));
        assertEquals("alice", s.getActiveUsername());
        s.delete("alice");
        assertNull(s.getActiveUsername());
    }

    @Test
    void deleteNonActiveUserPreservesActiveUsername() throws Exception {
        CredentialStore s = store();
        s.save(profile("alice"));
        s.save(profile("bob"));
        s.setActiveUser("alice");
        s.delete("bob");
        assertEquals("alice", s.getActiveUsername());
    }

    @Test
    void deleteNonExistentProfileThrows() {
        CredentialStore s = store();
        assertThrows(Exception.class, () -> s.delete("ghost"));
    }

    // ---- Encryption sanity check ---------------------------------------------------

    @Test
    void fileContentsAreNotPlainText() throws Exception {
        CredentialStore s = store();
        s.save(profile("alice"));
        String content = java.nio.file.Files.readString(tempDir.resolve("credentials.enc"));
        assertFalse(content.contains("pass-alice"), "Password must not appear in plain text");
        assertFalse(content.contains("token-alice"), "Bearer token must not appear in plain text");
        assertFalse(content.contains("key-alice"), "API key must not appear in plain text");
    }
}
