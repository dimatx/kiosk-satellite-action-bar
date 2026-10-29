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
        // Covers and locks open a menu; everything else acts on a tap.
        check(Actions.hasMenu("cover.shade"));
        check(Actions.hasMenu("lock.front"));
        check(!Actions.hasMenu("light.kitchen"));
        check(!Actions.hasMenu("script.x"));
        // A cover with open, close, position and stop offers all three buttons and a slider.
        List<Actions.Choice> shade = Actions.choices("cover.shade", positioned("open", "shade", 15, 40));
        eq(3, shade.size());
        eq("Open", shade.get(0).label);
        // Part way open marks neither end; all the way open, or moving, marks that end.
        check(!shade.get(0).current);
        check(Actions.choices("cover.shade", positioned("open", "shade", 15, 100)).get(0).current);
        check(Actions.choices("cover.shade", positioned("opening", "shade", 15, 40)).get(0).current);
        check(Actions.choices("cover.shade", positioned("closed", "shade", 15, 0)).get(2).current);
        check(Actions.choices("cover.shade", positioned("closing", "shade", 15, 40)).get(2).current);
        // Without a position, open means all the way open.
        check(Actions.choices("cover.basic", positioned("open", null, 0, null)).get(0).current);
        // A jammed lock needs attention; a locked one does not.
        check(Actions.alarming("lock.front", state("jammed", null, 0)));
        check(!Actions.alarming("lock.front", state("locked", null, 0)));
        check(!Actions.alarming("cover.shade", positioned("open", "shade", 15, 40)));
        eq("Stop", shade.get(1).label);
        eq("Close", shade.get(2).label);
        check(!shade.get(2).current);
        eq("cover.close_cover", shade.get(2).call.toString());
        eq("mdi:blinds-open", shade.get(0).icon);
        check(Actions.hasPosition("cover.shade", positioned("open", "shade", 15, 40)));
        eq("Open \u00b7 40%", Actions.describe("cover.shade", positioned("open", "shade", 15, 40)));
        // Without stop or position, only open and close, and no slider.
        List<Actions.Choice> garage = Actions.choices("cover.garage", positioned("closed", "garage", 3, null));
        eq(2, garage.size());
        check(garage.get(1).current);
        eq("mdi:garage-open", garage.get(0).icon);
        check(!Actions.hasPosition("cover.garage", positioned("closed", "garage", 3, null)));
        // A cover that reports no features is still offered open and close.
        eq(2, Actions.choices("cover.basic", positioned("open", null, 0, null)).size());
        // Locks offer both, with the current state marked.
        List<Actions.Choice> lock = Actions.choices("lock.front", state("unlocked", null, 0));
        eq(2, lock.size());
        eq("Lock", lock.get(0).label);
        check(!lock.get(0).current);
        check(lock.get(1).current);
        eq("lock.unlock", lock.get(1).call.toString());
        eq("Unlocked", Actions.describe("lock.front", state("unlocked", null, 0)));
        check(Actions.choices("light.kitchen", state("on", null, 0)).isEmpty());
        selects();
        // Positions are clamped and sent as service data.
        Map<String, Object> move = Actions.position(140).message("cover.shade");
        eq("set_cover_position", move.get("service"));
        eq(100, ((Map<?, ?>) move.get("service_data")).get("position"));
        eq(0, ((Map<?, ?>) Actions.position(-5).message("cover.shade").get("service_data")).get("position"));
        check(!new Actions.Call("light", "toggle", "switch").message("light.a").containsKey("service_data"));

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
        check(empty.makeRoom);
        eq(ActionConfig.Collapse.START_OPEN, empty.collapse);
        check(!empty.visibleAt("/x"));

        Map<String, Object> settings = new HashMap<>();
        settings.put("position", "Bottom");
        settings.put("labels", "Never");
        settings.put("scale", "120%");
        settings.put("opacity", 70.0);
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
        eq(0, cfg.autoCollapse);
        eq(ActionConfig.MAX_ITEMS, cfg.items.size());
        eq(1, cfg.problems.size());
        check(cfg.problems.get(0).startsWith("Broken: "));
        // The same entity twice is followed once.
        eq(ActionConfig.MAX_ITEMS - 1, cfg.entities().size());
        check(!cfg.visibleAt("/lovelace/settings"));
        check(cfg.visibleAt("/lovelace/other"));
        check(cfg.visibleAt(null));
        // A bare page, such as a built-in one, also hides the bar on its sub-pages.
        String bare = Paths.hideEntry("energy");
        check(ActionConfig.hides(bare, "/energy"));
        check(ActionConfig.hides(bare, "/energy/setup"));
        check(!ActionConfig.hides(bare, "/energy-2"));
        check(!ActionConfig.hides(Paths.hideEntry("/panel/settings"), "/panel/settings/sub"));
        check(!ActionConfig.hides(Paths.hideEntry("wall/0"), "/wall/1"));

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

    private static void selects() {
        // A select opens a list of its options. Picking one only sets the entity: what follows is Home Assistant's.
        check(Actions.hasMenu("input_select.ambient_sound"));
        check(Actions.hasMenu("select.mode"));
        check(Actions.isList("input_select.ambient_sound") && !Actions.isList("cover.shade") && !Actions.isList("lock.front"));
        EntityState sound = withOptions("Rain", Arrays.asList("Rain", "Ocean", "Forest", "Off"));
        List<Actions.Choice> picks = Actions.choices("input_select.ambient_sound", sound);
        eq(4, picks.size());
        eq("Rain", picks.get(0).label);
        check(picks.get(0).current && !picks.get(1).current);
        eq("input_select.select_option", picks.get(1).call.toString());
        Map<String, Object> message = picks.get(1).call.message("input_select.ambient_sound");
        eq("call_service", message.get("type"));
        eq("input_select", message.get("domain"));
        eq("Ocean", ((Map<?, ?>) message.get("service_data")).get("option"));
        eq("input_select.ambient_sound", ((Map<?, ?>) message.get("target")).get("entity_id"));
        eq("select.select_option", Actions.choices("select.mode", withOptions("a", Arrays.asList("a", "b"))).get(0).call.toString());
        // The state is one of the options, shown as written, not tidied like a lock's.
        eq("Rain", Actions.describe("input_select.ambient_sound", sound));
        eq("Living_room scene", Actions.describe("select.scene", withOptions("Living_room scene", Arrays.asList("Living_room scene"))));
        eq("Unavailable", Actions.describe("select.mode", new EntityState("unavailable", "unavailable", null, null, null, 0, "t")));
        eq("mdi:format-list-bulleted", Actions.defaultIcon("input_select.x", null, false));
        // A tap never acts on its own, and a select with no options has nothing to list.
        check(Actions.call("input_select.ambient_sound", sound) == null);
        check(Actions.choices("input_select.empty", withOptions("a", Arrays.<String>asList())).isEmpty());
        // Options are read from the attribute: text only, none empty, none too long, a bounded number.
        Map<String, Object> attributes = new HashMap<>();
        List<Object> raw = new ArrayList<>(Arrays.<Object>asList("One", "", 7, null, "Two"));
        raw.add(new String(new char[400]).replace('\0', 'x'));
        attributes.put("options", raw);
        Map<String, Object> payload = new HashMap<>();
        payload.put("status", "available");
        payload.put("state", "One");
        payload.put("attributes", attributes);
        EntityState parsed = EntityState.parse(payload);
        eq(Arrays.asList("One", "Two", parsed.options.get(2)), parsed.options);
        eq(EntityState.MAX_OPTION_LENGTH, parsed.options.get(2).length());
        List<Object> many = new ArrayList<>();
        for (int i = 0; i < 500; i++) many.add("o" + i);
        eq(EntityState.MAX_OPTIONS, EntityState.options(many).size());
        check(EntityState.options("not a list").isEmpty() && EntityState.options(null).isEmpty());
    }

    private static EntityState withOptions(String value, List<String> options) {
        return new EntityState("available", value, null, null, null, 0, "t", null, options);
    }

    private static EntityState positioned(String value, String deviceClass, int features, Integer position) {
        return new EntityState("available", value, null, null, deviceClass, features, "t", position);
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
