// SPDX-License-Identifier: Apache-2.0
package com.shymoose.ksactionbar;

import java.util.Map;

/** A snapshot of one Home Assistant entity, as Kiosk Satellite delivers it to plugins. */
final class EntityState {
    /** KS's availability status: available, unknown, unavailable, missing, connecting and so on. */
    final String status;
    /** Home Assistant's state string, or null when there is none. */
    final String state;
    final String friendlyName;
    /** The icon set in Home Assistant, or null. */
    final String icon;
    final String deviceClass;
    final int supportedFeatures;
    /** A cover's position, 0 closed to 100 open, or null when it reports none. */
    final Integer position;
    /** When Home Assistant last updated the entity, or null. Tells a fresh snapshot from a repeat. */
    final String lastUpdated;

    static final EntityState UNKNOWN = new EntityState("connecting", null, null, null, null, 0, null, null);

    EntityState(String status, String state, String friendlyName, String icon, String deviceClass, int supportedFeatures, String lastUpdated) {
        this(status, state, friendlyName, icon, deviceClass, supportedFeatures, lastUpdated, null);
    }

    EntityState(String status, String state, String friendlyName, String icon, String deviceClass, int supportedFeatures, String lastUpdated, Integer position) {
        this.status = status;
        this.state = state;
        this.friendlyName = friendlyName;
        this.icon = icon;
        this.deviceClass = deviceClass;
        this.supportedFeatures = supportedFeatures;
        this.lastUpdated = lastUpdated;
        this.position = position;
    }

    /** From a getHaEntityState result or an ha.entity event payload. */
    static EntityState parse(Map<?, ?> payload) {
        if (payload == null) return UNKNOWN;
        Object attributes = payload.get("attributes");
        Map<?, ?> attrs = attributes instanceof Map ? (Map<?, ?>) attributes : java.util.Collections.emptyMap();
        Object features = attrs.get("supported_features");
        Object position = attrs.get("current_position");
        return new EntityState(
            text(payload.get("status"), "unknown"),
            text(payload.get("state"), null),
            text(attrs.get("friendly_name"), null),
            text(attrs.get("icon"), null),
            text(attrs.get("device_class"), null),
            features instanceof Number ? ((Number) features).intValue() : 0,
            text(payload.get("lastUpdated"), null),
            position instanceof Number ? Integer.valueOf(Math.max(0, Math.min(100, ((Number) position).intValue()))) : null);
    }

    /** Whether Home Assistant reports a usable state the bar can act on. */
    boolean usable() {
        return "available".equals(status) && state != null && !"unavailable".equals(state) && !"unknown".equals(state);
    }

    private static String text(Object value, String fallback) {
        return value instanceof String && !((String) value).isEmpty() ? (String) value : fallback;
    }
}
