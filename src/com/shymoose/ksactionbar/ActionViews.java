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
import android.widget.SeekBar;
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
    /** Space between items along the bar, in dp at 100%. */
    static final int ITEM_GAP_DP = 8;

    /** What the plugin hears from the bar. All calls arrive on the main thread. */
    interface Listener {
        void onTap(int index);
        /** A long press. True when it did something, which also suppresses the tap. */
        boolean onHold(int index);
        void onCollapse();
        void onExpand();
        /** Any touch, to restart the auto-hide countdown. */
        void onInteraction();
        /**
         * How late a tap reached the view, in ms. Touches pass through Flutter before reaching a
         * plugin view, so this is KS's share of any lag.
         */
        void onLatency(long downMs, long upMs);
        /** The view's depth from its edge changed, in dp, for example when the bar hides behind its tab. */
        void onDepth(int depthDp);
    }

    /** A color with its alpha scaled by `strength`, to dim through colors rather than view alpha. */
    static int fade(int color, float strength) {
        return Color.argb(Math.round(Color.alpha(color) * strength), Color.red(color), Color.green(color), Color.blue(color));
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
     * never shown. KS draws a plugin overlay into a texture that it resizes only after measuring the
     * view. Whatever the texture held is scaled to the new size until the view redraws, which shows
     * as a stretched copy of the previous state. The gate keeps the content transparent from the
     * moment its size will change until the host surface has exactly the measured size, then
     * reveals it. A timeout reveals it anyway, so a size that never arrives cannot hide the bar.
     */
    static final class SizeGate {
        private static final long TIMEOUT_MS = 900;
        /**
         * How long to stay hidden after the host reaches the right size. The host view is resized
         * before the surface behind it, so a frame drawn at once lands in the old surface and shows
         * stretched. Measured on a ThinkSmart View: drawing at the match left the stretched frame up
         * indefinitely, while redraws 150 ms later always landed.
         */
        private static final long SETTLE_MS = 150;

        private final ViewGroup owner;
        private boolean waiting;
        private int lastHostWidth = -1;
        private int lastHostHeight = -1;
        private final Runnable timeout = this::open;
        private final Runnable reveal = this::open;
        private final Runnable redraw = () -> invalidateTree(owner());
        private final View.OnLayoutChangeListener hostListener = (view, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) redrawSoon();
            check();
        };

        SizeGate(ViewGroup owner) { this.owner = owner; }

        /**
         * Whether the owner should draw nothing. Owners skip drawing rather than using alpha: on KS's
         * texture-backed overlays an alpha change alone never reached the screen, while an ordinary
         * redraw does.
         */
        boolean hiding() { return waiting; }

        private ViewGroup owner() { return owner; }

        private View host() {
            return owner.getParent() instanceof View ? (View) owner.getParent() : null;
        }

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

        /** Redraw after the surface has had time to follow a resize, twice for slow panels. */
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

        /**
         * Draw only while the host surface has exactly the owner's measured size. KS can resize the
         * host again after a match (it briefly dropped to 2 x 2 while settling), so this runs on every
         * layout of the owner or the host, hiding and revealing as needed.
         */
        void check() {
            View host = host();
            int width = owner.getMeasuredWidth();
            int height = owner.getMeasuredHeight();
            int hostWidth = host == null ? -1 : host.getWidth();
            int hostHeight = host == null ? -1 : host.getHeight();
            // KS passes sizes through Flutter's logical pixels, so allow a pixel or two of rounding.
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
                // Only a new host size hides again, so the timeout cannot start a hide and show loop.
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

        /** Marks the item whose menu is open, or none for -1. */
        void setOpenMenu(int index) { bar.setOpen(index); }

        /**
         * How far an item's center is from this view's center along the bar, in dp. The bar's overlay
         * is centered on its edge like a menu's, so this places a menu over its item.
         */
        int itemOffsetDp(int index) {
            float offset = config.vertical
                ? viewport.getTop() + bar.getTop() + bar.itemCenter(index) - viewport.getScrollY() - getHeight() / 2f
                : viewport.getLeft() + bar.getLeft() + bar.itemCenter(index) - viewport.getScrollX() - getWidth() / 2f;
            return Math.round(offset / getResources().getDisplayMetrics().density);
        }

        /** How far this view reaches from its edge, in dp. */
        int depthDp() {
            return Math.round((config.vertical ? getWidth() : getHeight()) / getResources().getDisplayMetrics().density);
        }

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

        private long downDelay = -1;

        /** Measures how late touches arrive. The event time is when the finger actually moved. */
        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            long now = android.os.SystemClock.uptimeMillis();
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                downDelay = now - event.getEventTime();
                try { listener.onInteraction(); } catch (RuntimeException ignored) {}
            } else if (action == MotionEvent.ACTION_UP && downDelay >= 0) {
                long down = downDelay;
                downDelay = -1;
                try { listener.onLatency(down, now - event.getEventTime()); } catch (RuntimeException ignored) {}
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
        private KsTheme theme;
        /** The item whose menu is open, or -1. */
        private int open = -1;

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
                // Two lines, so names like "Living room ceiling light" fit without widening the bar.
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

        void setOpen(int index) {
            if (index == open) return;
            int previous = open;
            open = index;
            if (previous >= 0 && previous < cells.size()) apply(previous);
            if (open >= 0 && open < cells.size()) apply(open);
        }

        /** The center of an item along the bar, relative to the bar. */
        float itemCenter(int index) {
            View cell = cells.get(index);
            return config.vertical ? cell.getTop() + cell.getHeight() / 2f : cell.getLeft() + cell.getWidth() / 2f;
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
            // A tap waiting for Home Assistant, or an open menu, gets a lighter pill.
            if ((tile.pending || i == open) && fill == Color.TRANSPARENT) fill = Color.argb(110, Color.red(pill), Color.green(pill), Color.blue(pill));
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
            label.setText(tile.label);
            cells.get(i).setContentDescription(tile.description);
            label.setTextColor(fade(tile.active || tile.attention ? theme.color("onSurface") : off, strength));
            cells.get(i).setSelected(tile.active);
            cells.get(i).invalidate();
        }

        void restyle(KsTheme next) {
            theme = next;
            float s = config.scale;
            setBackground(surface(theme, config.opacity));
            if (config.vertical) setPadding(px(6 * s), px(8 * s), px(6 * s), px(8 * s));
            else setPadding(px(8 * s), px(6 * s), px(8 * s), px(6 * s));
            for (int i = 0; i < cells.size(); i++) {
                LinearLayout cell = cells.get(i);
                // A gap between items, so each reads as its own button.
                LinearLayout.LayoutParams spaced = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
                if (i > 0) {
                    if (config.vertical) spaced.topMargin = px(ITEM_GAP_DP * s);
                    else spaced.leftMargin = px(ITEM_GAP_DP * s);
                }
                cell.setLayoutParams(spaced);
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
                // Every label has the same width, so tiles line up and no name or hint can make the
                // bar wider. Heights follow the names, up to two lines.
                label.setWidth(px(LABEL_DP * s));
                label.setMaxLines(2);
                LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
                spacing.topMargin = px(4 * s);
                label.setLayoutParams(spacing);
                drawnIcons.set(i, null);
                apply(i);
            }
        }

        private int px(float dp) { return theme.px(dp); }
    }

    /** What the plugin hears from an open menu. All calls arrive on the main thread. */
    interface MenuListener {
        /** A button was tapped. */
        void onChoice(int index);
        /** The slider was released at {@code percent}, 0 closed to 100 open. */
        void onPosition(int percent);
        /** Any touch, so the menu's timeout starts over. */
        void onMenuTouch();
    }

    /**
     * A cover's or lock's controls on a card, in its own overlay over the item: the name and state,
     * the buttons, and for covers that report a position, a slider from closed to open.
     *
     * Beside a rail the card is tall and narrow, like the rail: the buttons stack as a list and the
     * slider stands upright, open at the top. Above or below a bar the buttons sit in a row and the
     * slider lies flat, so the card takes little of the screen's height.
     */
    static final class MenuPanel extends FrameLayout {
        private final ActionConfig config;
        private final LinearLayout card;
        private final TextView title;
        private final TextView state;
        private final List<LinearLayout> buttons = new ArrayList<>();
        private final List<FrameLayout> pills = new ArrayList<>();
        private final List<ImageView> buttonIcons = new ArrayList<>();
        private final List<TextView> buttonLabels = new ArrayList<>();
        private final SeekBar slider;
        private final FrameLayout sliderBox;
        private final LinearLayout body;
        private final LinearLayout row;
        private final TextView closedLabel;
        private final TextView openLabel;
        private final SizeGate gate = new SizeGate(this);
        private List<Actions.Choice> choices;
        private final MenuListener listener;
        private boolean tracking;
        private boolean usable = true;
        private boolean alarming;
        private KsTheme theme;

        MenuPanel(Context context, KsTheme theme, ActionConfig config, String name, String stateText,
                  List<Actions.Choice> choices, Integer position, boolean hasPosition, MenuListener listener) {
            super(context);
            this.config = config;
            this.theme = theme;
            this.choices = choices;
            this.listener = listener;
            card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            title = new TextView(context);
            title.setText(name);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setIncludeFontPadding(false);
            state = new TextView(context);
            state.setText(stateText);
            state.setIncludeFontPadding(false);
            card.addView(title);
            card.addView(state);
            boolean upright = config.vertical;
            row = new LinearLayout(context);
            row.setOrientation(upright ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            for (int i = 0; i < choices.size(); i++) {
                final int index = i;
                LinearLayout button = new LinearLayout(context);
                // Built like the bar's own buttons: an icon pill above its name.
                button.setOrientation(LinearLayout.VERTICAL);
                button.setGravity(Gravity.CENTER);
                FrameLayout pill = new FrameLayout(context);
                pill.setBackground(new GradientDrawable());
                ImageView icon = new ImageView(context);
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                pill.addView(icon, new FrameLayout.LayoutParams(0, 0, Gravity.CENTER));
                TextView label = new TextView(context);
                label.setText(choices.get(i).label);
                label.setGravity(Gravity.CENTER);
                label.setMaxLines(2);
                label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                button.addView(pill);
                button.addView(label);
                button.setContentDescription(choices.get(i).label);
                button.setOnClickListener(view -> {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    listener.onChoice(index);
                });
                row.addView(button);
                buttons.add(button);
                pills.add(pill);
                buttonIcons.add(icon);
                buttonLabels.add(label);
            }
            // The buttons size to their content and the card grows around them, so none is squeezed.
            body = new LinearLayout(context);
            body.setOrientation(upright ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            rowParams.gravity = Gravity.CENTER_HORIZONTAL;
            body.addView(row, rowParams);
            card.addView(body, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            if (hasPosition) {
                slider = new SeekBar(context);
                slider.setMax(100);
                slider.setProgress(position == null ? 0 : position);
                slider.setContentDescription("Position");
                slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                        if (fromUser) state.setText(progress + "% open");
                    }
                    @Override public void onStartTrackingTouch(SeekBar bar) {
                        tracking = true;
                        listener.onMenuTouch();
                    }
                    @Override public void onStopTrackingTouch(SeekBar bar) {
                        tracking = false;
                        bar.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        listener.onPosition(bar.getProgress());
                    }
                });
                closedLabel = new TextView(context);
                closedLabel.setText("Closed");
                openLabel = new TextView(context);
                openLabel.setText("Open");
                if (upright) {
                    // A SeekBar turned a quarter left: 0 at the bottom, 100 at the top. The view
                    // transforms touches for its rotated child, so dragging follows the thumb.
                    slider.setRotation(-90f);
                    sliderBox = new FrameLayout(context);
                    sliderBox.addView(slider);
                    LinearLayout column = new LinearLayout(context);
                    column.setOrientation(LinearLayout.VERTICAL);
                    column.setGravity(Gravity.CENTER_HORIZONTAL);
                    openLabel.setGravity(Gravity.CENTER);
                    closedLabel.setGravity(Gravity.CENTER);
                    column.addView(openLabel);
                    column.addView(sliderBox);
                    column.addView(closedLabel);
                    // The buttons sit nearest the bar and the slider on the far side.
                    body.setGravity(Gravity.CENTER_VERTICAL);
                    body.addView(column, config.edge == ActionConfig.Edge.LEFT ? 1 : 0,
                        new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
                } else {
                    sliderBox = null;
                    body.addView(slider);
                    // Each name at its end of the slider. Natural widths, so neither wraps while the
                    // card is still finding its width.
                    FrameLayout ends = new FrameLayout(context);
                    ends.addView(closedLabel, new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.START));
                    ends.addView(openLabel, new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.END));
                    body.addView(ends, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
                }
            } else {
                slider = null;
                sliderBox = null;
                closedLabel = null;
                openLabel = null;
            }
            addView(card, new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            restyle(theme);
        }

        /** Shows a new state while the menu is open. The slider stays put while a finger is on it. */
        void update(String stateText, List<Actions.Choice> next, Integer position) {
            if (!tracking) state.setText(stateText);
            if (next.size() == choices.size()) {
                choices = next;
                applyChoices();
            }
            if (slider != null && !tracking && position != null) slider.setProgress(position);
        }

        /**
         * While the entity is unavailable the controls dim and ignore touches; a state that needs
         * attention, such as a jammed lock, shows in the error color.
         */
        void setStatus(boolean usable, boolean alarming) {
            if (usable == this.usable && alarming == this.alarming) return;
            this.usable = usable;
            this.alarming = alarming;
            applyChoices();
        }

        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                try { listener.onMenuTouch(); } catch (RuntimeException ignored) {}
            }
            return super.dispatchTouchEvent(event);
        }

        private void applyChoices() {
            int pill = color(theme, "secondaryContainer", "primaryContainer");
            int onPill = color(theme, "onSecondaryContainer", "onSurface");
            int off = theme.color("onSurfaceVariant");
            // Dimmed through colors, as on the bar: a view's alpha alone never reaches KS's screen.
            float strength = usable ? 1f : 0.38f;
            for (int i = 0; i < buttons.size(); i++) {
                Actions.Choice choice = choices.get(i);
                boolean marked = choice.current && usable;
                ((GradientDrawable) pills.get(i).getBackground()).setColor(marked ? pill : Color.TRANSPARENT);
                setIcon(buttonIcons.get(i), theme, choice.icon, fade(marked ? onPill : off, strength));
                buttonLabels.get(i).setTextColor(fade(marked ? theme.color("onSurface") : off, strength));
                buttons.get(i).setSelected(marked);
                buttons.get(i).setEnabled(usable);
                buttons.get(i).invalidate();
            }
            state.setTextColor(alarming ? color(theme, "error", "onSurfaceVariant") : off);
            if (slider != null) slider.setEnabled(usable);
        }

        void restyle(KsTheme next) {
            theme = next;
            float s = config.scale;
            card.setBackground(surface(theme, Math.max(config.opacity, 0.95f)));
            boolean upright = config.vertical;
            // A cover's card beside a rail holds a slider and the buttons side by side, so it gets
            // room to breathe. A lock's card stays compact.
            boolean roomy = upright && slider != null;
            int pad = theme.px((roomy ? 24 : 16) * s);
            card.setPadding(pad, theme.px((roomy ? 18 : 14) * s), pad, theme.px((roomy ? 18 : 12) * s));
            card.setMinimumWidth(upright ? theme.px((roomy ? 232 : 0) * s) : theme.px(240 * s));
            body.setGravity(upright ? Gravity.CENTER : Gravity.NO_GRAVITY);
            theme.styleText(title, 16 * s, 500, "onSurface");
            theme.styleText(state, 13 * s, 400, "onSurfaceVariant");
            title.setMaxWidth(theme.px(280 * s));
            LinearLayout.LayoutParams stateParams = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            stateParams.topMargin = theme.px(4 * s);
            stateParams.bottomMargin = theme.px(12 * s);
            state.setLayoutParams(stateParams);
            for (int i = 0; i < buttons.size(); i++) {
                LinearLayout button = buttons.get(i);
                LinearLayout.LayoutParams spaced = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
                if (i > 0 && upright) spaced.topMargin = theme.px(ITEM_GAP_DP * s);
                else if (i > 0) spaced.leftMargin = theme.px(ITEM_GAP_DP * s);
                button.setLayoutParams(spaced);
                button.setBackground(pressable(theme, null, radii(theme.px(theme.radiusRow()))));
                // The bar's button sizes, so a menu button looks and feels like one on the bar.
                button.setMinimumWidth(theme.px(Math.max(MIN_TOUCH_DP, 52 * s)));
                button.setMinimumHeight(theme.px(MIN_TOUCH_DP));
                button.setPadding(theme.px(5 * s), theme.px(6 * s), theme.px(5 * s), theme.px(6 * s));
                ((GradientDrawable) pills.get(i).getBackground()).setCornerRadius(theme.px(16 * s));
                pills.get(i).setLayoutParams(new LinearLayout.LayoutParams(theme.px(46 * s), theme.px(32 * s)));
                FrameLayout.LayoutParams glyph = (FrameLayout.LayoutParams) buttonIcons.get(i).getLayoutParams();
                glyph.width = theme.px(24 * s);
                glyph.height = theme.px(24 * s);
                buttonIcons.get(i).setLayoutParams(glyph);
                theme.styleText(buttonLabels.get(i), 12 * s, 500, "onSurfaceVariant");
                buttonLabels.get(i).setWidth(theme.px(LABEL_DP * s));
                LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
                labelParams.topMargin = theme.px(4 * s);
                buttonLabels.get(i).setLayoutParams(labelParams);
            }
            if (slider != null) {
                theme.styleSlider(slider);
                int thickness = theme.px(Math.max(MIN_TOUCH_DP, 40 * s));
                if (upright) {
                    // A fixed length, so the slider is long enough to set a position with a finger.
                    int length = theme.px(160 * s);
                    // Room above and below, so the thumb at either end never touches Open or Closed.
                    LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(thickness, length);
                    boxParams.topMargin = theme.px(8 * s);
                    boxParams.bottomMargin = theme.px(8 * s);
                    sliderBox.setLayoutParams(boxParams);
                    LinearLayout.LayoutParams columnParams = (LinearLayout.LayoutParams) ((View) sliderBox.getParent()).getLayoutParams();
                    if (config.edge == ActionConfig.Edge.LEFT) columnParams.leftMargin = theme.px(32 * s);
                    else columnParams.rightMargin = theme.px(32 * s);
                    ((View) sliderBox.getParent()).setLayoutParams(columnParams);
                    sliderBox.removeOnLayoutChangeListener(SLIDER_FIT);
                    sliderBox.addOnLayoutChangeListener(SLIDER_FIT);
                } else {
                    LinearLayout.LayoutParams sliderParams = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, thickness);
                    sliderParams.topMargin = theme.px(10 * s);
                    slider.setLayoutParams(sliderParams);
                }
                theme.styleText(closedLabel, 12 * s, 400, "onSurfaceVariant");
                theme.styleText(openLabel, 12 * s, 400, "onSurfaceVariant");
            }
            applyChoices();
        }

        /**
         * Sizes the turned slider to its box: its width becomes the box's height, centred, so the
         * quarter turn lays it exactly over the box.
         */
        private static final View.OnLayoutChangeListener SLIDER_FIT = (box, l, t, r, b, ol, ot, or, ob) -> {
            int w = r - l, h = b - t;
            View bar = ((ViewGroup) box).getChildAt(0);
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) bar.getLayoutParams();
            if (p.width == h && p.height == w) return;
            p.width = h;
            p.height = w;
            p.gravity = Gravity.CENTER;
            box.post(() -> bar.setLayoutParams(p));
        };

        @Override protected void dispatchDraw(android.graphics.Canvas canvas) {
            if (!gate.hiding()) super.dispatchDraw(canvas);
        }

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
        }
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
