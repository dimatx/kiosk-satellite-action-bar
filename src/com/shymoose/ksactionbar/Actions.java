// SPDX-License-Identifier: Apache-2.0
package com.shymoose.ksactionbar;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** What an entity looks like on the bar and what a tap does to it. Pure logic, no Android. */
final class Actions {
    private Actions() {}

    /** Domains a plain toggle covers. */
    private static final Set<String> TOGGLES = new HashSet<>(Arrays.asList(
        "light", "switch", "fan", "input_boolean", "automation", "siren", "humidifier"));
    /** Cover device classes that move something people walk through, so a tap alone never moves them. */
    private static final Set<String> GUARDED_COVERS = new HashSet<>(Arrays.asList("garage", "gate", "door"));
    /** Home Assistant's cover feature flag for stop. */
    static final int COVER_SUPPORTS_STOP = 8;

    /** One Home Assistant service call. */
    static final class Call {
        final String domain;
        final String service;
        /** A short verb for messages, such as "unlock". */
        final String verb;

        Call(String domain, String service, String verb) {
            this.domain = domain;
            this.service = service;
            this.verb = verb;
        }

        /** The haCall message for {@code entityId}. */
        Map<String, Object> message(String entityId) {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("type", "call_service");
            message.put("domain", domain);
            message.put("service", service);
            message.put("target", Collections.singletonMap("entity_id", entityId));
            return message;
        }

        @Override public String toString() { return domain + "." + service; }
    }

    static String domain(String entityId) {
        int dot = entityId == null ? -1 : entityId.indexOf('.');
        return dot > 0 ? entityId.substring(0, dot) : "";
    }

    /**
     * The call a tap makes, or null when the entity cannot act in its current state, such as a lock
     * that is already moving or a sensor.
     */
    static Call call(String entityId, EntityState state) {
        String domain = domain(entityId);
        String value = state.state == null ? "" : state.state;
        if (TOGGLES.contains(domain)) return new Call("homeassistant", "toggle", "switch");
        switch (domain) {
            case "cover":
                if ("opening".equals(value) || "closing".equals(value)) {
                    if ((state.supportedFeatures & COVER_SUPPORTS_STOP) != 0) return new Call("cover", "stop_cover", "stop");
                    return "opening".equals(value) ? new Call("cover", "close_cover", "close") : new Call("cover", "open_cover", "open");
                }
                if ("open".equals(value)) return new Call("cover", "close_cover", "close");
                if ("closed".equals(value)) return new Call("cover", "open_cover", "open");
                return new Call("cover", "toggle", "move");
            case "lock":
                if ("locked".equals(value)) return new Call("lock", "unlock", "unlock");
                if ("locking".equals(value) || "unlocking".equals(value)) return null;
                return new Call("lock", "lock", "lock");
            case "script": return new Call("script", "turn_on", "run");
            case "scene": return new Call("scene", "turn_on", "activate");
            case "button": return new Call("button", "press", "press");
            case "input_button": return new Call("input_button", "press", "press");
            case "media_player": return new Call("media_player", "media_play_pause", "play or pause");
            default: return null;
        }
    }

    /** Domains whose tap never changes the entity's own on or off state, so nothing waits for one. */
    static boolean momentary(String entityId) {
        String domain = domain(entityId);
        return "scene".equals(domain) || "button".equals(domain) || "input_button".equals(domain) || "script".equals(domain);
    }

    /** Whether a tap alone must not act: locks, and covers on doors, gates and garages. */
    static boolean needsHold(String entityId, EntityState state) {
        String domain = domain(entityId);
        return "lock".equals(domain) || ("cover".equals(domain) && GUARDED_COVERS.contains(state.deviceClass));
    }

    /** Whether the entity reads as on, open or running, which highlights its item. */
    static boolean active(String entityId, EntityState state) {
        String value = state.state == null ? "" : state.state;
        switch (domain(entityId)) {
            case "cover":
            case "valve": return "open".equals(value) || "opening".equals(value) || "closing".equals(value);
            case "lock": return !"locked".equals(value) && !value.isEmpty();
            case "media_player": return "playing".equals(value);
            case "scene":
            case "button":
            case "input_button": return false;
            default: return "on".equals(value);
        }
    }

    /** Whether an active state needs attention: an unlocked lock, or an open door, gate or garage. */
    static boolean attention(String entityId, EntityState state) {
        return needsHold(entityId, state) && active(entityId, state);
    }

    /** The hint shown when a tap is not enough, such as "Hold to unlock". */
    static String holdHint(String entityId, EntityState state) {
        Call call = call(entityId, state);
        return call == null ? "Hold" : "Hold to " + call.verb;
    }

    /** The default icon for an entity, in its active or inactive look. */
    static String defaultIcon(String entityId, String deviceClass, boolean on) {
        String dc = deviceClass == null ? "" : deviceClass;
        switch (domain(entityId)) {
            case "light": return on ? "mdi:lightbulb" : "mdi:lightbulb-outline";
            case "switch": return "outlet".equals(dc)
                ? (on ? "mdi:power-plug" : "mdi:power-plug-off")
                : (on ? "mdi:toggle-switch" : "mdi:toggle-switch-off-outline");
            case "input_boolean": return on ? "mdi:toggle-switch" : "mdi:toggle-switch-off-outline";
            case "fan": return on ? "mdi:fan" : "mdi:fan-off";
            case "automation": return on ? "mdi:robot" : "mdi:robot-off";
            case "siren": return on ? "mdi:bullhorn" : "mdi:bullhorn-outline";
            case "humidifier": return on ? "mdi:air-humidifier" : "mdi:air-humidifier-off";
            case "lock": return on ? "mdi:lock-open-variant" : "mdi:lock";
            case "cover":
                switch (dc) {
                    case "garage": return on ? "mdi:garage-open" : "mdi:garage";
                    case "gate": return on ? "mdi:gate-open" : "mdi:gate";
                    case "door": return on ? "mdi:door-open" : "mdi:door-closed";
                    case "blind":
                    case "shade":
                    case "curtain": return on ? "mdi:blinds-open" : "mdi:blinds";
                    case "awning": return on ? "mdi:awning-outline" : "mdi:awning";
                    default: return on ? "mdi:window-shutter-open" : "mdi:window-shutter";
                }
            case "script": return on ? "mdi:script-text-play" : "mdi:script-text-outline";
            case "scene": return "mdi:palette";
            case "button":
            case "input_button": return "mdi:gesture-tap-button";
            case "media_player": return on ? "mdi:pause-circle" : "mdi:play-circle-outline";
            case "binary_sensor": return on ? "mdi:checkbox-marked-circle" : "mdi:checkbox-blank-circle-outline";
            default: return "mdi:circle-outline";
        }
    }
}
