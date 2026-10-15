// SPDX-License-Identifier: Apache-2.0
package com.shymoose.ksactionbar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** One row of the Items list: an entity, with an optional label and icons. */
final class ActionItem {
    private static final Pattern ENTITY_ID = Pattern.compile("^[a-z0-9_]+\\.[a-z0-9_]+$");

    final String entity;
    /** Empty to use the entity's name. */
    final String label;
    /** Empty to use Home Assistant's icon or one picked for the domain. */
    final String icon;
    /** Empty to use {@link #icon}, or the picked active icon. */
    final String iconOn;

    ActionItem(String entity, String label, String icon, String iconOn) {
        this.entity = entity;
        this.label = label;
        this.icon = icon;
        this.iconOn = iconOn;
    }

    static ActionItem fromRow(Map<?, ?> row) {
        String entity = text(row, "entity");
        if (entity.isEmpty()) throw new IllegalArgumentException("pick an entity");
        if (!ENTITY_ID.matcher(entity).matches()) throw new IllegalArgumentException("'" + entity + "' is not an entity ID");
        return new ActionItem(entity, text(row, "label"), text(row, "icon"), text(row, "iconOn"));
    }

    /** The name shown on the bar. */
    String displayLabel(EntityState state) {
        if (!label.isEmpty()) return label;
        if (state.friendlyName != null) return state.friendlyName;
        return entity.substring(entity.indexOf('.') + 1).replace('_', ' ');
    }

    /** The icon shown on the bar for the entity's current look. */
    String displayIcon(EntityState state, boolean on) {
        if (on && !iconOn.isEmpty()) return iconOn;
        if (!icon.isEmpty()) return icon;
        String fromHa = mdiIcon(state);
        if (fromHa != null) return fromHa;
        return Actions.defaultIcon(entity, state.deviceClass, on);
    }

    /**
     * Home Assistant's icon when it is a Material Design Icon. Icons from custom packs, such as
     * {@code phu:}, are not in Kiosk Satellite's set and the icon picker refuses them.
     */
    static String mdiIcon(EntityState state) {
        return state.icon != null && state.icon.startsWith("mdi:") ? state.icon : null;
    }

    /**
     * The Items rows with empty labels and icons filled in from what Home Assistant reports, so the
     * settings page shows what the bar uses. Rows whose entity has not reported a state yet keep
     * their empty fields. Null when nothing changed.
     */
    static List<Map<String, Object>> filled(Object items, Map<String, EntityState> states) {
        boolean changed = false;
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<?, ?> row : rows(items)) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : row.entrySet()) copy.put(String.valueOf(entry.getKey()), entry.getValue());
            EntityState state = states.get(text(row, "entity"));
            if (state != null && "available".equals(state.status)) {
                ActionItem item;
                try { item = fromRow(row); } catch (IllegalArgumentException invalid) { item = null; }
                if (item != null) {
                    if (item.label.isEmpty() && state.friendlyName != null) {
                        copy.put("label", state.friendlyName);
                        changed = true;
                    }
                    if (item.icon.isEmpty()) {
                        String fromHa = mdiIcon(state);
                        copy.put("icon", fromHa != null ? fromHa : Actions.defaultIcon(item.entity, state.deviceClass, false));
                        if (item.iconOn.isEmpty() && fromHa == null) {
                            copy.put("iconOn", Actions.defaultIcon(item.entity, state.deviceClass, true));
                        }
                        changed = true;
                    }
                }
            }
            result.add(copy);
        }
        return changed ? result : null;
    }

    /** The rows of a list setting. Anything that is not a list of maps reads as empty. */
    static List<Map<?, ?>> rows(Object value) {
        List<Map<?, ?>> rows = new ArrayList<>();
        if (value instanceof List) {
            for (Object row : (List<?>) value) if (row instanceof Map) rows.add((Map<?, ?>) row);
        }
        return Collections.unmodifiableList(rows);
    }

    static String text(Map<?, ?> row, String key) {
        Object value = row.get(key);
        return value instanceof String ? ((String) value).trim() : "";
    }
}
