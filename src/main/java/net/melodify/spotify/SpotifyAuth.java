package net.melodify.spotify;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.melodify.config.MelodifyConfig;
import net.melodify.util.Http;
import net.melodify.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Spotify "Authorization Code with PKCE" flow, as documented at
 * https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow
 *
 * No client secret is used or needed. The user supplies their own Client ID (config/melodify.json).
 * Only the long-lived refresh token is written to disk (config/melodify/spotify-tokens.json); the
 * short-lived access token lives in memory. Tokens are never logged or shown in the GUI.
 */
public final class SpotifyAuth {
    public enum Status { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    private static final Logger LOG = LoggerFactory.getLogger("Melodify/Auth");
    private static final String AUTH_URL = "https://accounts.spotify.com/authorize";
    private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";
    private static final String SCOPES = "user-read-currently-playing user-read-playback-state";
    private static final long LOGIN_TIMEOUT_MS = 5 * 60_000L;
    private static final Map<String, String> FORM_HEADERS = Map.of(
            "Content-Type", "application/x-www-form-urlencoded",
            "Accept", "application/json");

    private final MelodifyConfig config;
    private final Path tokenFile;
    private final SecureRandom random = new SecureRandom();
    private final Object refreshLock = new Object();

    private volatile Status status = Status.DISCONNECTED;
    private volatile String message = "Not connected";
    private volatile String pendingAuthUrl;
    private volatile String refreshToken;
    private volatile String accessToken;
    private volatile long expiresAtMs;
    private volatile int generation;
    private volatile boolean cancelled;
    private volatile ServerSocket server;

    public SpotifyAuth(MelodifyConfig config, Path tokenFile) {
        this.config = config;
        this.tokenFile = tokenFile;
        loadTokens();
    }

    // ------------------------------------------------------------------ public state

    public Status status() { return status; }

    public String message() { return message; }

    /** The authorization URL while a login is in progress (so the GUI can offer "copy link"). */
    public String pendingAuthUrl() { return pendingAuthUrl; }

    public boolean isConnected() { return refreshToken != null; }

    public String redirectUri() { return "http://127.0.0.1:" + config.redirectPort + "/callback"; }

    // ------------------------------------------------------------------ actions

    /** Starts the browser login. Returns immediately; progress is reported through status()/message(). */
    public synchronized void connect() {
        if (status == Status.CONNECTING) return;
        String id = config.spotifyClientId == null ? "" : config.spotifyClientId.trim();
        if (id.isEmpty()) {
            fail("Paste your Spotify Client ID first (see the README).");
            return;
        }
        final int gen = ++generation;
        cancelled = false;
        status = Status.CONNECTING;
        message = "Waiting for you to approve access in your browser...";
        Thread t = new Thread(() -> runLogin(id, gen), "Melodify-SpotifyLogin");
        t.setDaemon(true);
        t.start();
    }

    /** Cancels a pending login and forgets all tokens (memory and disk). */
    public void disconnect() {
        cancelled = true;
        generation++;
        closeServer();
        refreshToken = null;
        accessToken = null;
        expiresAtMs = 0L;
        pendingAuthUrl = null;
        try {
            Files.deleteIfExists(tokenFile);
        } catch (IOException e) {
            LOG.warn("Could not delete token file: {}", e.toString());
        }
        status = Status.DISCONNECTED;
        message = "Disconnected";
    }

    /** Returns a valid access token (refreshing if needed), or null when not connected. Blocking: background threads only. */
    public String getAccessToken() throws IOException {
        if (refreshToken == null) return null;
        String t = accessToken;
        if (t != null && System.currentTimeMillis() < expiresAtMs) return t;
        synchronized (refreshLock) {
            t = accessToken;
            if (t != null && System.currentTimeMillis() < expiresAtMs) return t;
            if (refreshToken == null) return null;
            refresh();
            return refreshToken == null ? null : accessToken;
        }
    }

    /** Called when the API answers 401 so the next call refreshes. */
    public void invalidateAccessToken() {
        expiresAtMs = 0L;
    }

    // ------------------------------------------------------------------ login flow

    private void runLogin(String clientId, int gen) {
        String verifier = randomUrlSafe(64);
        String challenge = base64Url(sha256(verifier.getBytes(StandardCharsets.US_ASCII)));
        String state = randomUrlSafe(16);
        String redirect = redirectUri();

        try (ServerSocket ss = new ServerSocket(config.redirectPort, 4, InetAddress.getByName("127.0.0.1"))) {
            server = ss;
            ss.setSoTimeout(1000);

            String url = AUTH_URL + "?" + query(
                    "response_type", "code",
                    "client_id", clientId,
                    "scope", SCOPES,
                    "redirect_uri", redirect,
                    "state", state,
                    "code_challenge_method", "S256",
                    "code_challenge", challenge);
            pendingAuthUrl = url;
            openBrowser(url);

            long deadline = System.currentTimeMillis() + LOGIN_TIMEOUT_MS;
            while (!cancelled && gen == generation && System.currentTimeMillis() < deadline) {
                Socket s = acceptOrNull(ss);
                if (s == null) continue;
                try (s) {
                    Map<String, String> q = readCallback(s);
                    if (q == null) continue; // favicon or something unrelated
                    if (q.containsKey("error")) {
                        fail("Spotify authorization was denied (" + q.get("error") + ").");
                        return;
                    }
                    if (!state.equals(q.get("state")) || q.get("code") == null) {
                        fail("Login response did not match this request. Please try again.");
                        return;
                    }
                    exchangeCode(q.get("code"), clientId, verifier, redirect, gen);
                    return;
                }
            }
            if (!cancelled && gen == generation) fail("Login timed out. Press Connect to try again.");
        } catch (BindException e) {
            fail("Port " + config.redirectPort + " is already in use. Change \"redirectPort\" in melodify.json "
                    + "and add the same redirect URI in the Spotify dashboard.");
        } catch (Exception e) {
            if (!cancelled && gen == generation) fail("Login failed: " + e.getClass().getSimpleName());
            LOG.warn("Spotify login failed: {}", e.toString());
        } finally {
            server = null;
            pendingAuthUrl = null;
        }
    }

    private static Socket acceptOrNull(ServerSocket ss) throws IOException {
        try {
            return ss.accept();
        } catch (SocketTimeoutException e) {
            return null;
        }
    }

    /** Reads one HTTP request from the browser; answers it; returns the query if it was /callback. */
    private Map<String, String> readCallback(Socket s) {
        try {
            s.setSoTimeout(5000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
            String line = in.readLine();
            if (line == null) return null;
            String[] parts = line.split(" ");
            if (parts.length < 2) return null;
            URI uri = URI.create("http://127.0.0.1" + parts[1]);
            if (!"/callback".equals(uri.getPath())) {
                respond(s, 404, "Not found");
                return null;
            }
            Map<String, String> q = parseQuery(uri.getRawQuery());
            boolean ok = q.containsKey("code") && !q.containsKey("error");
            respond(s, 200, "<!doctype html><meta charset=utf-8><title>Melodify</title>"
                    + "<body style='font-family:sans-serif;background:#12141a;color:#e8eaf0;text-align:center;padding-top:15vh'>"
                    + "<h1>" + (ok ? "Melodify is connected" : "Authorization failed") + "</h1>"
                    + "<p>" + (ok ? "You can close this tab and return to Minecraft." : "Return to Minecraft and try again.") + "</p>");
            return q;
        } catch (Exception e) {
            return null;
        }
    }

    private static void respond(Socket s, int code, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Not Found") + "\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = s.getOutputStream();
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private void exchangeCode(String code, String clientId, String verifier, String redirect, int gen) throws IOException {
        Http.Response r = Http.post(TOKEN_URL, FORM_HEADERS, query(
                "grant_type", "authorization_code",
                "code", code,
                "redirect_uri", redirect,
                "client_id", clientId,
                "code_verifier", verifier));
        if (r.status() != 200) {
            String detail = "";
            try {
                detail = Json.str(JsonParser.parseString(r.body()).getAsJsonObject(), "error_description");
            } catch (RuntimeException ignored) {
                // body was not JSON
            }
            fail("Spotify rejected the login (HTTP " + r.status() + (detail.isEmpty() ? "" : ": " + detail) + ").");
            return;
        }
        if (gen != generation) return; // user disconnected meanwhile
        applyTokens(JsonParser.parseString(r.body()).getAsJsonObject());
        LOG.info("Connected to Spotify");
    }

    private void refresh() throws IOException {
        String rt = refreshToken;
        int gen = generation;
        String id = config.spotifyClientId == null ? "" : config.spotifyClientId.trim();
        if (id.isEmpty()) {
            status = Status.ERROR;
            message = "Client ID missing - paste it again to refresh the session.";
            return;
        }
        Http.Response r = Http.post(TOKEN_URL, FORM_HEADERS, query(
                "grant_type", "refresh_token",
                "refresh_token", rt,
                "client_id", id));
        if (gen != generation) return;
        if (r.status() == 200) {
            applyTokens(JsonParser.parseString(r.body()).getAsJsonObject());
        } else if (r.status() == 400 || r.status() == 401) {
            clearTokens();
            status = Status.ERROR;
            message = "Your Spotify session expired. Press Connect to sign in again.";
        } else {
            throw new IOException("Token refresh failed (HTTP " + r.status() + ")");
        }
    }

    private void applyTokens(JsonObject o) {
        String at = Json.str(o, "access_token");
        long expiresIn = Json.lng(o, "expires_in");
        String rt = Json.str(o, "refresh_token");
        if (at.isEmpty()) {
            fail("Spotify did not return an access token.");
            return;
        }
        accessToken = at;
        expiresAtMs = System.currentTimeMillis() + Math.max(60L, expiresIn - 30L) * 1000L;
        if (!rt.isEmpty()) refreshToken = rt;
        saveTokens();
        status = Status.CONNECTED;
        message = "Connected";
    }

    // ------------------------------------------------------------------ token storage

    private void loadTokens() {
        try {
            if (Files.exists(tokenFile)) {
                JsonObject o = JsonParser.parseString(Files.readString(tokenFile, StandardCharsets.UTF_8)).getAsJsonObject();
                String rt = Json.str(o, "refresh_token");
                if (!rt.isEmpty()) {
                    refreshToken = rt;
                    status = Status.CONNECTED;
                    message = "Connected";
                }
            }
        } catch (Exception e) {
            LOG.warn("Ignoring unreadable token file: {}", e.toString());
        }
    }

    private void saveTokens() {
        try {
            Files.createDirectories(tokenFile.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("refresh_token", refreshToken);
            Files.writeString(tokenFile, o.toString(), StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(tokenFile, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException | IOException ignored) {
                // Windows: file lives in the user's own profile anyway
            }
        } catch (IOException e) {
            LOG.warn("Could not store login: {}", e.toString());
        }
    }

    private void clearTokens() {
        refreshToken = null;
        accessToken = null;
        expiresAtMs = 0L;
        try {
            Files.deleteIfExists(tokenFile);
        } catch (IOException ignored) {
            // best effort
        }
    }

    // ------------------------------------------------------------------ helpers

    private void fail(String msg) {
        status = Status.ERROR;
        message = msg;
        LOG.warn("Spotify: {}", msg);
    }

    private void closeServer() {
        ServerSocket s = server;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
    }

    private static void openBrowser(String url) {
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", url).start();
            } else {
                new ProcessBuilder("xdg-open", url).start();
            }
        } catch (IOException e) {
            LOG.warn("Could not open the browser automatically; use 'Copy login link' instead.");
        }
    }

    private String randomUrlSafe(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        String s = base64Url(b);
        return s.length() > bytes ? s.substring(0, bytes) : s;
    }

    private static String base64Url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String query(String... kv) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(kv[i], StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(kv[i + 1], StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.toString();
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null) return out;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            out.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return out;
    }
}
