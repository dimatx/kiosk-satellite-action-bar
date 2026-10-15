// SPDX-License-Identifier: Apache-2.0
package com.shymoose.ksactionbar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The validated configuration, built from the plugin's KS settings. */
final class ActionConfig {
    /** Matches Kiosk Satellite's limit of 16 live entities per plugin. */
    static final int MAX_ITEMS = 16;
    static final int INSET = 12;

    enum Edge { RIGHT, LEFT, TOP, BOTTOM }

    /** Whether the bar can shrink to a tab at its edge, and how it starts. */
    enum Collapse { OFF, START_OPEN, START_COLLAPSED }

    final Edge edge;
    /** A rail against the left or right edge. */
    final boolean vertical;
    final boolean labels;
    final float scale;
    final float opacity;
    final Collapse collapse;
    /** Seconds without a tap before an open bar hides. 0 never does. */
    final int autoCollapse;
    final boolean makeRoom;
    final boolean holdToConfirm;
    final List<String> hideOn;
    final List<ActionItem> items;
    /** One line per row that could not be used. */
    final List<String> problems;

    private ActionConfig(Edge edge, boolean labels, float scale, float opacity, Collapse collapse, int autoCollapse,
                         boolean makeRoom, boolean holdToConfirm, List<String> hideOn, List<ActionItem> items, List<String> problems) {
        this.edge = edge;
        this.vertical = edge == Edge.LEFT || edge == Edge.RIGHT;
        this.labels = labels;
        this.scale = scale;
        this.opacity = opacity;
        this.collapse = collapse;
        this.autoCollapse = autoCollapse;
        this.makeRoom = makeRoom;
        this.holdToConfirm = holdToConfirm;
        this.hideOn = Collections.unmodifiableList(hideOn);
        this.items = Collections.unmodifiableList(items);
        this.problems = Collections.unmodifiableList(problems);
    }

    static ActionConfig parse(Map<String, Object> settings) {
        String position = text(settings, "position");
        Edge edge = "Left".equals(position) ? Edge.LEFT : "Top".equals(position) ? Edge.TOP
            : "Bottom".equals(position) ? Edge.BOTTOM : Edge.RIGHT;
        boolean labels = !"Never".equals(text(settings, "labels"));
        float scale = 1f;
        String scaleText = text(settings, "scale").replace("%", "").trim();
        if (!scaleText.isEmpty()) {
            try { scale = Math.max(50, Math.min(200, Integer.parseInt(scaleText))) / 100f; } catch (NumberFormatException ignored) {}
        }
        float opacity = number(settings, "opacity", 20, 100, 100) / 100f;
        String collapseSetting = text(settings, "collapse");
        Collapse collapse = "Off".equals(collapseSetting) ? Collapse.OFF
            : "Start hidden".equals(collapseSetting) ? Collapse.START_COLLAPSED : Collapse.START_OPEN;
        int autoCollapse = collapse == Collapse.OFF ? 0 : number(settings, "autoCollapse", 0, 3600, 0);
        List<String> hideOn = new ArrayList<>();
        for (Map<?, ?> row : ActionItem.rows(settings.get("hideOn"))) {
            String page = ActionItem.text(row, "page");
            if (!page.isEmpty()) hideOn.add(Paths.normalize(page));
        }
        List<ActionItem> items = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        List<Map<?, ?>> rows = ActionItem.rows(settings.get("items"));
        for (int i = 0; i < rows.size() && items.size() < MAX_ITEMS; i++) {
            String label = ActionItem.text(rows.get(i), "label");
            String name = label.isEmpty() ? "Item " + (i + 1) : label;
            try {
                items.add(ActionItem.fromRow(rows.get(i)));
            } catch (IllegalArgumentException error) {
                problems.add(name + ": " + error.getMessage());
            }
        }
        boolean makeRoom = !Boolean.FALSE.equals(settings.get("makeRoom"));
        boolean holdToConfirm = !Boolean.FALSE.equals(settings.get("holdToConfirm"));
        return new ActionConfig(edge, labels, scale, opacity, collapse, autoCollapse, makeRoom, holdToConfirm, hideOn, items, problems);
    }

    /** Whether the bar belongs on {@code path}. It shows everywhere except the Hide the bar on pages. */
    boolean visibleAt(String path) {
        if (items.isEmpty()) return false;
        if (path == null) return true;
        String here = Paths.normalize(path);
        for (String page : hideOn) if (page.equals(here)) return false;
        return true;
    }

    /** The distinct entities the bar follows, in order. */
    Set<String> entities() {
        Set<String> entities = new LinkedHashSet<>();
        for (ActionItem item : items) entities.add(item.entity);
        return entities;
    }

    /**
     * Whether both configurations build the same views. Labels, icons and states are drawn into the
     * existing views, so only the shape of the bar counts here.
     */
    boolean sameShape(ActionConfig other) {
        return other != null && edge == other.edge && labels == other.labels && scale == other.scale
            && opacity == other.opacity && collapse == other.collapse && items.size() == other.items.size();
    }

    private static int number(Map<String, Object> settings, String key, int min, int max, int fallback) {
        Object value = settings.get(key);
        return value instanceof Number ? Math.max(min, Math.min(max, ((Number) value).intValue())) : fallback;
    }

    private static String text(Map<String, Object> settings, String key) {
        Object value = settings.get(key);
        return value instanceof String ? ((String) value).trim() : "";
    }
}
