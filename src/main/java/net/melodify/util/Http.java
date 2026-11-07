package net.melodify.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Minimal blocking HTTP helper built on HttpURLConnection (part of java.base, so it works in every
 * Minecraft launcher runtime). Only ever call this from a background thread.
 */
public final class Http {
    private Http() {}

    public record Response(int status, String body, int retryAfterSeconds) {}

    public static Response get(String url, Map<String, String> headers) throws IOException {
        return request("GET", url, headers, null);
    }

    public static Response post(String url, Map<String, String> headers, String body) throws IOException {
        return request("POST", url, headers, body);
    }

    private static Response request(String method, String url, Map<String, String> headers, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            c.setConnectTimeout(8_000);
            c.setReadTimeout(10_000);
            c.setRequestMethod(method);
            c.setInstanceFollowRedirects(true);
            for (Map.Entry<String, String> h : headers.entrySet()) {
                c.setRequestProperty(h.getKey(), h.getValue());
            }
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(bytes);
                }
            }
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return new Response(status, text, c.getHeaderFieldInt("Retry-After", 0));
        } finally {
            c.disconnect();
        }
    }
}
