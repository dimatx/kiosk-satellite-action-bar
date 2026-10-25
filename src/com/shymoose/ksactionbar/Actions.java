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
        /** Service data, such as {@code {position: 40}}. Empty for none. */
        final Map<String, Object> data;

        Call(String domain, String service, String verb) {
            this(domain, service, verb, Collections.<String, Object>emptyMap());
        }

        Call(String domain, String service, String verb, Map<String, Object> data) {
            this.domain = domain;
            this.service = service;
            this.verb = verb;
            this.data = data;
        }

        /** The haCall message for {@code entityId}. */
        Map<String, Object> message(String entityId) {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("type", "call_service");
            message.put("domain", domain);
            message.put("service", service);
            message.put("target", Collections.singletonMap("entity_id", entityId));
            if (!data.isEmpty()) message.put("service_data", new LinkedHashMap<>(data));
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

    /** Locks, and covers on doors, gates and garages: things people walk through. */
    static boolean guarded(String entityId, EntityState state) {
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
        return guarded(entityId, state) && active(entityId, state);
    }

    /** Home Assistant's cover feature flags. */
    static final int COVER_SUPPORTS_OPEN = 1;
    static final int COVER_SUPPORTS_CLOSE = 2;
    static final int COVER_SUPPORTS_POSITION = 4;

    /** Whether a tap opens a menu of controls instead of acting at once: covers and locks. */
    static boolean hasMenu(String entityId) {
        String domain = domain(entityId);
        return "cover".equals(domain) || "lock".equals(domain) || isSelect(domain);
    }

    /** A list of choices, one of which is picked: an input_select or a select. */
    static boolean isSelect(String domain) {
        return "input_select".equals(domain) || "select".equals(domain);
    }

    /** Whether the menu is a list of text choices, not a row of icon buttons. */
    static boolean isList(String entityId) {
        return isSelect(domain(entityId));
    }

    /** One button in an item's menu. */
    static final class Choice {
        final String label;
        final String icon;
        final Call call;
        /** The entity is already in the state this choice asks for. */
        final boolean current;

        Choice(String label, String icon, Call call, boolean current) {
            this.label = label;
            this.icon = icon;
            this.call = call;
            this.current = current;
        }
    }

    /** The buttons a cover's or lock's menu shows, in order. Empty for anything else. */
    static java.util.List<Choice> choices(String entityId, EntityState state) {
        java.util.List<Choice> choices = new java.util.ArrayList<>();
        String value = state.state == null ? "" : state.state;
        int features = state.supportedFeatures;
        String dc = state.deviceClass;
        switch (domain(entityId)) {
            case "cover":
                // A cover that reports no features at all is offered every basic control.
                boolean any = features == 0;
                // A choice is marked only when the cover is all the way there or moving that way,
                // so a shade at 40% marks neither Open nor Close.
                boolean fullyOpen = "open".equals(value) && (state.position == null || state.position >= 100);
                if (any || (features & COVER_SUPPORTS_OPEN) != 0) {
                    choices.add(new Choice("Open", defaultIcon(entityId, dc, true), new Call("cover", "open_cover", "open"),
                        fullyOpen || "opening".equals(value)));
                }
                if ((features & COVER_SUPPORTS_STOP) != 0) {
                    choices.add(new Choice("Stop", "mdi:stop", new Call("cover", "stop_cover", "stop"), false));
                }
                if (any || (features & COVER_SUPPORTS_CLOSE) != 0) {
                    choices.add(new Choice("Close", defaultIcon(entityId, dc, false), new Call("cover", "close_cover", "close"),
                        "closed".equals(value) || "closing".equals(value)));
                }
                break;
            case "lock":
                choices.add(new Choice("Lock", "mdi:lock", new Call("lock", "lock", "lock"), "locked".equals(value) || "locking".equals(value)));
                choices.add(new Choice("Unlock", "mdi:lock-open-variant", new Call("lock", "unlock", "unlock"),
                    "unlocked".equals(value) || "unlocking".equals(value)));
                break;
            case "input_select":
            case "select":
                // Each option sets the entity to it. What that starts is up to Home Assistant.
                for (String option : state.options) {
                    choices.add(new Choice(option, null, new Call(domain(entityId), "select_option", "choose " + option,
                        Collections.<String, Object>singletonMap("option", option)), option.equals(value)));
                }
                break;
            default:
                break;
        }
        return choices;
    }

    /** Whether a cover's menu has a position slider. */
    static boolean hasPosition(String entityId, EntityState state) {
        return "cover".equals(domain(entityId)) && (state.supportedFeatures & COVER_SUPPORTS_POSITION) != 0;
    }

    /** Moves a cover to {@code percent}, where 0 is closed and 100 is open. */
    static Call position(int percent) {
        int value = Math.max(0, Math.min(100, percent));
        return new Call("cover", "set_cover_position", "move", Collections.<String, Object>singletonMap("position", value));
    }

    /** Whether the state needs attention in a menu, such as a jammed lock. */
    static boolean alarming(String entityId, EntityState state) {
        return "lock".equals(domain(entityId)) && "jammed".equals(state.state);
    }

    /** A short description of the entity's state for a menu's title, such as "Open · 40%". */
    static String describe(String entityId, EntityState state) {
        if (!state.usable()) return "Unavailable";
        // A select's state is one of its own options, shown as it is written.
        if (isSelect(domain(entityId))) return state.state;
        String value = state.state.replace('_', ' ');
        String text = Character.toUpperCase(value.charAt(0)) + value.substring(1);
        if ("cover".equals(domain(entityId)) && state.position != null) text += " \u00b7 " + state.position + "%";
        return text;
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
            case "input_select":
            case "select": return "mdi:format-list-bulleted";
            case "binary_sensor": return on ? "mdi:checkbox-marked-circle" : "mdi:checkbox-blank-circle-outline";
            default: return "mdi:circle-outline";
        }
    }
}
