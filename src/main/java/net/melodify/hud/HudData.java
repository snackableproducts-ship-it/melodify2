package net.melodify.hud;

import net.melodify.lyrics.Lyrics;

/**
 * Everything the HUD renderer needs for one frame.
 *
 * @param message if non-null, shown instead of lyric lines (loading, no lyrics, instrumental...)
 * @param test    true for the built-in sample used by "Test HUD" / the position editor
 */
public record HudData(String title, String artist, Lyrics lyrics, long positionMs, boolean playing, String message, boolean test) {}
