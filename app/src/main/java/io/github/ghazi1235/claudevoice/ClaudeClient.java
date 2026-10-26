package io.github.ghazi1235.claudevoice;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal Claude Messages API client. One blocking call per turn, run off the main thread by
 * the caller. Keeps the running conversation so follow-ups ("and the one after that?") work.
 */
class ClaudeClient {

    private static final String TAG = "ClaudeVoice";
    private static final String ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final String MODEL = "claude-sonnet-5-5";
    private static final String API_VERSION = "2023-06-01";

    /** Replies are spoken aloud, so they have to stay short and free of markup. */
    private static final String SYSTEM_PROMPT =
            "You are a voice assistant on a television in a living room in Tunisia. Your reply "
                    + "is read aloud by a text-to-speech engine, so answer in one or two short "
                    + "sentences of plain spoken language. Never use markdown, lists, code "
                    + "blocks, emoji or special characters. If a question needs a long answer, "
                    + "give the short version and offer to go deeper.\n\n"
                    + "Language: reply in the same language the person used. They speak "
                    + "Tunisian Derja and French, and mix the two in one sentence as Tunisians "
                    + "normally do; follow their mix rather than correcting it. Write Derja in "
                    + "Arabic script, never in Latin letters or numerals (not 3, 7 or 9), "
                    + "because the speech engine reads Arabic script and would mispronounce "
                    + "transliteration. Keep French words that Tunisians normally say in French "
                    + "(for example portable, voiture, rendez-vous) in French. If you cannot "
                    + "tell which language was used, answer in Derja.";

    /** How many past turns to resend. Keeps latency and token use bounded on a TV. */
    private static final int MAX_HISTORY = 12;

    private final String apiKey;
    private final List<JSONObject> history = new ArrayList<>();

    ClaudeClient(String apiKey) {
        this.apiKey = apiKey;
    }

    void reset() {
        history.clear();
    }

    /** Sends one user turn and returns Claude's text. Throws on any transport or API error. */
    String ask(String userText) throws Exception {
        history.add(message("user", userText));
        trimHistory();

        JSONObject body = new JSONObject();
        body.put("model", MODEL);
        body.put("max_tokens", 512);
        body.put("system", SYSTEM_PROMPT);
        body.put("messages", new JSONArray(history));

        HttpURLConnection c = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setRequestProperty("content-type", "application/json");
            c.setRequestProperty("x-api-key", apiKey);
            c.setRequestProperty("anthropic-version", API_VERSION);
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.setDoOutput(true);

            try (OutputStream os = c.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int status = c.getResponseCode();
            String response = read(status >= 400 ? c.getErrorStream() : c.getInputStream());

            if (status >= 400) {
                // Drop the turn that failed so the next question starts from a clean history.
                history.remove(history.size() - 1);
                throw new ApiException(status, describe(status, response));
            }

            String reply = firstText(new JSONObject(response));
            history.add(message("assistant", reply));
            return reply;
        } finally {
            c.disconnect();
        }
    }

    /** Pulls the text out of content[], which may hold several blocks. */
    private static String firstText(JSONObject response) throws Exception {
        JSONArray content = response.optJSONArray("content");
        if (content == null) {
            throw new Exception("no content in response");
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            JSONObject block = content.optJSONObject(i);
            if (block != null && "text".equals(block.optString("type"))) {
                sb.append(block.optString("text"));
            }
        }
        String text = sb.toString().trim();
        if (text.isEmpty()) {
            throw new Exception("empty reply");
        }
        return text;
    }

    /** Turns an API error into something worth saying out loud. */
    private static String describe(int status, String response) {
        String apiMessage = null;
        try {
            JSONObject error = new JSONObject(response).optJSONObject("error");
            if (error != null) {
                apiMessage = error.optString("message", null);
            }
        } catch (Exception ignored) {
            // Not JSON (a proxy or gateway page); fall through to the status-based text.
        }
        switch (status) {
            case 401:
                return "The API key was rejected.";
            case 429:
                return "Rate limited. Try again in a moment.";
            case 529:
                return "Claude is overloaded. Try again in a moment.";
            default:
                if (apiMessage != null && !apiMessage.isEmpty()) {
                    return apiMessage;
                }
                Log.w(TAG, "HTTP " + status + ": " + response);
                return "The request failed with status " + status + ".";
        }
    }

    private void trimHistory() {
        while (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
    }

    private static JSONObject message(String role, String text) throws Exception {
        return new JSONObject().put("role", role).put("content", text);
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    static class ApiException extends Exception {
        final int status;

        ApiException(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
