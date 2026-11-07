package net.melodify.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Null-safe accessors for Gson objects. */
public final class Json {
    private Json() {}

    public static String str(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? "" : e.getAsString();
    }

    public static long lng(JsonObject o, String key) {
        try {
            JsonElement e = o == null ? null : o.get(key);
            return e == null || e.isJsonNull() ? 0L : e.getAsLong();
        } catch (RuntimeException ex) {
            return 0L;
        }
    }

    public static double dbl(JsonObject o, String key) {
        try {
            JsonElement e = o == null ? null : o.get(key);
            return e == null || e.isJsonNull() ? 0.0 : e.getAsDouble();
        } catch (RuntimeException ex) {
            return 0.0;
        }
    }

    public static boolean bool(JsonObject o, String key) {
        try {
            JsonElement e = o == null ? null : o.get(key);
            return e != null && !e.isJsonNull() && e.getAsBoolean();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    public static JsonObject obj(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }
}
