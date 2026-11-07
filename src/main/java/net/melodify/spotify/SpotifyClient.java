package net.melodify.spotify;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.melodify.config.MelodifyConfig;
import net.melodify.util.Http;
import net.melodify.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Polls Spotify's Web API ("Get Currently Playing Track") on a background thread.
 *
 * The Web API reports playback of the signed-in account on any device, including the Spotify desktop
 * app. Polling happens every few seconds only; between polls the position is interpolated locally, so
 * nothing network-related ever runs on the Minecraft tick or render thread.
 */
public final class SpotifyClient {
    private static final Logger LOG = LoggerFactory.getLogger("Melodify/Spotify");
    private static final String URL = "https://api.spotify.com/v1/me/player/currently-playing?additional_types=episode";

    private final SpotifyAuth auth;
    private final MelodifyConfig config;
    private final Consumer<PlaybackState> listener;
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Melodify-SpotifyPoller");
        t.setDaemon(true);
        return t;
    });

    private volatile PlaybackState state = PlaybackState.NONE;
    private volatile String error = "";
    private volatile boolean guiOpen;
    private long nextPollAt;
    private int failures;

    public SpotifyClient(SpotifyAuth auth, MelodifyConfig config, Consumer<PlaybackState> listener) {
        this.auth = auth;
        this.config = config;
        this.listener = listener;
    }

    public void start() {
        exec.scheduleWithFixedDelay(this::tick, 500, 500, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        exec.shutdownNow();
    }

    public PlaybackState state() { return state; }

    /** Last problem talking to Spotify, or empty. */
    public String error() { return error; }

    public void setGuiOpen(boolean open) { this.guiOpen = open; }

    // ------------------------------------------------------------------ polling

    private void tick() {
        try {
            if (!auth.isConnected()) {
                if (state.active()) publish(PlaybackState.NONE);
                error = "";
                failures = 0;
                return;
            }
            long now = System.currentTimeMillis();
            if (now < nextPollAt) return;

            long interval;
            PlaybackState next = null;
            try {
                String token = auth.getAccessToken();
                if (token == null) return;
                long sent = System.nanoTime();
                Http.Response r = Http.get(URL, Map.of("Authorization", "Bearer " + token, "Accept", "application/json"));
                long received = System.nanoTime();
                switch (r.status()) {
                    case 200 -> {
                        next = parse(r.body(), received - (received - sent) / 2);
                        error = "";
                        failures = 0;
                        boolean idle = !config.hudEnabled && !guiOpen;
                        interval = idle ? 8000 : (next.playing() ? 2000 : 3000);
                    }
                    case 204 -> {
                        next = PlaybackState.NONE;
                        error = "";
                        failures = 0;
                        interval = 4000;
                    }
                    case 401 -> {
                        auth.invalidateAccessToken();
                        interval = 500;
                    }
                    case 403 -> {
                        error = "Spotify refused access. If your app is in Development Mode, add your account under "
                                + "\"User Management\" in the Spotify dashboard.";
                        interval = 15000;
                    }
                    case 429 -> {
                        error = "Spotify rate limit hit - slowing down.";
                        interval = Math.max(1, r.retryAfterSeconds()) * 1000L + 500;
                    }
                    default -> {
                        error = "Spotify returned HTTP " + r.status();
                        interval = 5000;
                    }
                }
            } catch (IOException e) {
                failures++;
                error = "Network problem: " + e.getClass().getSimpleName();
                interval = Math.min(30_000L, 2000L * failures);
            }
            nextPollAt = System.currentTimeMillis() + interval;
            if (next != null) publish(next);
        } catch (Throwable t) {
            // Never let anything kill the poller or reach the game.
            error = "Unexpected error: " + t.getClass().getSimpleName();
            LOG.warn("Poll failed", t);
            nextPollAt = System.currentTimeMillis() + 5000;
        }
    }

    private void publish(PlaybackState next) {
        PlaybackState old = state;
        if (!old.key().equals(next.key()) || old.active() != next.active()) {
            LOG.debug("Track changed: {} - {}", next.artist(), next.title());
        } else if (old.playing() != next.playing()) {
            LOG.debug(next.playing() ? "Playback resumed" : "Playback paused");
        }
        state = next;
        try {
            listener.accept(next);
        } catch (RuntimeException e) {
            LOG.warn("Listener failed", e);
        }
    }

    private static PlaybackState parse(String body, long fetchedNanos) {
        JsonObject o = JsonParser.parseString(body).getAsJsonObject();
        JsonObject item = Json.obj(o, "item");
        if (item == null) return PlaybackState.NONE; // ad, or nothing loaded

        boolean podcast = "episode".equals(Json.str(o, "currently_playing_type")) || "episode".equals(Json.str(item, "type"));
        StringBuilder artists = new StringBuilder();
        String first = "";
        JsonElement arr = item.get("artists");
        if (arr != null && arr.isJsonArray()) {
            JsonArray a = arr.getAsJsonArray();
            for (JsonElement e : a) {
                String n = e.isJsonObject() ? Json.str(e.getAsJsonObject(), "name") : "";
                if (n.isEmpty()) continue;
                if (first.isEmpty()) first = n;
                if (artists.length() > 0) artists.append(", ");
                artists.append(n);
            }
        }
        if (podcast && artists.length() == 0) {
            String show = Json.str(Json.obj(item, "show"), "name");
            artists.append(show);
            first = show;
        }
        String album = Json.str(Json.obj(item, "album"), "name");
        return new PlaybackState(
                true,
                Json.str(item, "id"),
                Json.str(item, "name"),
                artists.toString(),
                first,
                album,
                Json.lng(item, "duration_ms"),
                Json.bool(o, "is_playing"),
                podcast,
                Json.lng(o, "progress_ms"),
                fetchedNanos);
    }
}
