package org.toltec.editor.util;

import org.toltec.unit.Direction8;
import org.toltec.unit.UnitStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Best-effort filename parsing for the "auto-import" buttons and the asset
 * palette's keyword search. Filenames are split into tokens on {@code _},
 * {@code -} and spaces — e.g. {@code Attack_Bow_Body_000} → {@code [Attack,
 * Bow, Body, 000]} — and each token is matched, case-insensitively, against
 * small built-in dictionaries. None of this needs to be perfect: everything
 * it produces is either applied to an explicit "auto-import" action the
 * person triggered on purpose, or used to narrow a search list — a missed or
 * over-eager match is never destructive.
 */
public final class FilenameParser {

    private FilenameParser() {}

    public static String stripExtension(String filename) {
        int i = filename.lastIndexOf('.');
        return i > 0 ? filename.substring(0, i) : filename;
    }

    public static List<String> tokens(String filenameNoExt) {
        List<String> out = new ArrayList<>();
        for (String t : filenameNoExt.split("[_\\-\\s]+")) {
            if (!t.isBlank()) out.add(t);
        }
        return out;
    }

    /** Angle is usually the last token, so the end of the filename is tried first. */
    public static Optional<Direction8> detectAngle(String filenameNoExt) {
        List<String> toks = tokens(filenameNoExt);
        for (int i = toks.size() - 1; i >= 0; i--) {
            Optional<Direction8> d = matchDirectionToken(toks.get(i));
            if (d.isPresent()) return d;
        }
        return Optional.empty();
    }

    public static Optional<UnitStatus> detectStatus(String filenameNoExt) {
        for (String tok : tokens(filenameNoExt)) {
            UnitStatus s = STATUS_WORDS.get(tok.toLowerCase(Locale.ROOT));
            if (s != null) return Optional.of(s);
        }
        return Optional.empty();
    }

    public static Optional<String> detectWeapon(String filenameNoExt) {
        for (String tok : tokens(filenameNoExt)) {
            String low = tok.toLowerCase(Locale.ROOT);
            if (KNOWN_WEAPONS.contains(low)) return Optional.of(low);
        }
        return Optional.empty();
    }

    /** Every token except the ones that look like an angle, lower-cased and de-duplicated — for the search filter. */
    public static List<String> keywordTokens(String filenameNoExt) {
        List<String> out = new ArrayList<>();
        for (String tok : tokens(filenameNoExt)) {
            if (matchDirectionToken(tok).isPresent()) continue;
            String low = tok.toLowerCase(Locale.ROOT);
            if (!out.contains(low)) out.add(low);
        }
        return out;
    }

    // =========================================================================
    // Dictionaries
    // =========================================================================

    private static Optional<Direction8> matchDirectionToken(String tok) {
        String t = tok.trim();
        if (t.matches("\\d{1,3}")) {
            try {
                int deg = Integer.parseInt(t);
                if (deg < 360) return Optional.of(Direction8.fromAngle(deg));
            } catch (NumberFormatException ignored) { /* fall through */ }
        }
        return Optional.ofNullable(DIRECTION_WORDS.get(t.toLowerCase(Locale.ROOT)));
    }

    private static final Map<String, Direction8> DIRECTION_WORDS = buildDirectionMap();
    private static final Map<String, UnitStatus> STATUS_WORDS = buildStatusMap();
    private static final List<String> KNOWN_WEAPONS = Arrays.asList(
            "unarmed", "sword", "bow", "axe", "spear", "staff", "dagger", "mace",
            "shield", "gun", "crossbow", "hammer", "knife", "club", "wand");

    private static Map<String, Direction8> buildDirectionMap() {
        Map<String, Direction8> m = new HashMap<>();
        put(m, Direction8.N, "n", "north", "с", "север");
        put(m, Direction8.NE, "ne", "northeast", "св", "северовосток");
        put(m, Direction8.E, "e", "east", "в", "восток");
        put(m, Direction8.SE, "se", "southeast", "юв", "юговосток");
        put(m, Direction8.S, "s", "south", "ю", "юг");
        put(m, Direction8.SW, "sw", "southwest", "юз", "югозапад");
        put(m, Direction8.W, "w", "west", "з", "запад");
        put(m, Direction8.NW, "nw", "northwest", "сз", "северозапад");
        return m;
    }

    private static void put(Map<String, Direction8> m, Direction8 d, String... keys) {
        for (String k : keys) m.put(k, d);
    }

    private static Map<String, UnitStatus> buildStatusMap() {
        Map<String, UnitStatus> m = new HashMap<>();
        put(m, UnitStatus.IDLE, "idle", "stand", "standing");
        put(m, UnitStatus.WALK, "walk", "walking");
        put(m, UnitStatus.RUN, "run", "running", "sprint");
        put(m, UnitStatus.ATTACK, "attack", "atk", "swing", "shoot");
        put(m, UnitStatus.HIT, "hit", "hurt", "flinch", "damage");
        put(m, UnitStatus.FALLING, "falling", "fall", "knockdown");
        put(m, UnitStatus.DYING, "dying", "death", "die");
        put(m, UnitStatus.LYING, "lying", "dead", "corpse");
        put(m, UnitStatus.BERSERK, "berserk", "rage", "enraged");
        return m;
    }

    private static void put(Map<String, UnitStatus> m, UnitStatus s, String... keys) {
        for (String k : keys) m.put(k, s);
    }
}
