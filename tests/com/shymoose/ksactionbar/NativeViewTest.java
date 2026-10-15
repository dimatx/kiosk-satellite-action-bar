// SPDX-License-Identifier: Apache-2.0
package com.shymoose.ksactionbar;

import android.content.Context;
import android.graphics.Path;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.jxl.kiosk.plugins.KsTheme;

/** Headless Android layout tests; run with tools/test_views.py on a connected development device. */
public final class NativeViewTest {
    private static int checks;
    private static int iconRequests;

    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            error.printStackTrace(System.err);
            System.exit(1);
        });
        Looper.prepareMainLooper();
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Object activityThread = thread.getMethod("systemMain").invoke(null);
        Context system = (Context) thread.getMethod("getSystemContext").invoke(activityThread);
        Context context = new ContextThemeWrapper(system, android.R.style.Theme_Material_Light);
        float density = context.getResources().getDisplayMetrics().density;
        Map<String, Integer> colors = new HashMap<>();
        for (String role : new String[] {"surface", "surfaceContainer", "surfaceContainerHigh", "surfaceContainerHighest", "onSurface",
                "onSurfaceVariant", "primary", "primaryContainer", "secondaryContainer", "onSecondaryContainer",
                "outline", "outlineVariant"}) colors.put(role, 0xff888888);
        KsTheme theme = new KsTheme(false, density, colors, null, name -> {
            iconRequests++;
            Path path = new Path();
            path.addRect(0, 0, 24, 24, Path.Direction.CW);
            return path;
        });
        for (String edge : new String[] {"Right", "Left", "Top", "Bottom"}) {
            for (String scale : new String[] {"80%", "100%", "140%"}) {
                for (String labels : new String[] {"Always", "Never"}) {
                    ActionConfig config = ActionConfig.parse(settings(edge, scale, labels));
                    List<Tile> tiles = tiles(16, -1);
                    ActionViews.Root root = new ActionViews.Root(context, theme, config, tiles, false, new Listener());
                    measure(root, theme.px(480), theme.px(480));
                    ViewGroup viewport = (ViewGroup) root.getChildAt(edge.equals("Left") || edge.equals("Top") ? 1 : 0);
                    ViewGroup bar = (ViewGroup) viewport.getChildAt(0);
                    check(bar.getChildCount() == 16, "sixteen tiles");
                    for (int i = 0; i < bar.getChildCount(); i++) {
                        View cell = bar.getChildAt(i);
                        check(cell.getHeight() >= theme.px(48) && cell.getWidth() >= theme.px(48), "48 dp touch target " + edge + " " + scale);
                    }
                    check(config.vertical ? viewport.getHeight() <= theme.px(480) : viewport.getWidth() <= theme.px(480),
                        "bar scrolls instead of overflowing");
                    int width = root.getMeasuredWidth(), height = root.getMeasuredHeight();
                    // One tile changing redraws one icon at most, and never resizes the bar.
                    int before = iconRequests;
                    root.setTiles(tiles(16, 3));
                    check(iconRequests - before <= 1, "one changed tile touches at most one icon");
                    before = iconRequests;
                    root.setTiles(tiles(16, 3));
                    check(iconRequests == before, "unchanged tiles draw nothing");
                    // Opening a menu marks its item without resizing the bar.
                    root.setOpenMenu(4);
                    measure(root, theme.px(480), theme.px(480));
                    check(root.getMeasuredWidth() == width && root.getMeasuredHeight() == height, "an open menu never resizes the bar");
                    root.setOpenMenu(-1);
                    root.setCollapsed(true);
                    measure(root, theme.px(480), theme.px(480));
                    View tab = root.getChildAt(edge.equals("Left") || edge.equals("Top") ? 0 : 1);
                    check(tab.getMeasuredWidth() >= theme.px(48) && tab.getMeasuredHeight() >= theme.px(48), "48 dp tab");
                }
            }
        }
        // Menus: every button and the slider are real touch targets, and the card fits a narrow screen.
        for (String edge : new String[] {"Right", "Left", "Bottom"})
        for (String scale : new String[] {"80%", "100%", "140%"}) {
            ActionConfig config = ActionConfig.parse(settings(edge, scale, "Always"));
            String at = edge + " " + scale;
            EntityState shade = new EntityState("available", "open", "Shades", null, "shade", 15, "t", 40);
            ActionViews.MenuPanel panel = new ActionViews.MenuPanel(context, theme, config, "A long example name for the shades",
                Actions.describe("cover.shade", shade), Actions.choices("cover.shade", shade), 40, true, new MenuListener());
            measure(panel, theme.px(360), theme.px(480));
            check(panel.getMeasuredWidth() <= theme.px(360), "menu fits a narrow screen at " + at);
            List<View> buttons = new ArrayList<>();
            List<View> sliders = new ArrayList<>();
            collect(panel, buttons, sliders);
            check(buttons.size() == 3, "open, stop and close at " + at);
            for (View button : buttons) {
                check(button.getWidth() >= theme.px(48) && button.getHeight() >= theme.px(48), "48 dp menu button at " + at);
            }
            check(sliders.size() == 1, "one slider at " + at);
            View slider = sliders.get(0);
            if (config.vertical) {
                // The slider is turned upright inside a box that sets its touch area.
                View box = (View) slider.getParent();
                check(box.getWidth() >= theme.px(48) && box.getHeight() >= theme.px(120), "an upright slider at " + at);
                int[] b = bounds(buttons.get(0)), s = bounds(box);
                boolean buttonsNearBar = edge.equals("Right") ? b[0] > s[0] : b[0] < s[0];
                check(buttonsNearBar, "the buttons sit nearest the bar at " + at);
                check(bounds(buttons.get(0))[1] < bounds(buttons.get(2))[1], "the buttons stack at " + at);
            } else {
                check(slider.getHeight() >= theme.px(48), "48 dp slider at " + at);
                check(bounds(buttons.get(0))[0] < bounds(buttons.get(2))[0], "the buttons sit in a row at " + at);
                check(buttons.get(2).getHeight() == buttons.get(0).getHeight(), "no squeezed button at " + at);
            }
            int width = panel.getMeasuredWidth(), height = panel.getMeasuredHeight();
            EntityState moved = new EntityState("available", "closing", "Shades", null, "shade", 15, "t2", 10);
            panel.update(Actions.describe("cover.shade", moved), Actions.choices("cover.shade", moved), 10);
            measure(panel, theme.px(360), theme.px(480));
            check(panel.getMeasuredWidth() == width && panel.getMeasuredHeight() == height, "a state change never resizes the menu");
            EntityState lockState = new EntityState("available", "locked", "Front", null, null, 0, "t", null);
            ActionViews.MenuPanel lock = new ActionViews.MenuPanel(context, theme, config, "Front door",
                Actions.describe("lock.front", lockState), Actions.choices("lock.front", lockState), null, false, new MenuListener());
            measure(lock, theme.px(360), theme.px(480));
            List<View> lockButtons = new ArrayList<>();
            List<View> lockSliders = new ArrayList<>();
            collect(lock, lockButtons, lockSliders);
            check(lockButtons.size() == 2 && lockSliders.isEmpty(), "a lock menu has two buttons and no slider at " + at);
        }
        System.out.println("NativeViewTest: " + checks + " checks passed");
        System.exit(0);
    }

    private static List<Tile> tiles(int count, int activeIndex) {
        List<Tile> tiles = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            boolean active = i == activeIndex;
            tiles.add(new Tile("Long example label " + i, active ? "mdi:lightbulb" : "mdi:lightbulb-outline",
                active, false, false, false, "Label " + i));
        }
        return tiles;
    }

    /** The menu's buttons (clickable rows of pill and name) and sliders, in layout order. */
    private static void collect(View view, List<View> buttons, List<View> sliders) {
        if (view instanceof android.widget.SeekBar) {
            sliders.add(view);
            return;
        }
        if (view.isClickable() && view instanceof ViewGroup) {
            buttons.add(view);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), buttons, sliders);
        }
    }

    /** A view's left and top within the whole tree. */
    private static int[] bounds(View view) {
        int x = 0, y = 0;
        for (View v = view; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            x += v.getLeft();
            y += v.getTop();
        }
        return new int[] {x, y};
    }

    private static void measure(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
    }

    private static Map<String, Object> settings(String edge, String scale, String labels) {
        Map<String, Object> settings = new HashMap<>();
        settings.put("position", edge);
        settings.put("scale", scale);
        settings.put("labels", labels);
        List<Object> items = new ArrayList<>();
        for (int i = 0; i < 16; i++) items.add(ActionTestRows.row("light.l" + i));
        settings.put("items", items);
        return settings;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    /** A row without depending on ActionTest, which test_views.py does not compile. */
    private static final class ActionTestRows {
        static Map<String, Object> row(String entity) {
            Map<String, Object> row = new HashMap<>();
            row.put("entity", entity);
            row.put("label", "");
            row.put("icon", "");
            row.put("iconOn", "");
            return row;
        }
    }

    private static final class MenuListener implements ActionViews.MenuListener {
        public void onChoice(int index) {}
        public void onPosition(int percent) {}
        public void onMenuTouch() {}
    }

    private static final class Listener implements ActionViews.Listener {
        public void onTap(int index) {}
        public boolean onHold(int index) { return false; }
        public void onCollapse() {}
        public void onExpand() {}
        public void onInteraction() {}
        public void onLatency(long down, long up) {}
        public void onDepth(int depth) {}
    }
}
