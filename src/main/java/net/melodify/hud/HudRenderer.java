package net.melodify.hud;

import net.melodify.MelodifyCore;
import net.melodify.config.MelodifyConfig;
import net.melodify.config.MelodifyConfig.Alignment;
import net.melodify.lyrics.LyricLine;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.joml.Matrix3x2fStack;

/**
 * Draws the lyrics HUD. Minecraft-version specific code lives here (GuiGraphics API); all the logic
 * about *what* to show is in MelodifyCore.hudData().
 *
 * Layout is done in "HUD units" and scaled by the user's text-size setting. Lyric lines are positioned
 * by a continuous "slot" value (0 = current, -1 = previous, +1 = next); while the song moves from one
 * line to the next the slot offsets glide, which gives the slide + cross-fade between lines.
 */
public final class HudRenderer {
    private static final int PAD = 8;
    private static final int LINE_H = 16;
    private static final int INFO_H = 11;
    private static final float TRANSITION_SECONDS = 0.32f;

    private float hudAlpha;
    private long lastNanos = System.nanoTime();
    private Object lastLyrics;
    private int lastIdx = Integer.MIN_VALUE;
    private float transition = 1f;
    private HudData lastData;

    /** Called every frame. {@code data == null} means "nothing to show" and fades the HUD out. */
    public void render(GuiGraphics g, HudData data) {
        MelodifyConfig cfg = MelodifyCore.config();
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - lastNanos) / 1_000_000_000f);
        lastNanos = now;

        float target = data == null ? 0f : (data.playing() || data.test() ? 1f : 0.55f);
        if (data != null) lastData = data;
        if (cfg.animatedTransitions) {
            float step = dt * 4f * cfg.animationSpeed;
            hudAlpha += Math.max(-step, Math.min(step, target - hudAlpha));
        } else {
            hudAlpha = target;
        }
        if (hudAlpha <= 0.01f) {
            if (target == 0f) {
                lastData = null;
                lastLyrics = null;
            }
            return;
        }
        HudData d = data != null ? data : lastData; // keep drawing the last frame while fading out
        if (d != null) draw(g, d, dt, cfg);
    }

    /** Screen rectangle {x, y, width, height} of the HUD in GUI-scaled pixels. */
    public static float[] bounds(int screenW, int screenH) {
        MelodifyConfig cfg = MelodifyCore.config();
        float w = cfg.hudWidth * cfg.textScale;
        float h = heightUnits(cfg) * cfg.textScale;
        float x = cfg.hudX * Math.max(0f, screenW - w);
        float y = cfg.hudY * Math.max(0f, screenH - h);
        return new float[]{x, y, w, h};
    }

    private static int heightUnits(MelodifyConfig cfg) {
        int rows = 1 + (cfg.showPrevious ? 1 : 0) + (cfg.showNext ? 1 : 0);
        return PAD * 2 + (cfg.showSongInfo ? INFO_H * 2 + 5 : 0) + rows * LINE_H;
    }

    // ------------------------------------------------------------------ drawing

    private void draw(GuiGraphics g, HudData d, float dt, MelodifyConfig cfg) {
        Font font = Minecraft.getInstance().font;
        float[] box = bounds(g.guiWidth(), g.guiHeight());
        float scale = cfg.textScale;
        int w = cfg.hudWidth;
        int h = heightUnits(cfg);
        float maxW = w - PAD * 2f;
        Alignment al = cfg.alignment;
        float anchorX = switch (al) {
            case LEFT -> PAD;
            case CENTER -> w / 2f;
            case RIGHT -> w - PAD;
        };

        // Which lyric line are we on, and are we mid-transition?
        boolean hasLines = d.message() == null && d.lyrics().hasLines();
        int idx = hasLines ? d.lyrics().indexAt(d.positionMs() + cfg.lyricsOffsetMs) : -1;
        if (d.lyrics() != lastLyrics) {
            lastLyrics = d.lyrics();
            lastIdx = idx;
            transition = 1f;
        } else if (idx != lastIdx) {
            transition = (cfg.animatedTransitions && Math.abs(idx - lastIdx) == 1) ? 0f : 1f;
            lastIdx = idx;
        }
        if (transition < 1f) transition = Math.min(1f, transition + dt * cfg.animationSpeed / TRANSITION_SECONDS);
        float inv = 1f - transition;
        float eased = 1f - inv * inv * inv;

        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate(box[0], box[1]);
        pose.scale(scale, scale);

        if (cfg.showBackground) {
            int a = Math.round(cfg.backgroundOpacity * hudAlpha * 255f);
            if (a > 3) roundRect(g, 0, 0, w, h, (a << 24) | 0x0E1016);
        }
        boolean shadow = !cfg.showBackground || cfg.backgroundOpacity < 0.3f;
        int rgb = cfg.textColor;

        float y = PAD;
        if (cfg.showSongInfo) {
            drawText(g, font, d.title(), anchorX, y + INFO_H / 2f, 1f, rgb, 1f * hudAlpha, true, shadow, al, maxW);
            drawText(g, font, d.artist(), anchorX, y + INFO_H + INFO_H / 2f, 1f, rgb, 0.65f * hudAlpha, false, shadow, al, maxW);
            y += INFO_H * 2 + 5;
        }
        float lyricsTop = y;
        int prevRows = cfg.showPrevious ? 1 : 0;

        if (d.message() != null) {
            float cy = lyricsTop + (prevRows + 0.5f) * LINE_H;
            drawText(g, font, d.message(), anchorX, cy, 1.1f, rgb, hudAlpha, false, shadow, al, maxW);
        } else if (hasLines) {
            var lines = d.lyrics().lines();
            for (int i = idx - 2; i <= idx + 2; i++) {
                if (i < 0 || i >= lines.size()) continue;
                LyricLine line = lines.get(i);
                float slot = (i - idx) + (1f - eased);
                float alpha = Math.max(0f, 1f - 0.55f * Math.abs(slot));
                if (!cfg.showPrevious && slot < 0f) alpha *= Math.max(0f, 1f + slot);
                if (!cfg.showNext && slot > 0f) alpha *= Math.max(0f, 1f - slot);
                alpha *= hudAlpha;
                if (alpha <= 0.02f) continue;
                float sc = 1f + 0.2f * Math.max(0f, 1f - Math.abs(slot));
                float cy = lyricsTop + (prevRows + slot + 0.5f) * LINE_H;
                drawText(g, font, line.text(), anchorX, cy, sc, rgb, alpha, false, shadow, al, maxW);
            }
        }
        pose.popMatrix();
    }

    private static void drawText(GuiGraphics g, Font font, String text, float anchorX, float centerY, float scale,
                                 int rgb, float alpha, boolean bold, boolean shadow, Alignment al, float maxW) {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
        if (a < 4 || text == null || text.isEmpty()) return;
        String t = fit(font, text, (int) (maxW / scale) - (bold ? 4 : 0));
        Component c = bold ? Component.literal(t).withStyle(ChatFormatting.BOLD) : Component.literal(t);
        int tw = font.width(c);
        int lx = switch (al) {
            case LEFT -> 0;
            case CENTER -> -tw / 2;
            case RIGHT -> -tw;
        };
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate(anchorX, centerY);
        pose.scale(scale, scale);
        g.drawString(font, c, lx, -4, (a << 24) | (rgb & 0xFFFFFF), shadow);
        pose.popMatrix();
    }

    private static String fit(Font font, String text, int maxPx) {
        if (font.width(text) <= maxPx) return text;
        int ell = font.width("...");
        return font.plainSubstrByWidth(text, Math.max(0, maxPx - ell)) + "...";
    }

    /** Filled rectangle with one-pixel-cut corners. */
    private static void roundRect(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x + 1, y, x + w - 1, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }
}
