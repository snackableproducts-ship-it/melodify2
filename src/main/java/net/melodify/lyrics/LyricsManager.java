package net.melodify.lyrics;

import net.melodify.hud.HudData;
import net.melodify.spotify.PlaybackState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Glue between playback changes and the lyrics service. Fetching always happens on one background
 * thread; the render thread only ever reads the volatile results.
 */
public final class LyricsManager {
    public enum Status { IDLE, LOADING, READY, ERROR }

    private static final Logger LOG = LoggerFactory.getLogger("Melodify/Lyrics");
    private static final long RETRY_AFTER_ERROR_MS = 30_000L;

    // Clearly-labelled built-in sample used only by the "Test HUD" button and the HUD position editor.
    private static final Lyrics SAMPLE = new Lyrics(Lyrics.Kind.SYNCED, List.of(
            new LyricLine(0, "Melodify lyrics HUD test"),
            new LyricLine(3_500, "This is a preview, not a real song"),
            new LyricLine(7_000, "Drag the HUD anywhere you like"),
            new LyricLine(10_500, "Previous and next lines fade softly"),
            new LyricLine(14_000, "Tune size, colour and speed in Appearance"),
            new LyricLine(17_500, "Connect Spotify to see real lyrics"),
            new LyricLine(21_000, LrcParser.BREAK)), "sample");
    private static final long SAMPLE_LOOP_MS = 24_500L;
    private static final long TEST_DURATION_MS = 30_000L;

    private final LyricsService service;
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Melodify-Lyrics");
        t.setDaemon(true);
        return t;
    });

    private volatile String key = "";
    private volatile Status status = Status.IDLE;
    private volatile Lyrics lyrics = Lyrics.NONE;
    private volatile long errorAtMs;
    private volatile long testStartNanos;

    public LyricsManager(LyricsService service) {
        this.service = service;
    }

    public void shutdown() {
        exec.shutdownNow();
    }

    // ------------------------------------------------------------------ playback hook (poller thread)

    public void onPlayback(PlaybackState s) {
        if (!s.active() || s.podcast()) {
            key = "";
            status = Status.IDLE;
            lyrics = Lyrics.NONE;
            return;
        }
        String k = s.key();
        if (k.equals(key)) {
            if (status == Status.ERROR && System.currentTimeMillis() - errorAtMs > RETRY_AFTER_ERROR_MS) fetch(s, k);
            return;
        }
        key = k;
        lyrics = Lyrics.NONE; // never show the previous song's lyrics for the new song
        fetch(s, k);
    }

    private void fetch(PlaybackState s, String k) {
        status = Status.LOADING;
        exec.submit(() -> {
            if (!k.equals(key)) return; // skipped away before we even started
            try {
                Lyrics l = service.fetch(s);
                if (k.equals(key)) {
                    lyrics = l;
                    status = Status.READY;
                }
            } catch (Throwable e) {
                LOG.warn("Lyrics lookup failed: {}", e.toString());
                if (k.equals(key)) {
                    status = Status.ERROR;
                    errorAtMs = System.currentTimeMillis();
                }
            }
        });
    }

    // ------------------------------------------------------------------ accessors

    public String currentKey() { return key; }

    public Status status() { return status; }

    public Lyrics lyrics() { return lyrics; }

    // ------------------------------------------------------------------ test / preview

    public void startTest() {
        testStartNanos = System.nanoTime();
    }

    public boolean isTesting() {
        return testStartNanos != 0L;
    }

    /** Sample data for the "Test HUD" button (30 s), or null once it has finished. */
    public HudData testData() {
        long start = testStartNanos;
        if (start == 0L) return null;
        long elapsed = (System.nanoTime() - start) / 1_000_000L;
        if (elapsed > TEST_DURATION_MS) {
            testStartNanos = 0L;
            return null;
        }
        return new HudData("HUD Test", "Melodify", SAMPLE, elapsed % SAMPLE_LOOP_MS, true, null, true);
    }

    /** Endless looping sample used by the HUD position editor. */
    public HudData previewData() {
        long pos = (System.nanoTime() / 1_000_000L) % SAMPLE_LOOP_MS;
        return new HudData("HUD Preview", "Melodify", SAMPLE, pos, true, null, true);
    }
}
