package net.melodify.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * All persisted settings. Stored as JSON in {@code <minecraft>/config/melodify.json}.
 * The Spotify Client ID is not a secret (PKCE flow, no client secret). Access/refresh tokens are NOT
 * stored here - see SpotifyAuth.
 */
public final class MelodifyConfig {
    private static final Logger LOG = LoggerFactory.getLogger("Melodify/Config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public enum Alignment {
        LEFT, CENTER, RIGHT;

        public Alignment next() {
            return values()[(ordinal() + 1) % values().length];
        }

        public String label() {
            return name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT);
        }
    }

    // Lyrics HUD
    public boolean hudEnabled = true;
    public boolean showSongInfo = true;
    public boolean showPrevious = true;
    public boolean showNext = true;
    public boolean animatedTransitions = true;
    public boolean allowUnsyncedLyrics = true;
    public int lyricsOffsetMs = 0;

    // Appearance
    public float textScale = 1.0f;
    /** RGB, no alpha. */
    public int textColor = 0xFFFFFF;
    public boolean showBackground = true;
    public float backgroundOpacity = 0.45f;
    public float animationSpeed = 1.0f;
    public Alignment alignment = Alignment.CENTER;
    public int hudWidth = 240;
    /** HUD position as a fraction (0..1) of the free screen space, so it survives resolution changes. */
    public float hudX = 0.5f;
    public float hudY = 0.04f;

    // Spotify
    public String spotifyClientId = "";
    public int redirectPort = 8888;

    private transient Path file;

    public static MelodifyConfig load(Path file) {
        MelodifyConfig cfg = null;
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                cfg = GSON.fromJson(r, MelodifyConfig.class);
            } catch (Exception e) {
                LOG.warn("Could not read {} - using defaults ({})", file, e.toString());
            }
        }
        if (cfg == null) cfg = new MelodifyConfig();
        cfg.file = file;
        cfg.sanitize();
        cfg.save();
        return cfg;
    }

    public synchronized void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(this), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOG.warn("Could not save config: {}", e.toString());
        }
    }

    public void sanitize() {
        textScale = clamp(textScale, 0.6f, 2.5f);
        backgroundOpacity = clamp(backgroundOpacity, 0f, 1f);
        animationSpeed = clamp(animationSpeed, 0.25f, 3f);
        hudX = clamp(hudX, 0f, 1f);
        hudY = clamp(hudY, 0f, 1f);
        hudWidth = Math.max(140, Math.min(400, hudWidth));
        lyricsOffsetMs = Math.max(-5000, Math.min(5000, lyricsOffsetMs));
        textColor &= 0xFFFFFF;
        if (alignment == null) alignment = Alignment.CENTER;
        if (spotifyClientId == null) spotifyClientId = "";
        if (redirectPort < 1024 || redirectPort > 65535) redirectPort = 8888;
    }

    public int channel(int index) {
        return (textColor >> (16 - 8 * index)) & 0xFF;
    }

    public void setChannel(int index, int value) {
        int shift = 16 - 8 * index;
        textColor = (textColor & ~(0xFF << shift)) | ((Math.max(0, Math.min(255, value)) & 0xFF) << shift);
    }

    public void resetPosition() {
        hudX = 0.5f;
        hudY = 0.04f;
    }

    private static float clamp(float v, float lo, float hi) {
        return Float.isNaN(v) ? lo : Math.max(lo, Math.min(hi, v));
    }
}
