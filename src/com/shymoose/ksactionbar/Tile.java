// SPDX-License-Identifier: Apache-2.0
package com.shymoose.ksactionbar;

import java.util.Objects;

/** What one item on the bar shows right now. Immutable, so views can skip unchanged items. */
final class Tile {
    final String label;
    final String icon;
    /** On, open or running. */
    final boolean active;
    /** An active state that deserves a warning color, such as an unlocked lock. */
    final boolean attention;
    /** A tap is waiting for Home Assistant to confirm. */
    final boolean pending;
    /** No usable state, so a tap does nothing. */
    final boolean unavailable;
    /** Spoken by TalkBack. */
    final String description;

    Tile(String label, String icon, boolean active, boolean attention, boolean pending, boolean unavailable, String description) {
        this.label = label;
        this.icon = icon;
        this.active = active;
        this.attention = attention;
        this.pending = pending;
        this.unavailable = unavailable;
        this.description = description;
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof Tile)) return false;
        Tile t = (Tile) other;
        return active == t.active && attention == t.attention && pending == t.pending && unavailable == t.unavailable
            && label.equals(t.label) && icon.equals(t.icon) && description.equals(t.description);
    }

    @Override public int hashCode() { return Objects.hash(label, icon, active, attention, pending, unavailable, description); }

    @Override public String toString() {
        return label + "[" + icon + (active ? ",active" : "") + (attention ? ",attention" : "")
            + (pending ? ",pending" : "") + (unavailable ? ",unavailable" : "") + "]";
    }
}
