package org.toltec;

/** Selects which animation set a {@link Unit} uses. */
public enum Gender {
    MALE("man"),
    FEMALE("woman"),
    /** Non-human units (e.g. {@link Goblin}) that need their own animation set in the config. */
    GOBLIN("goblin");

    private final String key;

    Gender(String key) { this.key = key; }

    /** Config-file key for this gender ("man" / "woman"). */
    public String key() { return key; }
}
