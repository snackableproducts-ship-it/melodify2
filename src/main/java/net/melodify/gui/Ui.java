package net.melodify.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/** Tiny custom widget kit for the ClickGUI. All coordinates are panel-local; the screen handles scaling. */
final class Ui {
    private Ui() {}

    static final int PANEL = 0xF0121419;
    static final int PANEL_ALT = 0xFF1B1E27;
    static final int RAIL = 0xF00C0E13;
    static final int BORDER = 0xFF2A2F3C;
    static final int TRACK = 0xFF2B2F3B;
    static final int TEXT = 0xFFE8EAF0;
    static final int MUTED = 0xFF8A90A0;
    static final int ACCENT = 0xFF6C7BFF;
    static final int GREEN = 0xFF1ED760;
    static final int RED = 0xFFFF5C6C;
    static final int AMBER = 0xFFFFC857;

    /** Filled rectangle with one-pixel-cut corners. */
    static void rect(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x + 1, y, x + w - 1, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    static int lerp(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int ar = a >>> 24, rr = (a >> 16) & 255, gr = (a >> 8) & 255, br = a & 255;
        int a2 = b >>> 24, r2 = (b >> 16) & 255, g2 = (b >> 8) & 255, b2 = b & 255;
        return (Math.round(ar + (a2 - ar) * t) << 24) | (Math.round(rr + (r2 - rr) * t) << 16)
                | (Math.round(gr + (g2 - gr) * t) << 8) | Math.round(br + (b2 - br) * t);
    }

    static String fit(Font f, String s, int maxPx) {
        if (f.width(s) <= maxPx) return s;
        return f.plainSubstrByWidth(s, Math.max(0, maxPx - f.width("..."))) + "...";
    }

    static void text(GuiGraphics g, Font f, String s, int x, int y, int color) {
        g.drawString(f, s, x, y, color, false);
    }

    static void textRight(GuiGraphics g, Font f, String s, int right, int y, int color) {
        g.drawString(f, s, right - f.width(s), y, color, false);
    }

    // ------------------------------------------------------------------ widgets

    abstract static class Widget {
        int x, y, w, h;

        Widget(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        abstract void render(GuiGraphics g, Font f, int mx, int my);

        boolean click(int mx, int my) { return false; }

        boolean draggable() { return false; }

        void drag(int mx, int my) {}

        void release() {}

        boolean hit(int mx, int my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }

        boolean hover(int mx, int my) { return hit(mx, my); }
    }

    /** Free-form drawing, optionally clickable. */
    interface Painter {
        void paint(GuiGraphics g, Font f, int x, int y, int w, int h, int mx, int my);
    }

    static final class Custom extends Widget {
        private final Painter painter;

        Custom(int x, int y, int w, int h, Painter painter) {
            super(x, y, w, h);
            this.painter = painter;
        }

        @Override
        void render(GuiGraphics g, Font f, int mx, int my) {
            painter.paint(g, f, x, y, w, h, mx, my);
        }
    }

    static final class Toggle extends Widget {
        private final String label;
        private final BooleanSupplier get;
        private final Consumer<Boolean> set;
        private float knob = -1f;

        Toggle(int x, int y, int w, String label, BooleanSupplier get, Consumer<Boolean> set) {
            super(x, y, w, 19);
            this.label = label;
            this.get = get;
            this.set = set;
        }

        @Override
        void render(GuiGraphics g, Font f, int mx, int my) {
            boolean on = get.getAsBoolean();
            if (knob < 0f) knob = on ? 1f : 0f;
            knob += ((on ? 1f : 0f) - knob) * 0.35f;
            if (hover(mx, my)) rect(g, x, y, w, h, 0x16FFFFFF);
            text(g, f, label, x + 6, y + (h - 8) / 2, TEXT);
            int sx = x + w - 34, sy = y + (h - 12) / 2;
            rect(g, sx, sy, 28, 12, lerp(TRACK, ACCENT, knob));
            rect(g, sx + 2 + Math.round(knob * 16), sy + 2, 8, 8, 0xFFFFFFFF);
        }

        @Override
        boolean click(int mx, int my) {
            set.accept(!get.getAsBoolean());
            return true;
        }
    }

    static final class Slider extends Widget {
        private final String label;
        private final double min, max, step;
        private final DoubleSupplier get;
        private final DoubleConsumer set;
        private final DoubleFunction<String> fmt;
        private boolean active;

        Slider(int x, int y, int w, String label, double min, double max, double step,
               DoubleSupplier get, DoubleConsumer set, DoubleFunction<String> fmt) {
            super(x, y, w, 19);
            this.label = label;
            this.min = min;
            this.max = max;
            this.step = step;
            this.get = get;
            this.set = set;
            this.fmt = fmt;
        }

        private int x0() { return x + 118; }

        private int x1() { return x + w - 56; }

        @Override
        void render(GuiGraphics g, Font f, int mx, int my) {
            if (hover(mx, my) || active) rect(g, x, y, w, h, 0x16FFFFFF);
            text(g, f, label, x + 6, y + (h - 8) / 2, TEXT);
            double v = get.getAsDouble();
            float t = (float) ((v - min) / (max - min));
            t = Math.max(0f, Math.min(1f, t));
            int ty = y + h / 2 - 1;
            g.fill(x0(), ty, x1(), ty + 3, TRACK);
            int kx = x0() + Math.round(t * (x1() - x0()));
            g.fill(x0(), ty, kx, ty + 3, ACCENT);
            rect(g, kx - 3, y + h / 2 - 4, 7, 8, active ? 0xFFFFFFFF : 0xFFD7DBFF);
            textRight(g, f, fmt.apply(v), x + w - 4, y + (h - 8) / 2, MUTED);
        }

        @Override
        boolean click(int mx, int my) {
            if (mx < x0() - 8) return false;
            active = true;
            update(mx);
            return true;
        }

        @Override
        boolean draggable() { return true; }

        @Override
        void drag(int mx, int my) { update(mx); }

        @Override
        void release() { active = false; }

        private void update(int mx) {
            double t = (mx - x0()) / (double) (x1() - x0());
            t = Math.max(0.0, Math.min(1.0, t));
            double v = min + t * (max - min);
            if (step > 0) v = Math.round(v / step) * step;
            set.accept(Math.max(min, Math.min(max, v)));
        }
    }

    static final class Button extends Widget {
        private final Supplier<String> label;
        private final Runnable action;
        private final boolean accent;
        private final BooleanSupplier enabled;

        Button(int x, int y, int w, int h, Supplier<String> label, boolean accent, BooleanSupplier enabled, Runnable action) {
            super(x, y, w, h);
            this.label = label;
            this.accent = accent;
            this.enabled = enabled;
            this.action = action;
        }

        @Override
        void render(GuiGraphics g, Font f, int mx, int my) {
            boolean on = enabled.getAsBoolean();
            int base = accent ? ACCENT : PANEL_ALT;
            int hov = accent ? 0xFF8793FF : 0xFF2A2F3C;
            int bg = !on ? 0xFF1A1D25 : (hover(mx, my) ? hov : base);
            rect(g, x, y, w, h, bg);
            String s = fit(f, label.get(), w - 8);
            int tx = x + (w - f.width(s)) / 2;
            g.drawString(f, s, tx, y + (h - 8) / 2, on ? TEXT : 0xFF5A6070, false);
        }

        @Override
        boolean click(int mx, int my) {
            if (enabled.getAsBoolean()) action.run();
            return true;
        }
    }

    /** Label on the left, a pill with the current value on the right; click cycles the value. */
    static final class Cycle extends Widget {
        private final String label;
        private final Supplier<String> value;
        private final Runnable next;

        Cycle(int x, int y, int w, String label, Supplier<String> value, Runnable next) {
            super(x, y, w, 19);
            this.label = label;
            this.value = value;
            this.next = next;
        }

        @Override
        void render(GuiGraphics g, Font f, int mx, int my) {
            if (hover(mx, my)) rect(g, x, y, w, h, 0x16FFFFFF);
            text(g, f, label, x + 6, y + (h - 8) / 2, TEXT);
            String v = value.get();
            int pw = Math.max(60, f.width(v) + 16);
            rect(g, x + w - pw - 4, y + 2, pw, h - 4, hover(mx, my) ? 0xFF2A2F3C : PANEL_ALT);
            text(g, f, v, x + w - pw - 4 + (pw - f.width(v)) / 2, y + (h - 8) / 2, TEXT);
        }

        @Override
        boolean click(int mx, int my) {
            next.run();
            return true;
        }
    }
}
