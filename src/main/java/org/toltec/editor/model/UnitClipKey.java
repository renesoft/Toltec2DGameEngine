package org.toltec.editor.model;

import org.toltec.unit.Gender;
import org.toltec.unit.UnitStatus;

/** Identifies one authored animation set for a unit: which gender, holding which weapon, doing what. */
public record UnitClipKey(Gender gender, String weapon, UnitStatus status) {
    public UnitClipKey {
        weapon = weapon == null || weapon.isBlank() ? "unarmed" : weapon.trim();
    }
}
