package com.automation.cli;

import com.automation.auth.config.CredentialStore;
import com.automation.auth.config.UserProfile;

import java.io.BufferedReader;
import java.io.Console;
import java.io.InputStreamReader;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Handles the --config subcommand. Manages credential profiles stored in an
 * AES-256-GCM encrypted file at ~/.config/postman-automation/credentials.enc.
 *
 * <p>Supported actions:
 * <ul>
 *   <li>{@code --config}                    — interactively add / update a profile</li>
 *   <li>{@code --config --show}             — list all profiles</li>
 *   <li>{@code --config --switch <name>}    — switch the active profile</li>
 *   <li>{@code --config --delete <name>}    — delete a profile</li>
 * </ul>
 */
public final class ConfigCommand {

    private static final BufferedReader FALLBACK_READER =
            new BufferedReader(new InputStreamReader(System.in));

    private ConfigCommand() {
    }

    public static void run(ConfigAction action, String targetUser) throws Exception {
        CredentialStore store = CredentialStore.system();
        switch (action) {
            case ADD    -> runAdd(store);
            case SHOW   -> runShow(store);
            case SWITCH -> runSwitch(store, targetUser);
            case DELETE -> runDelete(store, targetUser);
        }
    }

    // ---- ADD -------------------------------------------------------------------

    private static void runAdd(CredentialStore store) throws Exception {
        Console console = System.console();
        if (console == null) {
            System.err.println("[WARN] No interactive console detected — input will NOT be masked.");
        }

        String profileName = prompt(console, "Profile name: ");
        if (profileName.isBlank()) {
            System.out.println("Error: Profile name cannot be empty.");
            System.exit(1);
        }

        // Warn if overwriting an existing profile
        if (store.exists() && store.listUsernames().contains(profileName)) {
            String confirm = prompt(console, "Profile \"" + profileName + "\" already exists. Overwrite? [y/N]: ");
            if (!confirm.equalsIgnoreCase("y")) {
                System.out.println("Aborted.");
                return;
            }
        }

        String apiUsername = prompt(console, "API username    (press Enter to skip): ");

        String apiPassword = null;
        if (!apiUsername.isBlank()) {
            char[] pass1 = promptPassword(console, "API password    : ");
            char[] pass2 = promptPassword(console, "Confirm password: ");
            if (!Arrays.equals(pass1, pass2)) {
                Arrays.fill(pass1, '\0');
                Arrays.fill(pass2, '\0');
                System.out.println("Error: Passwords do not match.");
                System.exit(1);
            }
            if (pass1.length > 0) {
                apiPassword = new String(pass1);
            }
            Arrays.fill(pass1, '\0');
            Arrays.fill(pass2, '\0');
        }

        String bearerToken = promptSecret(console, "Bearer token    (press Enter to skip): ");
        String apiKey      = promptSecret(console, "API key         (press Enter to skip): ");
        String apiKeyHeader = null;
        if (apiKey != null) {
            String header = prompt(console, "API key header  [X-API-Key]: ");
            apiKeyHeader = header.isBlank() ? "X-API-Key" : header;
        }

        UserProfile profile = new UserProfile(
                profileName,
                apiUsername.isBlank() ? null : apiUsername,
                apiPassword,
                bearerToken,
                apiKey,
                apiKeyHeader,
                Instant.now().toString()
        );

        store.save(profile);

        long total = store.listUsernames().size();
        System.out.println("Profile \"" + profileName + "\" saved.");
        if (total == 1) {
            System.out.println("This is now the active profile.");
        }
    }

    // ---- SHOW ------------------------------------------------------------------

    private static void runShow(CredentialStore store) throws Exception {
        if (!store.exists() || store.listUsernames().isEmpty()) {
            System.out.println("No profiles configured. Run with --config to add one.");
            return;
        }

        String activeUser = store.getActiveUsername();
        Map<String, UserProfile> all = store.loadAll();
        List<String> names = store.listUsernames();

        System.out.println("Configured profiles:");
        for (String name : names) {
            String marker   = name.equals(activeUser) ? " [active]" : "";
            UserProfile p   = all.get(name);
            String userHint = p.apiUsername() != null ? " (" + p.apiUsername() + ")" : "";
            System.out.println("  " + name + userHint + marker);
        }
    }

    // ---- SWITCH ----------------------------------------------------------------

    private static void runSwitch(CredentialStore store, String targetUser) throws Exception {
        if (targetUser == null || targetUser.isBlank()) {
            System.out.println("Error: --switch requires a username. Usage: --config --switch <username>");
            System.exit(1);
            return;
        }
        if (!store.exists()) {
            System.out.println("No credential store found. Run --config to add a profile first.");
            System.exit(1);
            return;
        }
        store.setActiveUser(targetUser); // throws IllegalArgumentException if not found
        System.out.println("Active profile switched to \"" + targetUser + "\".");
    }

    // ---- DELETE ----------------------------------------------------------------

    private static void runDelete(CredentialStore store, String targetUser) throws Exception {
        if (targetUser == null || targetUser.isBlank()) {
            System.out.println("Error: --delete requires a username. Usage: --config --delete <username>");
            System.exit(1);
            return;
        }
        if (!store.exists() || !store.listUsernames().contains(targetUser)) {
            System.out.println("Profile \"" + targetUser + "\" not found.");
            System.exit(1);
            return;
        }

        Console console = System.console();
        String confirm = prompt(console, "Delete profile \"" + targetUser + "\"? [y/N]: ");
        if (!confirm.equalsIgnoreCase("y")) {
            System.out.println("Aborted.");
            return;
        }

        store.delete(targetUser);
        System.out.println("Profile \"" + targetUser + "\" deleted.");
    }

    // ---- Console helpers -------------------------------------------------------

    private static String prompt(Console console, String message) {
        if (console != null) {
            String result = console.readLine(message);
            return result != null ? result.trim() : "";
        }
        System.out.print(message);
        try {
            String line = FALLBACK_READER.readLine();
            return line != null ? line.trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static char[] promptPassword(Console console, String message) {
        if (console != null) {
            char[] result = console.readPassword(message);
            return result != null ? result : new char[0];
        }
        // No console — input will be visible; user was already warned at the top of runAdd()
        System.out.print(message);
        try {
            String line = FALLBACK_READER.readLine();
            return line != null ? line.toCharArray() : new char[0];
        } catch (Exception e) {
            return new char[0];
        }
    }

    /**
     * Prompts for a secret value. Returns null if the user pressed Enter with no input
     * (signalling "skip"), so optional fields can be left empty.
     */
    private static String promptSecret(Console console, String message) {
        char[] chars = promptPassword(console, message);
        if (chars.length == 0) {
            return null;
        }
        String result = new String(chars);
        Arrays.fill(chars, '\0');
        return result;
    }
}
