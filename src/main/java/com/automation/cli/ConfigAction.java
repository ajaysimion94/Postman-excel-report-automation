package com.automation.cli;

/** Describes what action the --config subcommand should perform. */
public enum ConfigAction {
    /** Add or update a credential profile (default when --config is used without a sub-flag). */
    ADD,
    /** List all configured profiles and mark the active one. */
    SHOW,
    /** Switch the active profile to the named one. */
    SWITCH,
    /** Delete the named profile. */
    DELETE
}
