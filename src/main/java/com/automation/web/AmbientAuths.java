package com.automation.web;

import com.automation.auth.VariableResolver;
import com.automation.auth.config.CredentialStore;
import com.automation.auth.secrets.SecretVault;
import com.automation.filter.FilterAuthSpec;
import com.automation.filter.FilterLoader;
import com.automation.filter.FilterSpec;
import com.automation.postman.AuthDefinition;
import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvEntry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Read-only inventory of every ambient credential source that can reach a request, plus which one
 * would actually win for the request the user is looking at.
 *
 * <p>The source order mirrors how {@code CredentialLoader} actually merges them: a value set on the
 * request wins outright, then the encrypted vault, then the selected filter's auth block, then the
 * active CLI credential-store profile, then {@code .env} (and OS environment variables). The
 * loader applies filter overrides after the credential store, so the filter outranks the store.</p>
 *
 * <p>No secret value ever leaves this class. Every value is reduced to the vault's masked preview.</p>
 */
final class AmbientAuths {
    private AmbientAuths() {
    }

    /** Ambient sources in resolution order, highest precedence first. */
    enum Source {
        VAULT(0, "Encrypted vault", "Stored in the app on this machine"),
        FILTER(1, "Selected filter", "Declared in the filter file for this run"),
        CLI(2, "CLI profile", "The active profile in your CLI credential store"),
        DOTENV(3, ".env", "Environment variables for this workspace");

        final int order;
        final String label;
        final String detail;

        Source(int order, String label, String detail) {
            this.order = order;
            this.label = label;
            this.detail = detail;
        }
    }

    /** The variable keys each auth field falls back to, in the order the executor tries them. */
    private record Field(String role, List<String> fallbacks) {
    }

    private static final Map<String, List<Field>> FIELDS = Map.of(
            "basic", List.of(
                    new Field("username", List.of("API_USERNAME", "USERNAME")),
                    new Field("password", List.of("API_PASSWORD", "PASSWORD"))),
            "bearer", List.of(
                    new Field("token", List.of("BEARER_TOKEN", "TOKEN"))),
            "apikey", List.of(
                    new Field("key", List.of("APIKEY_HEADER")),
                    new Field("value", List.of("API_KEY", "APIKEY"))));

    /** Matches a value that is nothing but a single {@code {{NAME}}} reference. */
    private static final java.util.regex.Pattern REFERENCE_ONLY =
            java.util.regex.Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)\\s*}}");

    /** Auth types the engine can actually send, and the label the UI uses for each. */
    private static final Map<String, String> TYPE_LABELS = Map.of(
            "basic", "Basic Auth",
            "bearer", "Bearer Token",
            "apikey", "API Key");

    /**
     * Builds the inventory.
     *
     * @param vault         the encrypted vault managed by the app
     * @param workspace     workspace root, used to resolve a relative filter path
     * @param envPath       the {@code .env} file the server was started with, or null
     * @param filterPath    workspace-relative path of the selected filter, or null
     * @param requestAuth   the auth on the open request (already including folder/collection
     *                      inheritance), or null
     * @param collectionVars collection variables, used to resolve a {@code {{REF}}} on the request
     */
    static Map<String, Object> inventory(SecretVault vault, Path workspace, Path envPath,
                                         String filterPath, AuthDefinition requestAuth,
                                         Map<String, String> collectionVars, Set<String> referenced) {
        Map<String, String> vaultValues = vaultValues(vault);
        Map<String, String> cliValues = cliValues();
        Map<String, String> filterValues = filterValues(workspace, filterPath);
        Map<String, String> envValues = envValues(envPath);

        // A workspace can carry hundreds of unrelated environment variables. Only keys that could act
        // as a credential for this request are listed: the engine's own fallback keys, plus anything
        // the open collection actually references as {{NAME}}.
        Set<String> relevant = new java.util.TreeSet<>(knownFallbackKeys());
        if (referenced != null) {
            relevant.addAll(referenced);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("envPath", relative(workspace, envPath));
        response.put("filterPath", filterPath == null || filterPath.isBlank() ? "" : filterPath);
        response.put("cliProfile", activeCliProfileName());

        List<Map<String, Object>> groups = new ArrayList<>();
        groups.add(declaredGroup(requestAuth, collectionVars));
        groups.add(group(Source.VAULT, vaultDisplayEntries(vault)));
        groups.add(group(Source.FILTER, keyValueEntries(filterValues, relevant)));
        groups.add(group(Source.CLI, keyValueEntries(cliValues, relevant)));
        groups.add(group(Source.DOTENV, keyValueEntries(envValues, relevant)));
        response.put("sources", groups);

        List<Placed2> placed = List.of(new Placed2(Source.VAULT, vaultValues),
                new Placed2(Source.FILTER, filterValues), new Placed2(Source.CLI, cliValues),
                new Placed2(Source.DOTENV, envValues));
        response.put("effective", effective(requestAuth, collectionVars, ambient(placed), placed));
        return response;
    }

    /**
     * The ambient layers flattened exactly as the executor sees them, so a {@code {{REF}}} written by
     * the credential picker resolves here the same way it resolves when the request is actually sent.
     */
    private static Map<String, String> ambient(List<Placed2> sources) {
        Map<String, String> merged = new LinkedHashMap<>();
        // Sources arrive highest-precedence first, so applying them backwards lets the highest win.
        for (int index = sources.size() - 1; index >= 0; index--) {
            merged.putAll(sources.get(index).values());
        }
        return merged;
    }

    /** A source's variable map, paired with its precedence position. */
    private record Placed2(Source source, Map<String, String> values) {
    }

    /** Renders a path relative to the workspace when it is inside it, otherwise its file name. */
    private static String relative(Path workspace, Path path) {
        if (path == null) {
            return "";
        }
        Path root = workspace.toAbsolutePath().normalize();
        Path absolute = path.toAbsolutePath().normalize();
        return absolute.startsWith(root) ? root.relativize(absolute).toString().replace('\\', '/') : absolute.getFileName().toString();
    }

    // ---- Sources -------------------------------------------------------------------------

    /**
     * Shows one row per stored credential rather than the flattened {@code NAME + NAME_ROLE} map.
     * Typed credentials (e.g. Basic auth) publish role aliases, but those are internal to the
     * executor — the panel only needs the credential name and a masked preview of its primary value.
     */
    private static List<Map<String, Object>> vaultDisplayEntries(SecretVault vault) {
        try {
            return vault.list().stream().map(entry -> {
                Map<String, Object> view = new LinkedHashMap<String, Object>();
                view.put("name", entry.get("name"));
                view.put("preview", entry.get("preview"));
                view.put("length", entry.get("length"));
                return view;
            }).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    /** The vault publishes NAME plus NAME_ROLE for every populated field. */
    private static Map<String, String> vaultValues(SecretVault vault) {
        try {
            return new TreeMap<>(vault.loadAll());
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** Only the active CLI profile is applied at runtime, so only it is listed. */
    private static Map<String, String> cliValues() {
        Map<String, String> values = new TreeMap<>();
        try {
            CredentialStore store = CredentialStore.system();
            store.getActive().ifPresent(profile -> {
                putIfNotBlank(values, "API_USERNAME", profile.apiUsername());
                putIfNotBlank(values, "API_PASSWORD", profile.apiPassword());
                putIfNotBlank(values, "BEARER_TOKEN", profile.bearerToken());
                putIfNotBlank(values, "API_KEY", profile.apiKey());
                putIfNotBlank(values, "APIKEY_HEADER", profile.apiKeyHeader());
            });
        } catch (Exception ignored) {
            // A missing or unreadable store simply contributes nothing.
        }
        return values;
    }

    private static Map<String, String> filterValues(Path workspace, String filterPath) {
        Map<String, String> values = new TreeMap<>();
        if (filterPath == null || filterPath.isBlank()) {
            return values;
        }
        try {
            Path resolved = workspace.resolve(filterPath).toAbsolutePath().normalize();
            if (!resolved.startsWith(workspace.toAbsolutePath().normalize()) || !Files.isRegularFile(resolved)) {
                return values;
            }
            FilterLoader.LoadedFilter loaded = FilterLoader.load(resolved, null, null);
            FilterSpec spec = loaded == null ? null : loaded.spec();
            if (spec == null) {
                return values;
            }
            FilterAuthSpec auth = spec.auth();
            if (auth != null) {
                putIfNotBlank(values, "API_USERNAME", auth.username());
                putIfNotBlank(values, "API_PASSWORD", auth.password());
                putIfNotBlank(values, "BEARER_TOKEN", auth.bearerToken());
                putIfNotBlank(values, "API_KEY", auth.apiKey());
                putIfNotBlank(values, "APIKEY_HEADER", auth.apiKeyHeader());
            }
        } catch (Exception ignored) {
            // An unparseable filter contributes nothing rather than breaking the panel.
        }
        return values;
    }

    /** The {@code .env} file first, then OS environment variables for keys the file omits. */
    private static Map<String, String> envValues(Path envPath) {
        Map<String, String> values = new TreeMap<>();
        if (envPath != null && Files.exists(envPath)) {
            try {
                Path parent = envPath.toAbsolutePath().getParent();
                Dotenv dotenv = Dotenv.configure()
                        .directory(parent == null ? "." : parent.toString())
                        .filename(envPath.getFileName().toString())
                        .ignoreIfMalformed()
                        .ignoreIfMissing()
                        .load();
                for (DotenvEntry entry : dotenv.entries()) {
                    putIfNotBlank(values, entry.getKey(), entry.getValue());
                }
            } catch (Exception ignored) {
                // A malformed .env is reported by the run itself; the panel just omits it.
            }
        }
        System.getenv().forEach(values::putIfAbsent);
        return values;
    }

    // ---- Shaping -------------------------------------------------------------------------

    private static List<Map<String, Object>> keyValueEntries(Map<String, String> values, Set<String> filter) {
        List<Map<String, Object>> entries = new ArrayList<>();
        values.forEach((key, value) -> {
            if (filter != null && !filter.contains(key)) {
                return;
            }
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("name", key);
            entry.put("preview", SecretVault.mask(value));
            entry.put("length", value == null ? 0 : value.length());
            entries.add(entry);
        });
        return entries;
    }

    private static Map<String, Object> group(Source source, List<Map<String, Object>> entries) {
        Map<String, Object> group = new LinkedHashMap<String, Object>();
        group.put("id", source.name().toLowerCase(Locale.ROOT));
        group.put("label", source.label);
        group.put("detail", source.detail);
        group.put("order", source.order);
        group.put("entries", entries);
        return group;
    }

    /** The request's own auth, listed with its source so the panel can show why it wins. */
    private static Map<String, Object> declaredGroup(AuthDefinition requestAuth, Map<String, String> collectionVars) {
        List<Map<String, Object>> entries = new ArrayList<>();
        String type = requestAuth == null || requestAuth.type() == null ? "noauth" : requestAuth.type();
        String authType = type.toLowerCase(Locale.ROOT);
        if (requestAuth != null && !requestAuth.isNone() && FIELDS.containsKey(authType)) {
            Map<String, String> raw = requestAuth.values() == null ? Map.of() : requestAuth.values();
            for (Field field : FIELDS.get(authType)) {
                String configured = raw.get(field.role());
                if (configured == null || configured.isBlank()) {
                    continue;
                }
                Map<String, Object> entry = new LinkedHashMap<String, Object>();
                entry.put("name", field.role());
                String resolved = resolve(configured, collectionVars);
                boolean reference = resolved == null || resolved.isBlank() || resolved.contains("{{");
                entry.put("preview", reference ? configured : SecretVault.mask(resolved));
                entry.put("length", reference ? 0 : resolved.length());
                entry.put("reference", reference);
                entries.add(entry);
            }
        }
        Map<String, Object> group = new LinkedHashMap<String, Object>();
        group.put("id", "request");
        group.put("label", "This request");
        group.put("detail", "Set on the request you have open");
        group.put("order", -1);
        group.put("type", authType);
        group.put("typeLabel", TYPE_LABELS.getOrDefault(authType, authType));
        group.put("declared", !entries.isEmpty());
        group.put("entries", entries);
        return group;
    }

    /** Resolves a {@code {{NAME}}} reference against collection variables, leaving others intact. */
    private static String resolve(String value, Map<String, String> variables) {
        if (value == null || variables == null || variables.isEmpty()) {
            return value;
        }
        return VariableResolver.resolve(value, variables);
    }

    /**
     * Works out which source would supply the credential for the open request, mirroring the
     * executor's own fallback walk. Each entry is marked so the panel can grey out shadowed rows.
     */
    private static Map<String, Object> effective(AuthDefinition requestAuth, Map<String, String> collectionVars,
                                                 Map<String, String> ambient, List<Placed2> sources) {
        Map<String, Object> result = new LinkedHashMap<>();
        String authType = requestAuth == null || requestAuth.type() == null
                ? "noauth" : requestAuth.type().toLowerCase(Locale.ROOT);
        result.put("type", authType);
        result.put("typeLabel", TYPE_LABELS.getOrDefault(authType, authType));
        if (requestAuth == null || requestAuth.isNone() || !FIELDS.containsKey(authType)) {
            result.put("summary", "This request sends no Authorization header. Nothing stored below is used.");
            result.put("fields", List.of());
            return result;
        }

        List<Map<String, Object>> fields = new ArrayList<>();
        StringBuilder sentence = new StringBuilder();
        for (Field field : FIELDS.get(authType)) {
            String configured = authDefinitionValue(requestAuth, field.role());
            PlacedWinner winner = decide(configured, field, collectionVars, ambient, sources);
            Map<String, Object> view = new LinkedHashMap<String, Object>();
            view.put("role", field.role());
            view.put("key", winner.key());
            view.put("source", winner.sourceId());
            view.put("sourceLabel", winner.sourceLabel());
            view.put("resolved", winner.resolved());
            fields.add(view);
            if (winner.sourceId() != null) {
                if (sentence.length() > 0) {
                    sentence.append("; ");
                }
                sentence.append(field.role()).append(" from ").append(winner.sourceLabel().toLowerCase(Locale.ROOT));
            }
        }
        result.put("fields", fields);
        result.put("summary", sentence.length() == 0
                ? "This request is set to " + result.get("typeLabel") + ", but no credential is configured for it."
                : "This request will send its " + result.get("typeLabel") + " using " + sentence + ".");
        return result;
    }

    /** Which source actually wins for one auth field, under which key, and whether it resolved. */
    private record PlacedWinner(String sourceId, String sourceLabel, String key, boolean resolved) {
    }

    private static PlacedWinner decide(String configured, Field field, Map<String, String> collectionVars,
                                       Map<String, String> ambient, List<Placed2> sources) {
        if (configured != null && !configured.isBlank()) {
            // The executor resolves a request value against collection variables plus every ambient
            // layer, so a {{NAME}} reference must resolve here too. Otherwise the panel would blame
            // .env for a token that actually comes from the vault.
            Map<String, String> merged = new LinkedHashMap<>(collectionVars);
            merged.putAll(ambient);
            String resolved = resolve(configured, merged);
            if (resolved != null && !resolved.isBlank() && !resolved.contains("{{")) {
                String reference = plainReference(configured);
                if (reference != null) {
                    for (Placed2 source : sources) {
                        String value = source.values().get(reference);
                        if (value != null && !value.isBlank()) {
                            return new PlacedWinner(source.source().name().toLowerCase(Locale.ROOT),
                                    source.source().label, reference, true);
                        }
                    }
                }
                return new PlacedWinner("request", "This request", field.role(), true);
            }
        }
        for (Placed2 source : sources) {
            for (String key : field.fallbacks()) {
                String value = source.values().get(key);
                if (value == null || value.isBlank()) {
                    continue;
                }
                String resolved = resolve(value, ambient);
                if (resolved != null && !resolved.isBlank()) {
                    return new PlacedWinner(source.source().name().toLowerCase(Locale.ROOT), source.source().label,
                            key, true);
                }
            }
        }
        return new PlacedWinner(null, "nothing configured", null, false);
    }

    /** The single {@code {{NAME}}} a value is, or null when it is a literal or a mixed template. */
    private static String plainReference(String value) {
        var matcher = REFERENCE_ONLY.matcher(value.trim());
        return matcher.matches() ? matcher.group(1) : null;
    }

    private static String authDefinitionValue(AuthDefinition auth, String role) {
        if (auth == null || auth.values() == null) {
            return null;
        }
        return auth.values().get(role);
    }

    private static void putIfNotBlank(Map<String, String> values, String key, String value) {
        if (key == null || key.isBlank() || value == null || value.isBlank()) {
            return;
        }
        values.put(key, value);
    }

    /** Keys the panel knows how to interpret, used by tests to assert the wiring stays in sync. */
    static Set<String> knownFallbackKeys() {
        Set<String> keys = new java.util.TreeSet<>();
        FIELDS.values().forEach(fields -> fields.forEach(field -> keys.addAll(field.fallbacks())));
        return keys;
    }

    /** Returns the name of the active CLI credential store profile, or null when none is set. */
    private static String activeCliProfileName() {
        try {
            return CredentialStore.system().getActiveUsername();
        } catch (Exception e) {
            return null;
        }
    }
}
