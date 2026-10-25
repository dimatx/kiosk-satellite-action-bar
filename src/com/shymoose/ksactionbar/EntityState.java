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
    /** A select's or input_select's choices, in Home Assistant's order. Empty for anything else. */
    final java.util.List<String> options;

    /** The most options a menu lists, and the longest one shown. */
    static final int MAX_OPTIONS = 200;
    static final int MAX_OPTION_LENGTH = 120;

    static final EntityState UNKNOWN = new EntityState("connecting", null, null, null, null, 0, null, null);

    EntityState(String status, String state, String friendlyName, String icon, String deviceClass, int supportedFeatures, String lastUpdated) {
        this(status, state, friendlyName, icon, deviceClass, supportedFeatures, lastUpdated, null);
    }

    EntityState(String status, String state, String friendlyName, String icon, String deviceClass, int supportedFeatures, String lastUpdated, Integer position) {
        this(status, state, friendlyName, icon, deviceClass, supportedFeatures, lastUpdated, position, java.util.Collections.<String>emptyList());
    }

    EntityState(String status, String state, String friendlyName, String icon, String deviceClass, int supportedFeatures,
                String lastUpdated, Integer position, java.util.List<String> options) {
        this.options = options;
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
            position instanceof Number ? Integer.valueOf(Math.max(0, Math.min(100, ((Number) position).intValue()))) : null,
            options(attrs.get("options")));
    }

    /** The {@code options} attribute as text, skipping anything else and anything past the limits. */
    static java.util.List<String> options(Object value) {
        if (!(value instanceof java.util.List)) return java.util.Collections.emptyList();
        java.util.List<String> options = new java.util.ArrayList<>();
        for (Object option : (java.util.List<?>) value) {
            if (!(option instanceof String) || ((String) option).isEmpty()) continue;
            String text = (String) option;
            options.add(text.length() > MAX_OPTION_LENGTH ? text.substring(0, MAX_OPTION_LENGTH) : text);
            if (options.size() == MAX_OPTIONS) break;
        }
        return java.util.Collections.unmodifiableList(options);
    }

    /** Whether Home Assistant reports a usable state the bar can act on. */
    boolean usable() {
        return "available".equals(status) && state != null && !"unavailable".equals(state) && !"unknown".equals(state);
    }

    private static String text(Object value, String fallback) {
        return value instanceof String && !((String) value).isEmpty() ? (String) value : fallback;
    }
}
