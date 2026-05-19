package com.automation.auth.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Manages an AES-256-GCM encrypted credential store at ~/.config/postman-automation/credentials.enc.
 *
 * <p>The file contains an outer JSON wrapper (unencrypted) with metadata and a base64-encoded
 * ciphertext blob. The ciphertext decrypts to a JSON map of profile name → UserProfile.</p>
 *
 * <p>The AES key is derived via PBKDF2WithHmacSHA256 from a machine-specific string
 * (OS username + home directory), so the file is non-portable across machines/accounts
 * and no per-run master password is required.</p>
 */
public final class CredentialStore {

    private static final int PBKDF2_ITERATIONS = 310_000;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final Path storePath;
    private final ObjectMapper mapper = new ObjectMapper();

    private CredentialStore(Path storePath) {
        this.storePath = storePath;
    }

    /** Returns the default system-level credential store located at ~/.config/postman-automation/credentials.enc. */
    public static CredentialStore system() {
        Path storeFile = Path.of(System.getProperty("user.home"))
                .resolve(".config")
                .resolve("postman-automation")
                .resolve("credentials.enc");
        return new CredentialStore(storeFile);
    }

    /** Creates a credential store at a custom path. Intended for testing only. */
    static CredentialStore at(Path storeFile) {
        return new CredentialStore(storeFile);
    }

    /** Returns true if the credential store file exists on disk. */
    public boolean exists() {
        return Files.exists(storePath);
    }

    /**
     * Saves (adds or updates) a profile. If this is the first profile, it is automatically
     * set as the active profile.
     */
    public void save(UserProfile profile) throws Exception {
        StoreData data = exists() ? loadStoreData() : new StoreData(new HashMap<>(), null);
        data.users().put(profile.profileName(), profile);
        // Auto-activate the first profile added
        String activeUser = data.activeUser();
        if (data.users().size() == 1 && activeUser == null) {
            activeUser = profile.profileName();
        }
        writeStoreData(new StoreData(data.users(), activeUser));
    }

    /** Returns all stored profiles keyed by profile name. */
    public Map<String, UserProfile> loadAll() throws Exception {
        if (!exists()) {
            return Map.of();
        }
        return Map.copyOf(loadStoreData().users());
    }

    /** Returns the currently active profile, if any. */
    public Optional<UserProfile> getActive() throws Exception {
        if (!exists()) {
            return Optional.empty();
        }
        StoreData data = loadStoreData();
        if (data.activeUser() == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(data.users().get(data.activeUser()));
    }

    /** Returns the name of the currently active profile, or null if none is set. */
    public String getActiveUsername() throws Exception {
        if (!exists()) {
            return null;
        }
        return loadStoreData().activeUser();
    }

    /** Returns all profile names sorted alphabetically. */
    public List<String> listUsernames() throws Exception {
        if (!exists()) {
            return List.of();
        }
        return loadStoreData().users().keySet().stream().sorted().toList();
    }

    /**
     * Sets the active profile. Throws {@link IllegalArgumentException} if the profile does not exist.
     */
    public void setActiveUser(String profileName) throws Exception {
        StoreData data = loadStoreData();
        if (!data.users().containsKey(profileName)) {
            throw new IllegalArgumentException("Profile \"" + profileName + "\" does not exist.");
        }
        writeStoreData(new StoreData(data.users(), profileName));
    }

    /**
     * Deletes the named profile. If it was the active profile, the active user is cleared.
     * Throws {@link IllegalArgumentException} if the profile does not exist.
     */
    public void delete(String profileName) throws Exception {
        StoreData data = loadStoreData();
        if (!data.users().containsKey(profileName)) {
            throw new IllegalArgumentException("Profile \"" + profileName + "\" does not exist.");
        }
        data.users().remove(profileName);
        String newActive = profileName.equals(data.activeUser()) ? null : data.activeUser();
        writeStoreData(new StoreData(data.users(), newActive));
    }

    // ---- Encryption / Decryption --------------------------------------------------------

    private void writeStoreData(StoreData data) throws Exception {
        Files.createDirectories(storePath.getParent());

        SecureRandom rng = new SecureRandom();
        byte[] salt = new byte[SALT_BYTES];
        byte[] iv   = new byte[IV_BYTES];
        rng.nextBytes(salt);
        rng.nextBytes(iv);

        SecretKey key = deriveKey(salt);
        byte[] plaintext = mapper.writeValueAsBytes(data.users());

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] ciphertext = cipher.doFinal(plaintext);

        StoreWrapper wrapper = new StoreWrapper();
        wrapper.version    = 1;
        wrapper.salt       = Base64.getEncoder().encodeToString(salt);
        wrapper.iv         = Base64.getEncoder().encodeToString(iv);
        wrapper.data       = Base64.getEncoder().encodeToString(ciphertext);
        wrapper.activeUser = data.activeUser();

        mapper.writeValue(storePath.toFile(), wrapper);

        // Restrict permissions to owner read/write only (POSIX systems)
        try {
            Files.setPosixFilePermissions(storePath, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
            ));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX (e.g. Windows) — skip silently
        }
    }

    private StoreData loadStoreData() throws Exception {
        StoreWrapper wrapper = mapper.readValue(storePath.toFile(), StoreWrapper.class);

        byte[] salt       = Base64.getDecoder().decode(wrapper.salt);
        byte[] iv         = Base64.getDecoder().decode(wrapper.iv);
        byte[] ciphertext = Base64.getDecoder().decode(wrapper.data);

        SecretKey key = deriveKey(salt);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] plaintext = cipher.doFinal(ciphertext);

        Map<String, UserProfile> users = mapper.readValue(
                plaintext,
                new TypeReference<HashMap<String, UserProfile>>() {}
        );
        return new StoreData(new HashMap<>(users), wrapper.activeUser);
    }

    /**
     * Derives a 256-bit AES key from a machine-specific password using PBKDF2WithHmacSHA256.
     * The password is not a user-supplied secret — it is a machine-identity string that ties
     * the encrypted file to a specific OS user account and home directory.
     */
    private static SecretKey deriveKey(byte[] salt) throws Exception {
        String machineId = System.getProperty("user.name")
                + ":" + System.getProperty("user.home")
                + ":postman-automation-v1";
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(machineId.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS);
        try {
            SecretKey tmp = factory.generateSecret(spec);
            return new SecretKeySpec(tmp.getEncoded(), "AES");
        } finally {
            ((PBEKeySpec) spec).clearPassword();
        }
    }

    // ---- Internal types -----------------------------------------------------------------

    private record StoreData(Map<String, UserProfile> users, String activeUser) {}

    /**
     * The outer JSON file structure. Public fields allow Jackson to serialize/deserialize
     * without requiring getters or annotations on every field.
     */
    public static final class StoreWrapper {
        public int    version;
        public String salt;
        public String iv;
        public String data;
        public String activeUser;
    }
}
