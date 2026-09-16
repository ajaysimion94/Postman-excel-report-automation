package com.automation.auth.secrets;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AES-256-GCM encrypted vault holding named credentials, stored at
 * {@code <workspace>/.web-state/secrets.enc}.
 *
 * <p>Collection files and the filters folder stay free of plaintext credentials: a collection stores
 * a {@code {{VAR}}} reference and this vault holds the real value. The ciphertext never leaves the
 * machine — no value is ever returned to the browser, only variable names and a masked preview.</p>
 *
 * <p>A stored credential carries its auth type ({@code bearer}, {@code basic}, {@code apikey},
 * {@code noauth}, or {@code raw}) so a multi-field credential such as Basic auth is one named entry
 * rather than several unrelated secrets. Every field of a credential is also published to the
 * executor as {@code NAME_ROLE}, and the primary field additionally as plain {@code NAME}, so an
 * existing {@code {{NAME}}} reference keeps working.</p>
 *
 * <p>The AES key is derived via PBKDF2WithHmacSHA256 from a machine-specific string (OS username +
 * home directory), mirroring {@code CredentialStore}: the file is non-portable across machines and
 * accounts, and no per-run master password is required.</p>
 */
public final class SecretVault {

    private static final int PBKDF2_ITERATIONS = 310_000;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    /** Object keys used inside the encrypted payload. */
    private static final String CREDENTIALS_KEY = "credentials";

    /** Auth types a stored credential may declare, in the order the UI offers them. */
    public static final List<String> SUPPORTED_TYPES = List.of("noauth", "basic", "bearer", "apikey", "raw");

    /** Secret-bearing field names per auth type. {@code noauth} carries none by design. */
    private static final Map<String, List<String>> FIELDS_BY_TYPE = Map.of(
            "noauth", List.of(),
            "basic", List.of("username", "password"),
            "bearer", List.of("token"),
            "apikey", List.of("key", "value", "in"),
            "raw", List.of("value"));

    /** Variable names usable in {@code {{...}}} references: letters, digits, underscore. */
    private static final Pattern VARIABLE_NAME = Pattern.compile("[A-Za-z0-9_]+");
    private static final Pattern TEMPLATE = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)\\s*}}");

    private final Path storePath;
    private final ObjectMapper mapper = new ObjectMapper();

    public SecretVault(Path storePath) {
        this.storePath = storePath;
    }

    /** A named credential: an auth type plus the field values that belong to it. */
    public record Credential(String name, String type, Map<String, String> values) {
        public Credential {
            values = Map.copyOf(values);
        }
    }

    /** Returns true when the vault file exists on disk. */
    public boolean exists() {
        return Files.exists(storePath);
    }

    /** Creates a vault at the given path. Intended for tests and custom workspaces. */
    public static SecretVault at(Path storePath) {
        return new SecretVault(storePath);
    }

    /** Normalizes an auth type to one this vault supports, defaulting to {@code raw}. */
    public static String normalizeType(String type) {
        String candidate = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        return SUPPORTED_TYPES.contains(candidate) ? candidate : "raw";
    }

    /** The field names that carry values for an auth type, in display order. */
    public static List<String> fieldsFor(String type) {
        return FIELDS_BY_TYPE.getOrDefault(normalizeType(type), List.of("value"));
    }

    /** Returns every stored credential, sorted by name. */
    public Map<String, Credential> loadCredentials() throws Exception {
        if (!exists()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new TreeMap<>(loadData()));
    }

    /**
     * Returns every stored credential flattened into the execution variable map.
     *
     * <p>Each field is published as {@code NAME_ROLE}; the first populated field is also published as
     * plain {@code NAME} so that an existing {@code {{NAME}}} reference keeps resolving.</p>
     */
    public Map<String, String> loadAll() throws Exception {
        Map<String, String> flat = new LinkedHashMap<>();
        loadCredentials().forEach((name, credential) -> {
            String primary = null;
            for (String role : fieldsFor(credential.type())) {
                String value = credential.values().get(role);
                if (value == null || value.isBlank()) {
                    continue;
                }
                if (primary == null) {
                    primary = value;
                }
                if (!"raw".equals(credential.type())) {
                    flat.put((name + "_" + role).toUpperCase(Locale.ROOT), value);
                }
            }
            if (primary != null) {
                flat.put(name, primary);
            }
        });
        return Map.copyOf(flat);
    }

    /**
     * Returns the credential names with a masked preview of each field. Values are never included,
     * so the UI can show what is configured without exposing a credential to the browser.
     */
    public List<Map<String, Object>> list() throws Exception {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Map.Entry<String, Credential> entry : loadCredentials().entrySet()) {
            Credential credential = entry.getValue();
            List<Map<String, Object>> fields = new ArrayList<>();
            String primaryPreview = "";
            int primaryLength = 0;
            for (String role : fieldsFor(credential.type())) {
                String value = credential.values().getOrDefault(role, "");
                fields.add(fieldView(role, value));
                if (primaryLength == 0 && !value.isEmpty()) {
                    primaryPreview = mask(value);
                    primaryLength = value.length();
                }
            }
            Map<String, Object> view = new LinkedHashMap<String, Object>();
            view.put("name", credential.name());
            view.put("type", credential.type());
            view.put("preview", primaryPreview);
            view.put("length", primaryLength);
            view.put("fields", fields);
            entries.add(view);
        }
        return List.copyOf(entries);
    }

    private static Map<String, Object> fieldView(String role, String value) {
        Map<String, Object> field = new LinkedHashMap<String, Object>();
        field.put("key", role);
        field.put("preview", mask(value));
        field.put("length", value.length());
        return field;
    }

    /** Returns the stored value for a name, or null when it is not configured. */
    public String get(String name) throws Exception {
        return loadAll().get(name);
    }

    /** Returns the credential stored under a name, or null when it is not configured. */
    public Credential getCredential(String name) throws Exception {
        return loadCredentials().get(name);
    }

    /** Saves a single-value credential of type {@code raw}. A blank value removes the entry. */
    public void put(String name, String value) throws Exception {
        Map<String, Credential> credentials = new LinkedHashMap<>(loadCredentials());
        String key = requireValidName(name);
        if (value == null || value.isBlank()) {
            credentials.remove(key);
        } else {
            credentials.put(key, new Credential(key, "raw", Map.of("value", value)));
        }
        write(credentials);
    }

    /** Stores several single-value credentials. Entries with a blank value are removed. */
    public void putAll(Map<String, String> values) throws Exception {
        Map<String, Credential> credentials = new LinkedHashMap<>(loadCredentials());
        values.forEach((name, value) -> {
            String key = requireValidName(name);
            if (value == null || value.isBlank()) {
                credentials.remove(key);
            } else {
                credentials.put(key, new Credential(key, "raw", Map.of("value", value)));
            }
        });
        write(credentials);
    }

    /**
     * Stores several typed credentials from the UI. A credential whose fields are all blank is
     * removed; a credential with a type other than {@code noauth} must supply at least one value.
     */
    public void putCredentials(Map<String, Credential> credentials) throws Exception {
        Map<String, Credential> stored = new LinkedHashMap<>(loadCredentials());
        credentials.forEach((name, credential) -> {
            String key = requireValidName(name);
            String type = normalizeType(credential.type());
            Map<String, String> values = new LinkedHashMap<>();
            for (String role : fieldsFor(type)) {
                String value = credential.values().get(role);
                if (value != null && !value.isBlank()) {
                    values.put(role, value);
                }
            }
            if (values.isEmpty() && !"noauth".equals(type)) {
                throw new IllegalArgumentException("Enter a value for \"" + key + "\" before saving it.");
            }
            stored.put(key, new Credential(key, type, values));
        });
        write(stored);
    }

    /** Removes a credential. Returns true when an entry was removed. */
    public boolean remove(String name) throws Exception {
        Map<String, Credential> credentials = new LinkedHashMap<>(loadCredentials());
        boolean removed = credentials.remove(name) != null;
        if (removed) {
            write(credentials);
        }
        return removed;
    }

    /**
     * Validates a variable name. Names must be usable in a {@code {{...}}} reference and must not
     * collide with environment variables, so that {@code .env} settings keep working unchanged.
     */
    public static String requireValidName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Enter a variable name.");
        }
        if (trimmed.length() > 120) {
            throw new IllegalArgumentException("Variable names are limited to 120 characters.");
        }
        if (!VARIABLE_NAME.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Use letters, digits and underscores only, for example MY_TOKEN.");
        }
        if (System.getenv().containsKey(trimmed)) {
            throw new IllegalArgumentException("\"" + trimmed
                    + "\" is an operating system environment variable. Choose another name so the OS value is not overridden.");
        }
        return trimmed;
    }

    /** Returns every {@code {{VAR}}} name referenced by the given text, in first-seen order. */
    public static Set<String> referencedNames(String... texts) {
        Set<String> names = new LinkedHashSet<>();
        for (String text : texts) {
            if (text == null) {
                continue;
            }
            Matcher matcher = TEMPLATE.matcher(text);
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
        }
        return names;
    }

    /**
     * Turns a secret into a masked preview that still lets a human confirm which value is stored,
     * without revealing enough of it to be reused.
     */
    public static String mask(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.length() <= 4) {
            return "•".repeat(value.length());
        }
        String tail = value.substring(value.length() - Math.min(4, value.length()));
        return "•".repeat(Math.min(value.length() - tail.length(), 12)) + tail;
    }

    // ---- Encryption / Decryption --------------------------------------------------------

    private void write(Map<String, Credential> credentials) throws Exception {
        if (credentials.isEmpty()) {
            Files.deleteIfExists(storePath);
            return;
        }
        Files.createDirectories(storePath.getParent());

        SecureRandom rng = new SecureRandom();
        byte[] salt = new byte[SALT_BYTES];
        byte[] iv = new byte[IV_BYTES];
        rng.nextBytes(salt);
        rng.nextBytes(iv);

        List<StoredCredential> payload = credentials.values().stream()
                .map(credential -> StoredCredential.of(credential.name(), credential.type(), credential.values()))
                .toList();

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(salt), new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] ciphertext = cipher.doFinal(mapper.writeValueAsBytes(Map.of(CREDENTIALS_KEY, payload)));

        VaultWrapper wrapper = new VaultWrapper();
        wrapper.version = 2;
        wrapper.salt = Base64.getEncoder().encodeToString(salt);
        wrapper.iv = Base64.getEncoder().encodeToString(iv);
        wrapper.data = Base64.getEncoder().encodeToString(ciphertext);

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

    private Map<String, Credential> loadData() throws Exception {
        VaultWrapper wrapper = mapper.readValue(storePath.toFile(), VaultWrapper.class);
        byte[] salt = Base64.getDecoder().decode(wrapper.salt);
        byte[] iv = Base64.getDecoder().decode(wrapper.iv);
        byte[] ciphertext = Base64.getDecoder().decode(wrapper.data);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(salt), new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] plaintext = cipher.doFinal(ciphertext);

        JsonNode root = mapper.readTree(plaintext);
        Map<String, Credential> credentials = new LinkedHashMap<>();
        JsonNode stored = root.path(CREDENTIALS_KEY);
        if (stored.isArray()) {
            for (JsonNode node : stored) {
                StoredCredential entry = mapper.treeToValue(node, StoredCredential.class);
                String name = entry.name == null ? "" : entry.name.trim();
                if (name.isEmpty()) {
                    continue;
                }
                Map<String, String> values = new LinkedHashMap<>();
                if (entry.values != null) {
                    entry.values.forEach((role, value) -> {
                        if (value != null && !value.isBlank()) {
                            values.put(role, value);
                        }
                    });
                }
                credentials.put(name, new Credential(name, normalizeType(entry.type), values));
            }
            return credentials;
        }

        // Legacy format: a flat map of variable name to plaintext value, written before typed
        // credentials existed. Read it as a set of raw single-value credentials.
        root.fields().forEachRemaining(field -> {
            if (field.getValue().isTextual() && !field.getValue().asText().isBlank()) {
                credentials.put(field.getKey(),
                        new Credential(field.getKey(), "raw", Map.of("value", field.getValue().asText())));
            }
        });
        return credentials;
    }

    /**
     * Derives a 256-bit AES key from a machine-specific password using PBKDF2WithHmacSHA256.
     * The password is not a user secret — it is a machine-identity string that ties the encrypted
     * file to a specific OS user account and home directory.
     *
     * <p>Package-private so the vault tests can reproduce the on-disk format.</p>
     */
    static SecretKey deriveKey(byte[] salt) throws Exception {
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

    /** The outer JSON file structure: metadata in the clear, credential values encrypted. */
    public static final class VaultWrapper {
        public int version;
        public String salt;
        public String iv;
        public String data;
    }

    /** One credential as it appears inside the encrypted payload. */
    public static final class StoredCredential {
        public String name;
        public String type;
        public Map<String, String> values;

        static StoredCredential of(String name, String type, Map<String, String> values) {
            StoredCredential stored = new StoredCredential();
            stored.name = name;
            stored.type = type;
            stored.values = new LinkedHashMap<>(values);
            return stored;
        }
    }
}
