package net.melodify.gui;

import net.melodify.MelodifyClient;
import net.melodify.MelodifyCore;
import net.melodify.config.MelodifyConfig;
import net.melodify.spotify.PlaybackState;
import net.melodify.spotify.SpotifyAuth;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Melodify ClickGUI: a floating panel with a category rail (Melodify -> Lyrics HUD / Spotify /
 * Appearance) and custom-drawn controls. Everything is laid out in a fixed 480x300 panel space that is
 * scaled down automatically on small windows / large GUI scales.
 */
public final class MelodifyScreen extends Screen {
    private static final int PW = 480, PH = 300, RAIL_W = 120, CX = 136, CW = 328;

    private enum Tab {
        LYRICS("Lyrics HUD"), SPOTIFY("Spotify"), APPEARANCE("Appearance");

        final String title;

        Tab(String title) { this.title = title; }
    }

    private final MelodifyConfig cfg = MelodifyCore.config();
    private final List<Ui.Widget> widgets = new ArrayList<>();
    private Tab tab = Tab.LYRICS;
    private Ui.Widget dragging;
    private float ox, oy, us = 1f;
    private int cy;
    private String flash = "";
    private long flashUntil;

    public MelodifyScreen() {
        super(Component.literal("Melodify"));
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void init() {
        us = Math.min(1f, Math.min((width - 16f) / PW, (height - 16f) / PH));
        ox = (width - PW * us) / 2f;
        oy = (height - PH * us) / 2f;
        MelodifyCore.spotify().setGuiOpen(true);
        buildTab();
    }

    @Override
    public void removed() {
        cfg.save();
        MelodifyCore.spotify().setGuiOpen(false);
    }

    @Override
    public boolean isPauseScreen() {
        return false; // keep the world (and the HUD behind the panel) running
    }

    // ------------------------------------------------------------------ tab content

    private void buildTab() {
        widgets.clear();
        dragging = null;
        cy = 44;
        switch (tab) {
            case LYRICS -> buildLyricsTab();
            case SPOTIFY -> buildSpotifyTab();
            case APPEARANCE -> buildAppearanceTab();
        }
    }

    private void buildLyricsTab() {
        toggle("Enable lyrics HUD", () -> cfg.hudEnabled, v -> cfg.hudEnabled = v);
        toggle("Show song information", () -> cfg.showSongInfo, v -> cfg.showSongInfo = v);
        toggle("Show previous lyric", () -> cfg.showPrevious, v -> cfg.showPrevious = v);
        toggle("Show next lyric", () -> cfg.showNext, v -> cfg.showNext = v);
        toggle("Animated transitions", () -> cfg.animatedTransitions, v -> cfg.animatedTransitions = v);
        toggle("Use unsynced lyrics as fallback", () -> cfg.allowUnsyncedLyrics, v -> cfg.allowUnsyncedLyrics = v);
        slider("Lyrics sync offset", -3000, 3000, 50, () -> cfg.lyricsOffsetMs, v -> cfg.lyricsOffsetMs = (int) v,
                v -> String.format(Locale.ROOT, "%+d ms", (int) v));
        cy += 6;
        int bw = (CW - 8) / 2;
        widgets.add(new Ui.Button(CX, cy, bw, 22, () -> "Test HUD (30 s)", true, () -> true,
                () -> MelodifyCore.lyrics().startTest()));
        widgets.add(new Ui.Button(CX + bw + 8, cy, bw, 22, () -> "Move HUD...", false, () -> true,
                () -> minecraft.setScreen(new HudEditorScreen(this))));
        cy += 30;
        widgets.add(new Ui.Custom(CX, cy, CW, 12, (g, f, x, y, w, h, mx, my) ->
                Ui.text(g, f, MelodifyCore.lyricsStatusText(), x + 6, y, Ui.MUTED)));
    }

    private void buildAppearanceTab() {
        slider("Text size", 0.6, 2.5, 0.05, () -> cfg.textScale, v -> cfg.textScale = (float) v,
                v -> String.format(Locale.ROOT, "%.2fx", v));
        widgets.add(new Ui.Custom(CX, cy, CW, 14, (g, f, x, y, w, h, mx, my) -> {
            Ui.text(g, f, "Text colour", x + 6, y + 3, Ui.MUTED);
            Ui.rect(g, x + w - 62, y + 1, 58, 11, 0xFF000000 | cfg.textColor);
            Ui.textRight(g, f, String.format(Locale.ROOT, "#%06X", cfg.textColor), x + w - 68, y + 3, Ui.MUTED);
        }));
        cy += 15;
        String[] names = {"Red", "Green", "Blue"};
        for (int i = 0; i < 3; i++) {
            final int ch = i;
            slider("  " + names[i], 0, 255, 1, () -> cfg.channel(ch), v -> cfg.setChannel(ch, (int) v),
                    v -> Integer.toString((int) v));
        }
        toggle("Show background", () -> cfg.showBackground, v -> cfg.showBackground = v);
        slider("Background opacity", 0, 1, 0.05, () -> cfg.backgroundOpacity, v -> cfg.backgroundOpacity = (float) v,
                v -> Math.round(v * 100) + "%");
        slider("Animation speed", 0.25, 3, 0.05, () -> cfg.animationSpeed, v -> cfg.animationSpeed = (float) v,
                v -> String.format(Locale.ROOT, "%.2fx", v));
        widgets.add(new Ui.Cycle(CX, cy, CW, "Lyric alignment", () -> cfg.alignment.label(),
                () -> {
                    cfg.alignment = cfg.alignment.next();
                    cfg.save();
                }));
        cy += 21;
        slider("HUD width", 140, 400, 5, () -> cfg.hudWidth, v -> cfg.hudWidth = (int) v,
                v -> (int) v + " px");
        cy += 4;
        int bw = (CW - 8) / 2;
        widgets.add(new Ui.Button(CX, cy, bw, 22, () -> "Move HUD...", true, () -> true,
                () -> minecraft.setScreen(new HudEditorScreen(this))));
        widgets.add(new Ui.Button(CX + bw + 8, cy, bw, 22, () -> "Reset position", false, () -> true, () -> {
            cfg.resetPosition();
            cfg.save();
        }));
    }

    private void buildSpotifyTab() {
        SpotifyAuth auth = MelodifyCore.auth();

        // connection status + message
        widgets.add(new Ui.Custom(CX, cy, CW, 36, (g, f, x, y, w, h, mx, my) -> {
            SpotifyAuth.Status st = auth.status();
            int col = switch (st) {
                case CONNECTED -> Ui.GREEN;
                case CONNECTING -> Ui.AMBER;
                case ERROR -> Ui.RED;
                case DISCONNECTED -> Ui.MUTED;
            };
            String label = switch (st) {
                case CONNECTED -> "Connected to Spotify";
                case CONNECTING -> "Connecting...";
                case ERROR -> "Connection problem";
                case DISCONNECTED -> "Not connected";
            };
            Ui.rect(g, x + 6, y + 2, 7, 7, col);
            Ui.text(g, f, label, x + 19, y + 2, Ui.TEXT);
            String msg = statusLine();
            int msgCol = st == SpotifyAuth.Status.ERROR || (st == SpotifyAuth.Status.CONNECTED && !MelodifyCore.spotify().error().isEmpty())
                    ? Ui.RED : Ui.MUTED;
            List<FormattedCharSequence> lines = f.split(Component.literal(msg), w - 12);
            for (int i = 0; i < Math.min(2, lines.size()); i++) {
                g.drawString(f, lines.get(i), x + 6, y + 14 + i * 10, msgCol, false);
            }
        }));
        cy += 40;

        // client id row
        widgets.add(new Ui.Custom(CX, cy, CW, 22, (g, f, x, y, w, h, mx, my) -> {
            String id = cfg.spotifyClientId == null ? "" : cfg.spotifyClientId;
            String shown = id.length() >= 12 ? id.substring(0, 4) + "..." + id.substring(id.length() - 4) : "not set";
            Ui.text(g, f, "Client ID", x + 6, y + 7, Ui.TEXT);
            Ui.text(g, f, shown, x + 62, y + 7, id.isEmpty() ? Ui.AMBER : Ui.MUTED);
        }));
        widgets.add(new Ui.Button(CX + CW - 112, cy, 112, 22, () -> "Paste from clipboard", false, () -> true, this::pasteClientId));
        cy += 28;

        // connect / disconnect / copy link
        int bw = (CW - 16) / 3;
        widgets.add(new Ui.Button(CX, cy, bw, 22, () -> auth.isConnected() ? "Reconnect" : "Connect", true,
                () -> auth.status() != SpotifyAuth.Status.CONNECTING, auth::connect));
        widgets.add(new Ui.Button(CX + bw + 8, cy, bw, 22, () -> auth.status() == SpotifyAuth.Status.CONNECTING ? "Cancel" : "Disconnect", false,
                () -> auth.isConnected() || auth.status() == SpotifyAuth.Status.CONNECTING || auth.status() == SpotifyAuth.Status.ERROR,
                auth::disconnect));
        widgets.add(new Ui.Button(CX + (bw + 8) * 2, cy, bw, 22, () -> "Copy login link", false,
                () -> auth.pendingAuthUrl() != null, () -> {
                    String url = auth.pendingAuthUrl();
                    if (url != null) {
                        Minecraft.getInstance().keyboardHandler.setClipboard(url);
                        flash("Login link copied. Paste it into your browser.");
                    }
                }));
        cy += 30;

        // now playing card
        widgets.add(new Ui.Custom(CX, cy, CW, 66, this::paintNowPlaying));
        cy += 72;
        widgets.add(new Ui.Custom(CX, cy, CW, 12, (g, f, x, y, w, h, mx, my) ->
                Ui.text(g, f, MelodifyCore.lyricsStatusText(), x + 6, y, Ui.MUTED)));
        cy += 14;
        widgets.add(new Ui.Custom(CX, cy, CW, 12, (g, f, x, y, w, h, mx, my) ->
                Ui.text(g, f, "Redirect URI to register: " + auth.redirectUri(), x + 6, y, 0xFF5F6575)));
    }

    private void paintNowPlaying(GuiGraphics g, Font f, int x, int y, int w, int h, int mx, int my) {
        Ui.rect(g, x, y, w, h, Ui.PANEL_ALT);
        PlaybackState s = MelodifyCore.spotify().state();
        if (!s.active()) {
            Ui.text(g, f, "Nothing playing", x + 10, y + 12, Ui.TEXT);
            Ui.text(g, f, "Start a song in the Spotify app.", x + 10, y + 26, Ui.MUTED);
            return;
        }
        Ui.text(g, f, Ui.fit(f, s.title(), w - 20), x + 10, y + 8, Ui.TEXT);
        Ui.text(g, f, Ui.fit(f, s.artist(), w - 20), x + 10, y + 21, Ui.MUTED);
        Ui.text(g, f, s.playing() ? "Playing" : "Paused", x + 10, y + 34, s.playing() ? Ui.GREEN : Ui.AMBER);

        long pos = s.positionMs(), dur = Math.max(1L, s.durationMs());
        int bx = x + 10, bw = w - 20, by = y + 52;
        g.fill(bx, by, bx + bw, by + 4, Ui.TRACK);
        g.fill(bx, by, bx + (int) (bw * Math.min(1.0, pos / (double) dur)), by + 4, Ui.ACCENT);
        Ui.textRight(g, f, mmss(pos) + " / " + mmss(s.durationMs()), x + w - 10, y + 34, Ui.MUTED);
    }

    private String statusLine() {
        if (System.currentTimeMillis() < flashUntil) return flash;
        SpotifyAuth auth = MelodifyCore.auth();
        if (auth.status() == SpotifyAuth.Status.CONNECTED) {
            String e = MelodifyCore.spotify().error();
            return e.isEmpty() ? "Reading playback from your Spotify account (works with the desktop app)." : e;
        }
        return auth.message();
    }

    private void pasteClientId() {
        String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
        String s = clip == null ? "" : clip.trim();
        if (s.matches("[0-9a-fA-F]{32}")) {
            cfg.spotifyClientId = s.toLowerCase(Locale.ROOT);
            cfg.save();
            flash("Client ID saved. You can press Connect now.");
        } else {
            flash("Clipboard doesn't hold a Client ID (32 letters/digits). Copy it from the Spotify dashboard first.");
        }
    }

    private void flash(String msg) {
        flash = msg;
        flashUntil = System.currentTimeMillis() + 6000L;
    }

    private static String mmss(long ms) {
        long s = Math.max(0L, ms / 1000L);
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    // ------------------------------------------------------------------ widget helpers

    private void toggle(String label, java.util.function.BooleanSupplier get, java.util.function.Consumer<Boolean> set) {
        widgets.add(new Ui.Toggle(CX, cy, CW, label, get, v -> {
            set.accept(v);
            cfg.save();
        }));
        cy += 21;
    }

    private void slider(String label, double min, double max, double step, java.util.function.DoubleSupplier get,
                        java.util.function.DoubleConsumer set, java.util.function.DoubleFunction<String> fmt) {
        widgets.add(new Ui.Slider(CX, cy, CW, label, min, max, step, get, set, fmt));
        cy += 21;
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0x66000000);
        int mx = toLocalX(mouseX), my = toLocalY(mouseY);

        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(ox, oy);
        pose.scale(us, us);

        Ui.rect(g, -1, -1, PW + 2, PH + 2, Ui.BORDER);
        Ui.rect(g, 0, 0, PW, PH, Ui.PANEL);
        g.fill(1, 0, RAIL_W, PH, Ui.RAIL);
        g.fill(RAIL_W, 8, RAIL_W + 1, PH - 8, Ui.BORDER);

        Font f = this.font;
        // rail: category title
        g.drawString(f, Component.literal("Melodify").withStyle(net.minecraft.ChatFormatting.BOLD), 16, 16, Ui.TEXT, false);
        Ui.text(g, f, "MELODIFY", 16, 34, Ui.ACCENT);
        // rail: tabs
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            int ty = 52 + i * 24;
            boolean sel = tabs[i] == tab;
            boolean hov = mx >= 8 && mx < RAIL_W - 8 && my >= ty && my < ty + 20;
            if (sel) Ui.rect(g, 8, ty, RAIL_W - 16, 20, 0xFF1E2230);
            else if (hov) Ui.rect(g, 8, ty, RAIL_W - 16, 20, 0x18FFFFFF);
            if (sel) g.fill(8, ty + 3, 10, ty + 17, Ui.ACCENT);
            Ui.text(g, f, tabs[i].title, 20, ty + 6, sel ? Ui.TEXT : Ui.MUTED);
        }
        // rail: footer connection indicator
        SpotifyAuth.Status st = MelodifyCore.auth().status();
        int dot = st == SpotifyAuth.Status.CONNECTED ? Ui.GREEN : st == SpotifyAuth.Status.CONNECTING ? Ui.AMBER
                : st == SpotifyAuth.Status.ERROR ? Ui.RED : Ui.MUTED;
        Ui.rect(g, 16, PH - 22, 6, 6, dot);
        Ui.text(g, f, st == SpotifyAuth.Status.CONNECTED ? "Spotify linked" : "Spotify offline", 28, PH - 23, Ui.MUTED);

        // content header
        g.drawString(f, Component.literal(tab.title).withStyle(net.minecraft.ChatFormatting.BOLD), CX + 6, 20, Ui.TEXT, false);
        g.fill(CX + 6, 34, CX + CW - 6, 35, Ui.BORDER);

        for (Ui.Widget w : widgets) w.render(g, f, mx, my);
        pose.popMatrix();
    }

    // ------------------------------------------------------------------ input

    private int toLocalX(double mx) { return (int) Math.floor((mx - ox) / us); }

    private int toLocalY(double my) { return (int) Math.floor((my - oy) / us); }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            int mx = toLocalX(event.x()), my = toLocalY(event.y());
            Tab[] tabs = Tab.values();
            for (int i = 0; i < tabs.length; i++) {
                int ty = 52 + i * 24;
                if (mx >= 8 && mx < RAIL_W - 8 && my >= ty && my < ty + 20) {
                    if (tabs[i] != tab) {
                        cfg.save();
                        tab = tabs[i];
                        buildTab();
                    }
                    return true;
                }
            }
            for (Ui.Widget w : widgets) {
                if (w.hit(mx, my) && w.click(mx, my)) {
                    if (w.draggable()) dragging = w;
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragging != null) {
            dragging.drag(toLocalX(event.x()), toLocalY(event.y()));
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging != null) {
            dragging.release();
            dragging = null;
            cfg.save();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (MelodifyClient.OPEN_GUI != null && MelodifyClient.OPEN_GUI.matches(event)) {
            onClose();
            return true;
        }
        return super.keyPressed(event); // Escape closes
    }
}
