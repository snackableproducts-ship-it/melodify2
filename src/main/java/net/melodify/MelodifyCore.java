package net.melodify;

import net.melodify.config.MelodifyConfig;
import net.melodify.hud.HudData;
import net.melodify.hud.HudRenderer;
import net.melodify.lyrics.Lyrics;
import net.melodify.lyrics.LyricsManager;
import net.melodify.lyrics.LyricsService;
import net.melodify.spotify.PlaybackState;
import net.melodify.spotify.SpotifyAuth;
import net.melodify.spotify.SpotifyClient;

import java.nio.file.Path;

/**
 * Process-wide singletons. Everything here is Minecraft-version independent (no Minecraft imports);
 * only MelodifyClient, hud/HudRenderer and gui/* touch the game's API, so porting to a new Minecraft
 * version means touching those files only.
 */
public final class MelodifyCore {
    private static MelodifyConfig config;
    private static SpotifyAuth auth;
    private static SpotifyClient spotify;
    private static LyricsManager lyrics;
    private static final HudRenderer HUD = new HudRenderer();
    private static volatile boolean hudEditing;

    private MelodifyCore() {}

    public static void init(Path configDir) {
        config = MelodifyConfig.load(configDir.resolve("melodify.json"));
        Path dataDir = configDir.resolve("melodify");
        auth = new SpotifyAuth(config, dataDir.resolve("spotify-tokens.json"));
        lyrics = new LyricsManager(new LyricsService(dataDir.resolve("lyrics-cache")));
        spotify = new SpotifyClient(auth, config, lyrics::onPlayback);
        spotify.start();
    }

    public static void shutdown() {
        if (spotify != null) spotify.stop();
        if (lyrics != null) lyrics.shutdown();
        if (config != null) config.save();
    }

    public static MelodifyConfig config() { return config; }

    public static SpotifyAuth auth() { return auth; }

    public static SpotifyClient spotify() { return spotify; }

    public static LyricsManager lyrics() { return lyrics; }

    public static HudRenderer hud() { return HUD; }

    public static boolean hudEditing() { return hudEditing; }

    public static void setHudEditing(boolean v) { hudEditing = v; }

    /** What the HUD should show right now, or null for "nothing". Cheap: reads volatile fields only. */
    public static HudData hudData() {
        HudData test = lyrics.testData();
        if (test != null) return test;
        if (!config.hudEnabled) return null;

        PlaybackState s = spotify.state();
        if (!s.active()) return null;

        String message = null;
        Lyrics l = Lyrics.NONE;
        if (s.podcast()) {
            message = "Podcast episode";
        } else if (!s.key().equals(lyrics.currentKey())) {
            message = "Loading lyrics...";
        } else {
            switch (lyrics.status()) {
                case IDLE, LOADING -> message = "Loading lyrics...";
                case ERROR -> message = "Lyrics service unreachable";
                case READY -> {
                    l = lyrics.lyrics();
                    switch (l.kind()) {
                        case NONE -> message = "No lyrics found";
                        case INSTRUMENTAL -> message = "♪ Instrumental ♪";
                        case UNSYNCED -> {
                            if (!config.allowUnsyncedLyrics) message = "No synced lyrics available";
                        }
                        default -> { }
                    }
                }
            }
        }
        return new HudData(s.title(), s.artist(), l, s.positionMs(), s.playing(), message, false);
    }

    /** One-line lyrics status for the GUI. */
    public static String lyricsStatusText() {
        PlaybackState s = spotify.state();
        if (!s.active()) return "Lyrics: waiting for a song";
        if (s.podcast()) return "Lyrics: not available for podcasts";
        if (!s.key().equals(lyrics.currentKey())) return "Lyrics: loading...";
        return switch (lyrics.status()) {
            case IDLE, LOADING -> "Lyrics: loading...";
            case ERROR -> "Lyrics: service unreachable (retrying)";
            case READY -> switch (lyrics.lyrics().kind()) {
                case SYNCED -> "Lyrics: synced (" + lyrics.lyrics().source() + ")";
                case UNSYNCED -> config.allowUnsyncedLyrics
                        ? "Lyrics: unsynced, timing estimated"
                        : "Lyrics: only unsynced found (disabled)";
                case INSTRUMENTAL -> "Lyrics: instrumental";
                case NONE -> "Lyrics: none found";
            };
        };
    }
}
