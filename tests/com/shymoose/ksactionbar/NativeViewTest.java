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
        for (String role : new String[] {"surface", "surfaceContainer", "surfaceContainerHigh", "onSurface",
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
                    root.hint(5, "Hold to unlock");
                    measure(root, theme.px(480), theme.px(480));
                    check(root.getMeasuredWidth() == width && root.getMeasuredHeight() == height, "a hint never resizes the bar");
                    root.setCollapsed(true);
                    measure(root, theme.px(480), theme.px(480));
                    View tab = root.getChildAt(edge.equals("Left") || edge.equals("Top") ? 0 : 1);
                    check(tab.getMeasuredWidth() >= theme.px(48) && tab.getMeasuredHeight() >= theme.px(48), "48 dp tab");
                }
            }
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

    private static final class Listener implements ActionViews.Listener {
        public void onTap(int index) {}
        public boolean onHold(int index) { return false; }
        public void onCollapse() {}
        public void onExpand() {}
        public void onInteraction() {}
        public void onDepth(int depth) {}
    }
}
