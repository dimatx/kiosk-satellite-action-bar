// SPDX-License-Identifier: Apache-2.0
package com.shymoose.ksactionbar;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import me.jxl.kiosk.plugins.KsTheme;

/**
 * The action bar in KS's design language: a pill of entity tiles and the tab it hides behind, in one
 * overlay. Creating a KS overlay is slow on low-power panels, so state changes, hiding and showing
 * all update existing views. Every method runs on the main thread.
 */
final class ActionViews {
    private ActionViews() {}

    static final int MIN_TOUCH_DP = 48;
    /** The widest a label gets before it wraps, in dp at 100%. */
    /** The widest a label gets before it wraps, in dp at 100%. */
    static final int LABEL_DP = 70;
    static final long HINT_MS = 1600;

    /** What the plugin hears from the bar. All calls arrive on the main thread. */
    interface Listener {
        void onTap(int index);
        /** A long press. True when it did something, which also suppresses the tap. */
        boolean onHold(int index);
        void onCollapse();
        void onExpand();
        /** Any touch, to restart the auto-hide countdown. */
        void onInteraction();
        /** The view's depth from its edge changed, in dp, for example when the bar hides behind its tab. */
        void onDepth(int depthDp);
    }

    static int color(KsTheme theme, String role, String fallback) {
        try {
            return theme.color(role);
        } catch (IllegalArgumentException missing) {
            return theme.color(fallback);
        }
    }

    static GradientDrawable surface(KsTheme theme, float opacity) {
        GradientDrawable card = theme.card();
        card.setAlpha(Math.round(255 * opacity));
        return card;
    }

    /**
     * Pressed feedback without animation: an onSurface wash that appears on touch down and goes on
     * release. A ripple's frames each cross KS's overlay texture, which arrives late on a slow panel.
     */
    static Drawable pressable(KsTheme theme, Drawable content, float[] radii) {
        int ink = theme.color("onSurface");
        GradientDrawable wash = new GradientDrawable();
        wash.setCornerRadii(radii);
        wash.setColor(Color.argb(36, Color.red(ink), Color.green(ink), Color.blue(ink)));
        StateListDrawable states = new StateListDrawable();
        states.setEnterFadeDuration(0);
        states.setExitFadeDuration(0);
        states.addState(new int[] {android.R.attr.state_pressed},
            content == null ? wash : new LayerDrawable(new Drawable[] {content, wash}));
        states.addState(new int[0], content == null ? new ColorDrawable(Color.TRANSPARENT) : content);
        return states;
    }

    static float[] radii(float r) { return new float[] {r, r, r, r, r, r, r, r}; }

    /** Corner radii for a shape against the bar's edge: the corners on the edge are square. */
    static float[] edgeRadii(ActionConfig config, float r) {
        switch (config.edge) {
            case TOP: return new float[] {0, 0, 0, 0, r, r, r, r};
            case LEFT: return new float[] {0, 0, r, r, r, r, 0, 0};
            case RIGHT: return new float[] {r, r, 0, 0, 0, 0, r, r};
            default: return new float[] {r, r, r, r, 0, 0, 0, 0};
        }
    }

    /** A chevron pointing away from the bar's edge, the way the bar opens. */
    static String chevron(ActionConfig config) {
        switch (config.edge) {
            case TOP: return "mdi:chevron-down";
            case LEFT: return "mdi:chevron-right";
            case RIGHT: return "mdi:chevron-left";
            default: return "mdi:chevron-up";
        }
    }

    /** Draws a Material Design Icon from KS's set. An unknown name draws a plain circle. */
    static void setIcon(ImageView view, KsTheme theme, String name, int color) {
        Drawable drawable = null;
        try {
            drawable = theme.icon(name, "onSurface");
            if (drawable == null) drawable = theme.icon("mdi:circle-outline", "onSurface");
        } catch (LinkageError olderHost) {
            // Kiosk Satellite before 2026.10.23 has no icons for plugins.
        }
        if (drawable != null) {
            drawable = drawable.mutate();
            drawable.setTint(color);
        }
        view.setImageDrawable(drawable);
    }

    static void invalidateTree(View view) {
        view.invalidate();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) invalidateTree(group.getChildAt(i));
        }
    }

    /**
     * Hides an overlay's content while its size is changing, so a frame drawn at the wrong size is
     * never shown. KS scales the old texture to a new size until the view redraws into it, which
     * shows as a stretched copy. Measured on a ThinkSmart View; see the navbar's SDK gap 16.
     */
    static final class SizeGate {
        private static final long TIMEOUT_MS = 900;
        private static final long SETTLE_MS = 150;

        private final ViewGroup owner;
        private boolean waiting;
        private int lastHostWidth = -1;
        private int lastHostHeight = -1;
        private final Runnable timeout = this::open;
        private final Runnable reveal = this::open;
        private final Runnable redraw;
        private final View.OnLayoutChangeListener hostListener;

        SizeGate(ViewGroup owner) {
            this.owner = owner;
            this.redraw = () -> invalidateTree(owner);
            this.hostListener = (view, l, t, r, b, ol, ot, or, ob) -> {
                if (r - l != or - ol || b - t != ob - ot) redrawSoon();
                check();
            };
        }

        boolean hiding() { return waiting; }

        private View host() { return owner.getParent() instanceof View ? (View) owner.getParent() : null; }

        void attach() {
            View host = host();
            if (host != null) host.addOnLayoutChangeListener(hostListener);
        }

        void detach() {
            View host = host();
            if (host != null) host.removeOnLayoutChangeListener(hostListener);
            owner.removeCallbacks(timeout);
            owner.removeCallbacks(reveal);
            owner.removeCallbacks(redraw);
        }

        private void redrawSoon() {
            owner.removeCallbacks(redraw);
            owner.postDelayed(redraw, 300);
            owner.postDelayed(redraw, 800);
        }

        /** The owner's size is about to change. Hide it until the host matches. */
        void close() {
            waiting = true;
            owner.invalidate();
            owner.removeCallbacks(timeout);
            owner.postDelayed(timeout, TIMEOUT_MS);
        }

        void check() {
            View host = host();
            int width = owner.getMeasuredWidth();
            int height = owner.getMeasuredHeight();
            int hostWidth = host == null ? -1 : host.getWidth();
            int hostHeight = host == null ? -1 : host.getHeight();
            boolean match = host != null && width > 1 && height > 1
                && Math.abs(hostWidth - width) <= 2 && Math.abs(hostHeight - height) <= 2;
            boolean hostChanged = hostWidth != lastHostWidth || hostHeight != lastHostHeight;
            lastHostWidth = hostWidth;
            lastHostHeight = hostHeight;
            if (match) {
                if (waiting) {
                    owner.removeCallbacks(reveal);
                    owner.postDelayed(reveal, SETTLE_MS);
                }
            } else if (waiting) {
                owner.removeCallbacks(reveal);
            } else if (hostChanged) {
                close();
            }
        }

        private void open() {
            waiting = false;
            owner.removeCallbacks(timeout);
            owner.removeCallbacks(reveal);
            invalidateTree(owner);
            redrawSoon();
        }
    }

    /** The bar and the tab it hides behind, edge last. */
    static final class Root extends LinearLayout {
        private final ActionConfig config;
        private final Listener listener;
        private final Bar bar;
        private final FrameLayout viewport;
        private final Tab tab;
        private final SizeGate gate = new SizeGate(this);
        private KsTheme theme;
        private boolean collapsed;
        private int reportedDepth = -1;
        private float startX;
        private float startY;
        private boolean swiped;

        Root(Context context, KsTheme theme, ActionConfig config, List<Tile> tiles, boolean collapsed, Listener listener) {
            super(context);
            this.config = config;
            this.listener = listener;
            this.theme = theme;
            setOrientation(config.vertical ? HORIZONTAL : VERTICAL);
            setGravity(config.vertical ? Gravity.CENTER_VERTICAL : Gravity.CENTER_HORIZONTAL);
            bar = new Bar(context, theme, config, tiles, listener);
            viewport = config.vertical ? new ScrollView(context) : new HorizontalScrollView(context);
            viewport.setClipToPadding(false);
            viewport.setVerticalScrollBarEnabled(false);
            viewport.setHorizontalScrollBarEnabled(false);
            viewport.addView(bar, new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            tab = new Tab(context, theme, config, view -> {
                listener.onInteraction();
                listener.onExpand();
            });
            // The tab goes against the edge: after the bar for right and bottom, before it for left and top.
            boolean edgeFirst = config.edge == ActionConfig.Edge.LEFT || config.edge == ActionConfig.Edge.TOP;
            if (edgeFirst) {
                addView(tab);
                addView(viewport);
            } else {
                addView(viewport);
                addView(tab);
            }
            this.collapsed = collapsed && config.collapse != ActionConfig.Collapse.OFF;
            viewport.setVisibility(this.collapsed ? GONE : VISIBLE);
            tab.setVisibility(this.collapsed ? VISIBLE : GONE);
            restyle(theme);
        }

        void setTiles(List<Tile> tiles) { bar.setTiles(tiles); }

        void hint(int index, String text) { bar.hint(index, text); }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            gate.attach();
            gate.close();
        }

        @Override protected void onDetachedFromWindow() {
            gate.detach();
            super.onDetachedFromWindow();
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            gate.check();
            float density = getResources().getDisplayMetrics().density;
            int depth = Math.round((config.vertical ? getWidth() : getHeight()) / density);
            if (depth > 1 && depth != reportedDepth) {
                reportedDepth = depth;
                try { listener.onDepth(depth); } catch (RuntimeException ignored) {}
            }
        }

        @Override protected void dispatchDraw(android.graphics.Canvas canvas) {
            if (gate.hiding()) return;
            super.dispatchDraw(canvas);
        }

        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                try { listener.onInteraction(); } catch (RuntimeException ignored) {}
            }
            return super.dispatchTouchEvent(event);
        }

        void setCollapsed(boolean value) {
            boolean next = value && config.collapse != ActionConfig.Collapse.OFF;
            if (next == collapsed) return;
            collapsed = next;
            gate.close();
            viewport.setVisibility(collapsed ? GONE : VISIBLE);
            tab.setVisibility(collapsed ? VISIBLE : GONE);
        }

        /**
         * A swipe toward the bar's edge hides it behind its tab, and a swipe away from the edge shows
         * it. The action fires as soon as the swipe is recognized, so it keeps up with the finger.
         */
        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            if (config.collapse == ActionConfig.Collapse.OFF) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = event.getX();
                    startY = event.getY();
                    swiped = false;
                    return false;
                case MotionEvent.ACTION_MOVE:
                    return !swiped && checkSwipe(event);
                default:
                    return false;
            }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (swiped) {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) swiped = false;
                return true;
            }
            if (config.collapse != ActionConfig.Collapse.OFF) {
                if (action == MotionEvent.ACTION_DOWN) {
                    startX = event.getX();
                    startY = event.getY();
                    return true;
                }
                if (action == MotionEvent.ACTION_MOVE && checkSwipe(event)) return true;
            }
            return super.onTouchEvent(event);
        }

        private boolean checkSwipe(MotionEvent event) {
            float along = config.vertical ? event.getX() - startX : event.getY() - startY;
            float across = config.vertical ? event.getY() - startY : event.getX() - startX;
            float threshold = theme.px(24 * config.scale);
            if (Math.abs(along) < threshold || Math.abs(along) < 1.5f * Math.abs(across)) return false;
            boolean towardEdge = config.edge == ActionConfig.Edge.TOP || config.edge == ActionConfig.Edge.LEFT ? along < 0 : along > 0;
            if (towardEdge == collapsed) return false;
            swiped = true;
            if (towardEdge) listener.onCollapse();
            else listener.onExpand();
            return true;
        }

        void restyle(KsTheme next) {
            theme = next;
            int edge = theme.px(ActionConfig.INSET);
            bar.restyle(theme);
            tab.restyle(theme);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            // The overlay sits flush with its edge so the tab can touch it. The bar keeps its distance itself.
            switch (config.edge) {
                case TOP: params.setMargins(edge, edge, edge, 0); break;
                case LEFT: params.setMargins(edge, edge, 0, edge); break;
                case RIGHT: params.setMargins(0, edge, edge, edge); break;
                default: params.setMargins(edge, 0, edge, edge); break;
            }
            viewport.setLayoutParams(params);
            tab.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        }
    }

    /** A pill of tiles, horizontal along the top or bottom and a rail on the sides. */
    static final class Bar extends LinearLayout {
        private final ActionConfig config;
        private final List<LinearLayout> cells = new ArrayList<>();
        private final List<GradientDrawable> indicators = new ArrayList<>();
        private final List<ImageView> icons = new ArrayList<>();
        private final List<TextView> labels = new ArrayList<>();
        private final List<String> drawnIcons = new ArrayList<>();
        private final List<Tile> tiles = new ArrayList<>();
        private final List<Runnable> hintEnds = new ArrayList<>();
        private KsTheme theme;

        Bar(Context context, KsTheme theme, ActionConfig config, List<Tile> initial, Listener listener) {
            super(context);
            this.config = config;
            this.theme = theme;
            setOrientation(config.vertical ? VERTICAL : HORIZONTAL);
            setGravity(Gravity.CENTER);
            for (int i = 0; i < initial.size(); i++) {
                final int index = i;
                LinearLayout cell = new LinearLayout(context);
                cell.setOrientation(VERTICAL);
                cell.setGravity(Gravity.CENTER);
                FrameLayout indicator = new FrameLayout(context);
                GradientDrawable shape = new GradientDrawable();
                indicator.setBackground(shape);
                ImageView icon = new ImageView(context);
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                indicator.addView(icon, new FrameLayout.LayoutParams(0, 0, Gravity.CENTER));
                TextView label = new TextView(context);
                // Two lines, so names like "His office desk light" fit without widening the bar.
                label.setSingleLine(false);
                label.setMaxLines(2);
                label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                label.setGravity(Gravity.CENTER);
                label.setIncludeFontPadding(false);
                label.setVisibility(config.labels ? VISIBLE : GONE);
                cell.addView(indicator);
                cell.addView(label);
                cell.setOnClickListener(view -> {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    listener.onTap(index);
                });
                cell.setOnLongClickListener(view -> {
                    if (!listener.onHold(index)) return false;
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    return true;
                });
                addView(cell);
                cells.add(cell);
                indicators.add(shape);
                icons.add(icon);
                labels.add(label);
                drawnIcons.add(null);
                tiles.add(null);
                hintEnds.add(null);
            }
            restyle(theme);
            setTiles(initial);
        }

        /** Redraws only the tiles that changed. */
        void setTiles(List<Tile> next) {
            for (int i = 0; i < cells.size() && i < next.size(); i++) {
                Tile tile = next.get(i);
                if (tile.equals(tiles.get(i))) continue;
                tiles.set(i, tile);
                apply(i);
            }
        }

        /** Shows {@code text} in place of an item's label for a moment, such as "Hold to unlock". */
        void hint(int index, String text) {
            if (index < 0 || index >= cells.size()) return;
            TextView label = labels.get(index);
            Runnable end = hintEnds.get(index);
            if (end != null) removeCallbacks(end);
            if (config.labels) {
                // Keep the label's size, so the hint never resizes the bar.
                label.setMinWidth(label.getWidth());
                label.setMinHeight(label.getHeight());
                // Never more lines than the name had, so the hint cannot make the bar taller.
                label.setMaxLines(Math.max(1, label.getLineCount()));
                label.setMaxWidth(Math.max(label.getWidth(), theme.px(LABEL_DP * config.scale)));
                label.setText(text);
            }
            cells.get(index).setContentDescription(text);
            Runnable restore = () -> {
                hintEnds.set(index, null);
                label.setMinWidth(0);
                label.setMinHeight(0);
                label.setMaxLines(2);
                label.setMaxWidth(theme.px(LABEL_DP * config.scale));
                Tile tile = tiles.get(index);
                if (tile != null) {
                    label.setText(tile.label);
                    cells.get(index).setContentDescription(tile.description);
                }
            };
            hintEnds.set(index, restore);
            postDelayed(restore, HINT_MS);
        }

        @Override protected void onDetachedFromWindow() {
            for (Runnable end : hintEnds) if (end != null) removeCallbacks(end);
            super.onDetachedFromWindow();
        }

        private void apply(int i) {
            Tile tile = tiles.get(i);
            if (tile == null) return;
            int pill = color(theme, "secondaryContainer", "primaryContainer");
            int onPill = color(theme, "onSecondaryContainer", "onSurface");
            int warn = color(theme, "errorContainer", "secondaryContainer");
            int onWarn = color(theme, "onErrorContainer", "onSurface");
            int off = theme.color("onSurfaceVariant");
            int fill = tile.attention ? warn : tile.active ? pill : Color.TRANSPARENT;
            if (tile.pending && !tile.active) fill = Color.argb(110, Color.red(pill), Color.green(pill), Color.blue(pill));
            indicators.get(i).setColor(fill);
            // Dimmed through colors, not View.setAlpha: on KS's texture-backed overlays an alpha
            // change alone never reached the screen (navbar SDK gap 16).
            float strength = tile.unavailable ? 0.38f : tile.pending ? 0.7f : 1f;
            int ink = fade(tile.attention ? onWarn : tile.active ? onPill : off, strength);
            ImageView icon = icons.get(i);
            if (!tile.icon.equals(drawnIcons.get(i))) {
                setIcon(icon, theme, tile.icon, ink);
                drawnIcons.set(i, tile.icon);
            } else if (icon.getDrawable() != null) {
                icon.getDrawable().setTint(ink);
                icon.invalidate();
            }
            TextView label = labels.get(i);
            if (hintEnds.get(i) == null) {
                label.setText(tile.label);
                cells.get(i).setContentDescription(tile.description);
            }
            label.setTextColor(fade(tile.active || tile.attention ? theme.color("onSurface") : off, strength));
            cells.get(i).setSelected(tile.active);
            cells.get(i).invalidate();
        }

        private static int fade(int color, float strength) {
            return Color.argb(Math.round(Color.alpha(color) * strength), Color.red(color), Color.green(color), Color.blue(color));
        }

        void restyle(KsTheme next) {
            theme = next;
            float s = config.scale;
            setBackground(surface(theme, config.opacity));
            if (config.vertical) setPadding(px(6 * s), px(8 * s), px(6 * s), px(8 * s));
            else setPadding(px(8 * s), px(6 * s), px(8 * s), px(6 * s));
            for (int i = 0; i < cells.size(); i++) {
                LinearLayout cell = cells.get(i);
                cell.setBackground(pressable(theme, null, radii(px(theme.radiusRow()))));
                cell.setMinimumWidth(px(Math.max(MIN_TOUCH_DP, 52 * s)));
                cell.setMinimumHeight(px(MIN_TOUCH_DP));
                cell.setPadding(px(5 * s), px(6 * s), px(5 * s), px(6 * s));
                indicators.get(i).setCornerRadius(px(16 * s));
                View frame = (View) icons.get(i).getParent();
                frame.setLayoutParams(new LinearLayout.LayoutParams(px(46 * s), px(32 * s)));
                FrameLayout.LayoutParams glyph = (FrameLayout.LayoutParams) icons.get(i).getLayoutParams();
                glyph.width = px(24 * s);
                glyph.height = px(24 * s);
                icons.get(i).setLayoutParams(glyph);
                TextView label = labels.get(i);
                theme.styleText(label, 12 * s, 500, "onSurfaceVariant");
                label.setMaxWidth(px(LABEL_DP * s));
                LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
                spacing.topMargin = px(4 * s);
                label.setLayoutParams(spacing);
                drawnIcons.set(i, null);
                apply(i);
            }
        }

        private int px(float dp) { return theme.px(dp); }
    }

    /** The hidden bar: a small tab against the edge with a lightning bolt and a chevron. */
    static final class Tab extends LinearLayout {
        private final ActionConfig config;
        private final ImageView badge;
        private final ImageView chevron;
        private KsTheme theme;

        Tab(Context context, KsTheme theme, ActionConfig config, View.OnClickListener open) {
            super(context);
            this.config = config;
            this.theme = theme;
            setOrientation(config.vertical ? VERTICAL : HORIZONTAL);
            setGravity(Gravity.CENTER);
            setContentDescription("Open action bar");
            badge = new ImageView(context);
            chevron = new ImageView(context);
            addView(badge);
            addView(chevron);
            setOnClickListener(view -> {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                open.onClick(view);
            });
            restyle(theme);
        }

        void restyle(KsTheme next) {
            theme = next;
            float s = config.scale;
            float[] radii = edgeRadii(config, theme.px(theme.radiusCard()));
            GradientDrawable shape = surface(theme, config.opacity);
            shape.setCornerRadii(radii);
            setBackground(pressable(theme, shape, radii));
            int length = theme.px(96 * s);
            int depth = theme.px(Math.max(MIN_TOUCH_DP, 40 * s));
            if (config.vertical) {
                setMinimumWidth(depth);
                setMinimumHeight(length);
                setPadding(theme.px(6 * s), theme.px(16 * s), theme.px(6 * s), theme.px(16 * s));
            } else {
                setMinimumWidth(length);
                setMinimumHeight(depth);
                setPadding(theme.px(16 * s), theme.px(6 * s), theme.px(16 * s), theme.px(6 * s));
            }
            setIcon(badge, theme, "mdi:lightning-bolt", theme.color("onSurface"));
            LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(theme.px(20 * s), theme.px(20 * s));
            if (config.vertical) gap.bottomMargin = theme.px(8 * s);
            else gap.rightMargin = theme.px(8 * s);
            badge.setLayoutParams(gap);
            setIcon(chevron, theme, chevron(config), theme.color("onSurfaceVariant"));
            chevron.setLayoutParams(new LinearLayout.LayoutParams(theme.px(22 * s), theme.px(22 * s)));
        }
    }
}
