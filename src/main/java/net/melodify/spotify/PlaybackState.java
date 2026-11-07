package net.melodify.spotify;

/** Immutable snapshot of what Spotify reported at {@code fetchedNanos}. */
public record PlaybackState(
        boolean active,
        String trackId,
        String title,
        String artist,
        String firstArtist,
        String album,
        long durationMs,
        boolean playing,
        boolean podcast,
        long progressMs,
        long fetchedNanos) {

    public static final PlaybackState NONE =
            new PlaybackState(false, "", "", "", "", "", 0L, false, false, 0L, 0L);

    /** Current position, interpolated locally between polls so lyrics stay smooth without extra requests. */
    public long positionMs() {
        if (!active) return 0L;
        long p = progressMs;
        if (playing) p += (System.nanoTime() - fetchedNanos) / 1_000_000L;
        if (durationMs > 0) p = Math.min(p, durationMs);
        return Math.max(0L, p);
    }

    /** Stable identity of the track (Spotify id, or title|artist for local files). */
    public String key() {
        return trackId.isEmpty() ? title + "|" + artist : trackId;
    }
}
