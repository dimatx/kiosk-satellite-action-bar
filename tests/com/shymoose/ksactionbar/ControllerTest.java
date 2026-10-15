// SPDX-License-Identifier: Apache-2.0
package com.shymoose.ksactionbar;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.jxl.kiosk.plugins.OverlayFactory;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;

/** Drives the real controller with a virtual clock and a fake host, without drawing. */
public final class ControllerTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        followsEntities();
        tapsCallServices();
        menusForCoversAndLocks();
        failuresAndTimeouts();
        momentaryAndUnavailable();
        fillsLabels();
        autoHideAndPages();
        olderKioskSatellite();
        System.out.println("ControllerTest: " + checks + " checks passed");
    }

    private static void followsEntities() {
        Fixture f = new Fixture(items("light.kitchen", "cover.shade", "light.kitchen"));
        check(f.host.subscribed.equals(set("browser.state", "ha.entity.light.kitchen", "ha.entity.cover.shade")), "follows each entity once");
        check(f.host.barShows == 1, "bar shown once");
        f.plugin.configure(settings(items("cover.shade", "lock.front")));
        f.clock.flush();
        check(f.host.subscribed.equals(set("browser.state", "ha.entity.cover.shade", "ha.entity.lock.front")), "configure follows the new set only");
        check(f.host.unsubscribed.contains("ha.entity.light.kitchen"), "dropped entity unsubscribed");
        f.plugin.onEvent("ks.ha.entity.light.kitchen", state("on", "t1", null));
        f.clock.flush();
        check(f.plugin.tiles().size() == 2, "stale events from a dropped entity are ignored");
    }

    private static void tapsCallServices() {
        Fixture f = new Fixture(items("light.kitchen", "cover.shade"));
        f.plugin.onEvent("ks.ha.entity.light.kitchen", state("off", "t1", "Kitchen"));
        f.clock.flush();
        Tile before = f.plugin.tiles().get(0);
        check("Kitchen".equals(before.label) && !before.active && !before.pending, "initial tile");
        f.plugin.taps.onTap(0);
        f.clock.flush();
        check(f.host.calls.size() == 1, "tap makes one call");
        Map<String, Object> message = f.host.calls.get(0);
        check("homeassistant".equals(message.get("domain")) && "toggle".equals(message.get("service")), "light toggles");
        check("light.kitchen".equals(((Map<?, ?>) message.get("target")).get("entity_id")), "targets the entity");
        check(f.plugin.tiles().get(0).pending, "tap shows pending at once");
        f.host.replyCall(true, null);
        f.clock.flush();
        check(f.plugin.tiles().get(0).pending, "success alone keeps waiting for the state");
        f.plugin.onEvent("ks.ha.entity.light.kitchen", state("on", "t2", "Kitchen"));
        f.clock.flush();
        Tile after = f.plugin.tiles().get(0);
        check(after.active && !after.pending, "state change confirms the tap");
        check(f.host.status.isEmpty(), "no status while working");

        f.plugin.onEvent("ks.ha.entity.cover.shade", stateWith("opening", "t1", "shade", 15));
        f.clock.flush();
        f.plugin.taps.onHold(1);
        check("stop_cover".equals(f.host.calls.get(1).get("service")), "a long press stops a moving shade");
    }

    private static void menusForCoversAndLocks() throws Exception {
        Fixture f = new Fixture(items("lock.front", "cover.shade", "light.desk"));
        f.plugin.onEvent("ks.ha.entity.lock.front", state("locked", "t1", "Front door"));
        f.plugin.onEvent("ks.ha.entity.cover.shade", positioned("closed", "t1", "shade", 15, 0));
        f.plugin.onEvent("ks.ha.entity.light.desk", state("off", "t1", "Desk"));
        f.clock.flush();
        // A tap on a lock opens its menu and calls nothing.
        f.plugin.taps.onTap(0);
        f.clock.flush();
        check(f.host.menuShows == 1 && f.host.menuOpen, "a tap on a lock opens its menu");
        check(f.host.calls.isEmpty(), "opening a menu calls nothing");
        // Choosing Unlock calls it once and closes the menu.
        f.plugin.menuChoice(0, 1);
        f.clock.flush();
        check(f.host.calls.size() == 1 && "unlock".equals(f.host.calls.get(0).get("service")), "Unlock unlocks");
        check(!f.host.menuOpen, "a choice closes the menu");
        // A second tap on the same item closes its menu again.
        f.plugin.taps.onTap(1);
        f.plugin.taps.onTap(1);
        f.clock.flush();
        check(!f.host.menuOpen, "a second tap closes the menu");
        // The slider sets the position and leaves the menu open.
        f.plugin.taps.onTap(1);
        f.plugin.menuPosition(1, 40);
        f.clock.flush();
        Map<String, Object> move = f.host.calls.get(f.host.calls.size() - 1);
        check("set_cover_position".equals(move.get("service")), "the slider moves the cover");
        check(Integer.valueOf(40).equals(((Map<?, ?>) move.get("service_data")).get("position")), "to the chosen position");
        check(f.host.menuOpen, "the menu stays open after the slider");
        // A tap on another item while a menu is open acts and leaves the menu alone.
        f.plugin.taps.onTap(2);
        check("toggle".equals(f.host.calls.get(f.host.calls.size() - 1).get("service")), "a light still toggles on a tap");
        // A long press acts at once and closes any menu.
        f.plugin.taps.onHold(0);
        f.clock.flush();
        check("unlock".equals(f.host.calls.get(f.host.calls.size() - 1).get("service")), "a long press on a lock acts at once");
        check(!f.host.menuOpen, "a long press closes the menu");
        // An open menu closes after 30 seconds without a touch, and a tap outside it is bookkept.
        f.plugin.taps.onTap(1);
        f.clock.advance(ActionBarPlugin.MENU_TIMEOUT_MS - 1);
        check(f.host.menuOpen, "still open before the timeout");
        f.clock.advance(1);
        check(!f.host.menuOpen, "closed after the timeout");
        f.plugin.taps.onTap(1);
        f.host.menuOpen = false;
        f.plugin.onEvent("overlay.closed", map("key", "menu"));
        f.clock.flush();
        f.plugin.taps.onTap(1);
        check(f.host.menuOpen, "after an outside tap, the next tap opens the menu again");

        // Auto-hide waits while a menu is open, and starts over once it closes.
        Map<String, Object> s2 = settings(items("cover.shade"));
        s2.put("autoCollapse", 10.0);
        Fixture g = new Fixture(s2);
        g.plugin.onEvent("ks.ha.entity.cover.shade", positioned("closed", "t1", "shade", 15, 0));
        g.clock.flush();
        g.plugin.taps.onTap(0);
        g.clock.advance(20_000);
        check(!collapsed(g.plugin), "auto-hide waits for an open menu");
        g.plugin.menuChoice(0, 0);
        g.clock.flush();
        g.clock.advance(9_999);
        check(!collapsed(g.plugin), "a fresh countdown after the menu closes");
        g.clock.advance(1);
        check(collapsed(g.plugin), "then the bar hides");
    }

    private static void failuresAndTimeouts() {
        Fixture f = new Fixture(items("light.kitchen"));
        f.plugin.onEvent("ks.ha.entity.light.kitchen", state("off", "t1", "Kitchen"));
        f.clock.flush();
        f.plugin.taps.onTap(0);
        f.host.replyCall(false, "unreachable: Home Assistant did not answer");
        f.clock.flush();
        check(!f.plugin.tiles().get(0).pending, "failure ends pending");
        check(f.host.error && f.host.status.contains("Could not switch Kitchen"), "failure is reported");
        check(f.host.calls.size() == 1, "never retried");
        f.plugin.taps.onTap(0);
        f.host.replyCall(true, null);
        f.clock.flush();
        check(f.host.status.isEmpty() && !f.host.error, "a later success clears the error");

        // No state change: the pending look ends on its own.
        f.clock.advance(ActionBarPlugin.PENDING_MS - 1);
        check(f.plugin.tiles().get(0).pending, "still pending before the timeout");
        f.clock.advance(1);
        check(!f.plugin.tiles().get(0).pending, "pending ends after the timeout");

        // A stale timeout from an earlier tap leaves a newer tap pending.
        f.plugin.taps.onTap(0);
        f.clock.advance(5_000);
        f.plugin.onEvent("ks.ha.entity.light.kitchen", state("on", "t9", "Kitchen"));
        f.plugin.taps.onTap(0);
        f.clock.advance(5_000);
        check(f.plugin.tiles().get(0).pending, "older timeout does not clear a newer tap");
    }

    private static void momentaryAndUnavailable() {
        Fixture f = new Fixture(items("script.good_night", "light.porch"));
        f.plugin.onEvent("ks.ha.entity.script.good_night", state("off", "t1", "Good night"));
        f.plugin.onEvent("ks.ha.entity.light.porch", state("unavailable", "t1", "Porch"));
        f.clock.flush();
        f.plugin.taps.onTap(0);
        check("turn_on".equals(f.host.calls.get(0).get("service")), "script starts");
        f.host.replyCall(true, null);
        f.clock.flush();
        check(!f.plugin.tiles().get(0).pending, "a script tap ends on success");
        check(f.plugin.tiles().get(1).unavailable, "unavailable entity is marked");
        f.plugin.taps.onTap(1);
        check(!f.plugin.taps.onHold(1), "long press on an unavailable entity does nothing");
        check(f.host.calls.size() == 1, "unavailable entity never calls");

        Map<String, Object> missing = new HashMap<>();
        missing.put("entityId", "light.porch");
        missing.put("status", "missing");
        f.plugin.onEvent("ks.ha.entity.light.porch", missing);
        f.clock.flush();
        check(f.host.status.contains("light.porch was not found"), "missing entity is reported");
    }

    private static void fillsLabels() {
        Fixture f = new Fixture(items("light.kitchen"));
        f.plugin.onEvent("ks.ha.entity.light.kitchen", state("off", "t1", "Kitchen"));
        f.clock.advance(ActionBarPlugin.FILL_DELAY_MS);
        check(f.host.saved != null, "labels saved once states arrive");
        List<?> rows = (List<?>) f.host.saved.get("items");
        check("Kitchen".equals(((Map<?, ?>) rows.get(0)).get("label")), "label filled");
        check("mdi:lightbulb-outline".equals(((Map<?, ?>) rows.get(0)).get("icon")), "icon filled");
        f.host.saved = null;
        f.plugin.onEvent("ks.ha.entity.light.kitchen", state("on", "t2", "Kitchen"));
        f.clock.advance(ActionBarPlugin.FILL_DELAY_MS);
        check(f.host.saved == null, "nothing saved twice");
    }

    private static void autoHideAndPages() throws Exception {
        Map<String, Object> s = settings(items("light.kitchen"));
        s.put("autoCollapse", 10.0);
        Map<String, Object> page = new HashMap<>();
        page.put("page", "lovelace/settings");
        s.put("hideOn", Arrays.asList(page));
        Fixture f = new Fixture(s);
        f.clock.advance(9_999);
        check(!collapsed(f.plugin), "visible before the delay");
        f.clock.advance(1);
        check(collapsed(f.plugin), "hidden after the delay");
        f.plugin.onEvent("ks.browser.state", new HashMap<String, Object>());
        f.host.reply("getDashboardState", true, map("currentPath", "/lovelace/settings"));
        f.clock.flush();
        check(f.host.hides == 1, "hidden on a listed page");
        check(f.host.status.contains("Hide the bar on"), "status explains why");
        f.plugin.execute("hide", new HashMap<String, Object>());
        f.plugin.stop();
        f.clock.advance(60_000);
        check(f.clock.tasks.isEmpty(), "stop leaves no scheduled work");
    }

    private static void olderKioskSatellite() {
        Fixture f = new Fixture(items("light.kitchen"));
        f.host.noHa = true;
        f.plugin.onEvent("ks.ha.entity.light.kitchen", state("off", "t1", "Kitchen"));
        f.clock.flush();
        f.plugin.taps.onTap(0);
        f.clock.flush();
        check(f.host.status.contains("2026.10.25"), "explains the version needed");
        check(!f.plugin.tiles().get(0).pending, "no pending look without a call");
    }

    private static boolean collapsed(ActionBarPlugin plugin) throws Exception {
        Field field = ActionBarPlugin.class.getDeclaredField("collapsed");
        field.setAccessible(true);
        return (Boolean) field.get(plugin);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    private static Set<String> set(String... values) { return new LinkedHashSet<>(Arrays.asList(values)); }

    private static Map<String, Object> map(String key, Object value) {
        Map<String, Object> map = new HashMap<>();
        map.put(key, value);
        return map;
    }

    private static List<Object> items(String... entities) {
        List<Object> rows = new ArrayList<>();
        for (String entity : entities) rows.add(ActionTest.row(entity, "", "", ""));
        return rows;
    }

    private static Map<String, Object> settings(List<Object> items) {
        Map<String, Object> settings = new HashMap<>();
        settings.put("items", items);
        return settings;
    }

    private static Map<String, Object> state(String value, String updated, String name) {
        return stateWith(value, updated, null, 0, name);
    }

    private static Map<String, Object> stateWith(String value, String updated, String deviceClass, int features) {
        return stateWith(value, updated, deviceClass, features, null);
    }

    private static Map<String, Object> positioned(String value, String updated, String deviceClass, int features, int position) {
        Map<String, Object> payload = stateWith(value, updated, deviceClass, features, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> attributes = (Map<String, Object>) payload.get("attributes");
        attributes.put("current_position", position);
        return payload;
    }

    private static Map<String, Object> stateWith(String value, String updated, String deviceClass, int features, String name) {
        Map<String, Object> attributes = new HashMap<>();
        if (name != null) attributes.put("friendly_name", name);
        if (deviceClass != null) attributes.put("device_class", deviceClass);
        attributes.put("supported_features", features);
        Map<String, Object> payload = new HashMap<>();
        payload.put("status", "available");
        payload.put("state", value);
        payload.put("attributes", attributes);
        payload.put("lastUpdated", updated);
        return payload;
    }

    private static final class Fixture {
        final Clock clock = new Clock();
        final Host host = new Host();
        final ActionBarPlugin plugin = new ActionBarPlugin(clock);

        Fixture(List<Object> items) { this(settings(items)); }

        Fixture(Map<String, Object> settings) {
            plugin.start(host, settings);
            host.reply("getDashboardState", true, map("currentPath", "/lovelace/0"));
            clock.flush();
        }
    }

    private static final class Clock implements ActionBarPlugin.Scheduler {
        private static final class Task {
            final Runnable run;
            final long time;
            Task(Runnable run, long time) { this.run = run; this.time = time; }
        }
        final List<Task> tasks = new ArrayList<>();
        long now;
        public void post(Runnable run) { postDelayed(run, 0); }
        public void postDelayed(Runnable run, long delay) { tasks.add(new Task(run, now + delay)); }
        public void removeCallbacks(Runnable run) { tasks.removeIf(task -> task.run == run); }
        void flush() { advance(0); }
        void advance(long duration) {
            long end = now + duration;
            int count = 0;
            while (!tasks.isEmpty()) {
                tasks.sort(Comparator.comparingLong(task -> task.time));
                Task next = tasks.get(0);
                if (next.time > end) break;
                tasks.remove(0);
                now = next.time;
                next.run.run();
                if (++count > 10_000) throw new AssertionError("runaway scheduler");
            }
            now = end;
        }
    }

    private static final class Host implements PluginHost {
        final Set<String> subscribed = new LinkedHashSet<>();
        final List<String> unsubscribed = new ArrayList<>();
        final Map<String, List<CommandCallback>> replies = new HashMap<>();
        final List<Map<String, Object>> calls = new ArrayList<>();
        final List<CommandCallback> callReplies = new ArrayList<>();
        Map<String, Object> saved;
        int barShows;
        int hides;
        int menuShows;
        boolean menuOpen;
        boolean noHa;
        String status = "";
        boolean error;

        public void subscribe(String event) { subscribed.add(event); }
        public void unsubscribe(String event) { subscribed.remove(event); unsubscribed.add(event); }
        public void executeCommand(String name, Map<String, Object> args, CommandCallback callback) {
            replies.computeIfAbsent(name, key -> new ArrayList<>()).add(callback);
        }
        void reply(String name, boolean ok, Object value) { replies.get(name).remove(0).onResult(ok, value, ok ? null : "failed"); }
        public void haCall(Map<String, Object> message, CommandCallback callback) {
            if (noHa) throw new UnsupportedOperationException("Home Assistant access is unavailable");
            calls.add(message);
            callReplies.add(callback);
        }
        void replyCall(boolean ok, String error) { callReplies.remove(0).onResult(ok, null, error); }
        public void showOverlay(String key, OverlaySpec spec, OverlayFactory factory) {
            if ("menu".equals(key)) { menuShows++; menuOpen = true; } else barShows++;
        }
        public void hideOverlay(String key) {
            if ("menu".equals(key)) menuOpen = false; else hides++;
        }
        public void setContentInsets(int l, int t, int r, int b) {}
        public void status(String message, boolean error) { this.status = message; this.error = error; }
        public void saveSettings(Map<String, Object> values) { saved = values; }
        public void showWindow(String title, String message, String button) {}
        public void hideWindow() {}
        public void log(String message) { throw new AssertionError(message); }
    }
}
