// SPDX-License-Identifier: Apache-2.0
package com.shymoose.ksactionbar;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** JVM tests for everything that does not draw. Run by tools/test.py with -ea. */
public final class ActionTest {
    private static int passed;

    public static void main(String[] args) {
        calls();
        looks();
        items();
        config();
        System.out.println("ActionTest: " + passed + " checks passed");
    }

    private static void calls() {
        eq("homeassistant.toggle", call("light.kitchen", "on", null, 0));
        eq("homeassistant.toggle", call("switch.fan", "off", null, 0));
        eq("homeassistant.toggle", call("fan.office", "off", null, 0));
        eq("homeassistant.toggle", call("input_boolean.guest_mode", "on", null, 0));
        // Covers: open closes, closed opens, moving stops when it can and reverses when it cannot.
        eq("cover.close_cover", call("cover.shade", "open", "shade", 15));
        eq("cover.open_cover", call("cover.shade", "closed", "shade", 15));
        eq("cover.stop_cover", call("cover.shade", "opening", "shade", 15));
        eq("cover.close_cover", call("cover.shade", "opening", "shade", 3));
        eq("cover.open_cover", call("cover.shade", "closing", "shade", 3));
        // Locks: locked unlocks, anything else locks, and a moving lock waits.
        eq("lock.unlock", call("lock.front", "locked", null, 0));
        eq("lock.lock", call("lock.front", "unlocked", null, 0));
        eq("lock.lock", call("lock.front", "jammed", null, 0));
        eq("null", call("lock.front", "locking", null, 0));
        // Scripts start without waiting for the script to finish.
        eq("script.turn_on", call("script.good_night", "off", null, 0));
        eq("scene.turn_on", call("scene.movie", "2026-10-14T00:00:00", null, 0));
        eq("button.press", call("button.doorbell_test", "unknown", null, 0));
        eq("input_button.press", call("input_button.reset", "unknown", null, 0));
        eq("media_player.media_play_pause", call("media_player.kitchen", "playing", null, 0));
        // Sensors are shown but never act.
        eq("null", call("sensor.temperature", "21", null, 0));
        eq("null", call("binary_sensor.door", "on", null, 0));

        Map<String, Object> message = new Actions.Call("light", "turn_on", "switch").message("light.desk");
        eq("call_service", message.get("type"));
        eq("light", message.get("domain"));
        eq("turn_on", message.get("service"));
        eq("light.desk", ((Map<?, ?>) message.get("target")).get("entity_id"));
        check(!message.containsKey("id"));

        check(Actions.momentary("script.x"));
        check(Actions.momentary("scene.x"));
        check(Actions.momentary("button.x"));
        check(!Actions.momentary("light.x"));
    }

    private static void looks() {
        // A tap alone never moves locks, garage doors, gates or doors. Shades and lights move on a tap.
        check(Actions.needsHold("lock.front", state("locked", null, 0)));
        check(Actions.needsHold("cover.garage", state("closed", "garage", 0)));
        check(Actions.needsHold("cover.gate", state("closed", "gate", 0)));
        check(!Actions.needsHold("cover.shade", state("closed", "shade", 0)));
        check(!Actions.needsHold("cover.unknown_class", state("closed", null, 0)));
        check(!Actions.needsHold("light.kitchen", state("on", null, 0)));
        eq("Hold to unlock", Actions.holdHint("lock.front", state("locked", null, 0)));
        eq("Hold to lock", Actions.holdHint("lock.front", state("unlocked", null, 0)));
        eq("Hold to open", Actions.holdHint("cover.garage", state("closed", "garage", 0)));
        eq("Hold to close", Actions.holdHint("cover.garage", state("open", "garage", 0)));

        check(Actions.active("light.a", state("on", null, 0)));
        check(!Actions.active("light.a", state("off", null, 0)));
        check(Actions.active("cover.a", state("opening", null, 0)));
        check(!Actions.active("cover.a", state("closed", null, 0)));
        check(Actions.active("lock.a", state("unlocked", null, 0)));
        check(!Actions.active("lock.a", state("locked", null, 0)));
        check(Actions.active("media_player.a", state("playing", null, 0)));
        check(!Actions.active("scene.a", state("2026-10-14T00:00:00", null, 0)));
        // Warnings go to an unlocked lock and an open garage, not to a light that is on.
        check(Actions.attention("lock.a", state("unlocked", null, 0)));
        check(!Actions.attention("lock.a", state("locked", null, 0)));
        check(Actions.attention("cover.g", state("open", "garage", 0)));
        check(!Actions.attention("cover.s", state("open", "shade", 0)));
        check(!Actions.attention("light.a", state("on", null, 0)));

        eq("mdi:lightbulb", Actions.defaultIcon("light.a", null, true));
        eq("mdi:lightbulb-outline", Actions.defaultIcon("light.a", null, false));
        eq("mdi:garage-open", Actions.defaultIcon("cover.g", "garage", true));
        eq("mdi:blinds", Actions.defaultIcon("cover.s", "shade", false));
        eq("mdi:window-shutter", Actions.defaultIcon("cover.x", null, false));
        eq("mdi:lock", Actions.defaultIcon("lock.a", null, false));
        eq("mdi:power-plug", Actions.defaultIcon("switch.p", "outlet", true));
        eq("mdi:circle-outline", Actions.defaultIcon("weird.thing", null, true));
    }

    private static void items() {
        ActionItem plain = ActionItem.fromRow(row("light.kitchen_island", "", "", ""));
        EntityState named = new EntityState("available", "on", "Kitchen island", null, null, 0, "t1");
        eq("Kitchen island", plain.displayLabel(named));
        eq("kitchen island", plain.displayLabel(EntityState.UNKNOWN));
        eq("mdi:lightbulb", plain.displayIcon(named, true));
        // Home Assistant's own icon wins over the picked one, and the settings win over both.
        EntityState withIcon = new EntityState("available", "on", "Lamp", "mdi:lamp", null, 0, "t1");
        eq("mdi:lamp", plain.displayIcon(withIcon, true));
        ActionItem custom = ActionItem.fromRow(row("light.kitchen_island", "Island", "mdi:star-outline", "mdi:star"));
        eq("Island", custom.displayLabel(named));
        eq("mdi:star", custom.displayIcon(withIcon, true));
        eq("mdi:star-outline", custom.displayIcon(withIcon, false));
        ActionItem onlyIcon = ActionItem.fromRow(row("light.a", "", "mdi:star-outline", ""));
        eq("mdi:star-outline", onlyIcon.displayIcon(named, true));
        rejects(() -> ActionItem.fromRow(row("", "", "", "")));
        rejects(() -> ActionItem.fromRow(row("Light.Kitchen", "", "", "")));
        rejects(() -> ActionItem.fromRow(row("light", "", "", "")));

        // Empty labels and icons are filled from Home Assistant once the entity reports.
        List<Object> rows = new ArrayList<>(Arrays.asList(
            row("light.kitchen_island", "", "", ""),
            row("cover.garage", "", "", ""),
            row("lock.front", "Front", "mdi:door", ""),
            row("light.not_yet", "", "", "")));
        Map<String, EntityState> states = new HashMap<>();
        states.put("light.kitchen_island", named);
        states.put("cover.garage", new EntityState("available", "closed", "Garage door", null, "garage", 0, "t"));
        states.put("lock.front", new EntityState("available", "locked", "Front lock", null, null, 0, "t"));
        states.put("light.not_yet", new EntityState("connecting", null, null, null, null, 0, null));
        List<Map<String, Object>> filled = ActionItem.filled(rows, states);
        eq("Kitchen island", filled.get(0).get("label"));
        eq("mdi:lightbulb-outline", filled.get(0).get("icon"));
        eq("mdi:lightbulb", filled.get(0).get("iconOn"));
        eq("Garage door", filled.get(1).get("label"));
        eq("mdi:garage", filled.get(1).get("icon"));
        eq("mdi:garage-open", filled.get(1).get("iconOn"));
        eq("Front", filled.get(2).get("label"));
        eq("mdi:door", filled.get(2).get("icon"));
        eq("", filled.get(2).get("iconOn"));
        eq("", filled.get(3).get("label"));
        check(ActionItem.filled(filled, states) == null);
        // An icon from Home Assistant fills Icon only, so it shows in both states.
        states.put("light.kitchen_island", withIcon);
        List<Map<String, Object>> withHa = ActionItem.filled(Arrays.asList(row("light.kitchen_island", "", "", "")), states);
        eq("mdi:lamp", withHa.get(0).get("icon"));
        eq("", withHa.get(0).get("iconOn"));
        // Icons from custom packs are not in Kiosk Satellite's set, so the domain's icons are used.
        EntityState fromPackState = new EntityState("available", "off", "Ring light", "phu:elgato-ring-light", null, 0, "t");
        eq("mdi:lightbulb-outline", plain.displayIcon(fromPackState, false));
        eq("mdi:lightbulb", plain.displayIcon(fromPackState, true));
        states.put("light.kitchen_island", fromPackState);
        List<Map<String, Object>> fromPack = ActionItem.filled(Arrays.asList(row("light.kitchen_island", "", "", "")), states);
        eq("mdi:lightbulb-outline", fromPack.get(0).get("icon"));
        eq("mdi:lightbulb", fromPack.get(0).get("iconOn"));
    }

    private static void config() {
        ActionConfig empty = ActionConfig.parse(new HashMap<String, Object>());
        eq(ActionConfig.Edge.RIGHT, empty.edge);
        check(empty.vertical);
        check(empty.labels);
        check(empty.holdToConfirm);
        check(empty.makeRoom);
        eq(ActionConfig.Collapse.START_OPEN, empty.collapse);
        check(!empty.visibleAt("/x"));

        Map<String, Object> settings = new HashMap<>();
        settings.put("position", "Bottom");
        settings.put("labels", "Never");
        settings.put("scale", "120%");
        settings.put("opacity", 70.0);
        settings.put("holdToConfirm", false);
        settings.put("collapse", "Off");
        settings.put("autoCollapse", 30.0);
        settings.put("hideOn", Arrays.asList(page("lovelace/settings")));
        List<Object> rows = new ArrayList<>();
        for (int i = 0; i < 20; i++) rows.add(row("light.l" + i, "", "", ""));
        rows.add(1, row("", "Broken", "", ""));
        rows.add(2, row("light.l0", "Again", "", ""));
        settings.put("items", rows);
        ActionConfig cfg = ActionConfig.parse(settings);
        eq(ActionConfig.Edge.BOTTOM, cfg.edge);
        check(!cfg.vertical);
        check(!cfg.labels);
        eq(1.2f, cfg.scale);
        eq(0.7f, cfg.opacity);
        check(!cfg.holdToConfirm);
        eq(0, cfg.autoCollapse);
        eq(ActionConfig.MAX_ITEMS, cfg.items.size());
        eq(1, cfg.problems.size());
        check(cfg.problems.get(0).startsWith("Broken: "));
        // The same entity twice is followed once.
        eq(ActionConfig.MAX_ITEMS - 1, cfg.entities().size());
        check(!cfg.visibleAt("/lovelace/settings"));
        check(cfg.visibleAt("/lovelace/other"));
        check(cfg.visibleAt(null));

        Map<String, Object> relabeled = new HashMap<>(settings);
        List<Object> renamed = new ArrayList<>(rows);
        renamed.set(0, row("light.other", "New name", "mdi:star", ""));
        relabeled.put("items", renamed);
        check(ActionConfig.parse(relabeled).sameShape(cfg));
        relabeled.put("position", "Top");
        check(!ActionConfig.parse(relabeled).sameShape(cfg));
    }

    private static String call(String entity, String value, String deviceClass, int features) {
        Actions.Call call = Actions.call(entity, state(value, deviceClass, features));
        return String.valueOf(call);
    }

    private static EntityState state(String value, String deviceClass, int features) {
        return new EntityState("available", value, null, null, deviceClass, features, "t");
    }

    static Map<String, Object> row(String entity, String label, String icon, String iconOn) {
        Map<String, Object> row = new HashMap<>();
        row.put("entity", entity);
        row.put("label", label);
        row.put("icon", icon);
        row.put("iconOn", iconOn);
        return row;
    }

    private static Map<String, Object> page(String path) {
        Map<String, Object> row = new HashMap<>();
        row.put("page", path);
        return row;
    }

    private static void eq(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + " but got " + actual);
        passed++;
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("check failed");
        passed++;
    }

    private static void rejects(Runnable code) {
        try {
            code.run();
        } catch (IllegalArgumentException expected) {
            passed++;
            return;
        }
        throw new AssertionError("expected IllegalArgumentException");
    }
}
