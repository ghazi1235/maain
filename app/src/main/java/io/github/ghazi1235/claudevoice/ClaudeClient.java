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

    // A key that starts with "gsk_" is a Groq key: the same app, same conversation and same
    // prompt, but routed to Groq's free OpenAI-compatible endpoint instead of Anthropic. Lets
    // MAX run for free on the TV without a rebuild — the person just pastes a different key.
    private static final String GROQ_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions";
    private static final String GROQ_MODEL = "llama-3.3-70b-versatile";

    // A "key" that is itself an http(s) URL points MAX at any OpenAI-compatible endpoint with no
    // auth at all — a free keyless service, or a local Ollama on the LAN. An optional "||model"
    // suffix picks the model. This is how MAX runs free with no account, and how its brain can
    // later move onto the always-on center PC by injecting a different URL — no rebuild.
    private static final String DEFAULT_OPENAI_MODEL = "openai";

    // A key starting "AIza" or "AQ." is a Google Gemini key (free tier), called through Gemini's
    // OpenAI-compatible endpoint with Bearer auth. gemini flash is fast, free and multilingual.
    private static final String GEMINI_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions";
    private static final String GEMINI_MODEL = "gemini-3.6-flash";

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
        synchronized (history) {
            history.clear();
        }
    }

    /** Sends one user turn and returns Claude's text. Throws on any transport or API error. */
    String ask(String userText) throws Exception {
        synchronized (history) {
            history.add(message("user", userText));
            trimHistory();
        }
        boolean ok = false;
        try {
            // The key decides the backend:
            //   starts "http"          -> any OpenAI-compatible URL, no auth (keyless / local Ollama)
            //   starts "gsk_"          -> Groq's free OpenAI-compatible endpoint
            //   starts "AIza" / "AQ."  -> Google Gemini (free tier), OpenAI-compatible endpoint
            //   otherwise              -> the Anthropic Messages API
            // Anthropic carries the system prompt in its own field; the OpenAI-style APIs carry it
            // as the first message. None of these run on anyone's PC — the TV calls them directly.
            boolean urlMode = apiKey.startsWith("http");
            boolean groq = apiKey.startsWith("gsk_");
            boolean gemini = apiKey.startsWith("AIza") || apiKey.startsWith("AQ.");
            boolean openai = urlMode || groq || gemini;

            String endpoint;
            String model;
            if (urlMode) {
                int sep = apiKey.indexOf("||");
                endpoint = sep >= 0 ? apiKey.substring(0, sep) : apiKey;
                model = sep >= 0 ? apiKey.substring(sep + 2) : DEFAULT_OPENAI_MODEL;
            } else if (groq) {
                endpoint = GROQ_ENDPOINT;
                model = GROQ_MODEL;
            } else if (gemini) {
                endpoint = GEMINI_ENDPOINT;
                model = GEMINI_MODEL;
            } else {
                endpoint = ENDPOINT;
                model = MODEL;
            }

            // Snapshot the conversation under the lock so a concurrent reset() (new conversation on
            // the UI thread) can't mutate the list while we iterate it to build the request.
            List<JSONObject> snapshot;
            synchronized (history) {
                snapshot = new ArrayList<>(history);
            }

            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("max_tokens", 512);
            if (openai) {
                JSONArray msgs = new JSONArray();
                msgs.put(new JSONObject().put("role", "system").put("content", SYSTEM_PROMPT));
                for (JSONObject m : snapshot) {
                    msgs.put(m);
                }
                body.put("messages", msgs);
            } else {
                body.put("system", SYSTEM_PROMPT);
                body.put("messages", new JSONArray(snapshot));
            }
            if (gemini) {
                // Gemini flash "thinks" by default, which spends the whole token budget and returns
                // an empty answer. A little reasoning keeps quality but leaves room for the reply.
                body.put("reasoning_effort", "low");
            }

            HttpURLConnection c = (HttpURLConnection) new URL(endpoint).openConnection();
            try {
                c.setRequestMethod("POST");
                c.setRequestProperty("content-type", "application/json");
                if (groq || gemini) {
                    c.setRequestProperty("authorization", "Bearer " + apiKey);
                } else if (!urlMode) {
                    c.setRequestProperty("x-api-key", apiKey);
                    c.setRequestProperty("anthropic-version", API_VERSION);
                }
                c.setConnectTimeout(10000);
                c.setReadTimeout(30000);
                c.setDoOutput(true);

                try (OutputStream os = c.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }

                int status = c.getResponseCode();
                String response = read(status >= 400 ? c.getErrorStream() : c.getInputStream());

                if (status >= 400) {
                    throw new ApiException(status, describe(status, response));
                }

                String reply = openai
                        ? firstChoice(new JSONObject(response))
                        : firstText(new JSONObject(response));
                synchronized (history) {
                    history.add(message("assistant", reply));
                }
                ok = true;
                return reply;
            } finally {
                c.disconnect();
            }
        } finally {
            if (!ok) {
                // Roll back the just-added user turn on ANY failure (HTTP error, timeout, or any
                // transport exception), so a dangling user message can never corrupt role
                // alternation and make every following request fail until "new conversation".
                synchronized (history) {
                    if (!history.isEmpty()
                            && "user".equals(history.get(history.size() - 1).optString("role"))) {
                        history.remove(history.size() - 1);
                    }
                }
            }
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

    /** Pulls the reply out of an OpenAI-style choices[0].message.content (Groq). */
    private static String firstChoice(JSONObject response) throws Exception {
        JSONArray choices = response.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new Exception("no choices in response");
        }
        JSONObject message = choices.getJSONObject(0).optJSONObject("message");
        String text = message == null ? "" : message.optString("content", "").trim();
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
            case 503:
            case 529:
                return "The AI is busy right now. Try again in a moment.";
            default:
                if (apiMessage != null && !apiMessage.isEmpty()) {
                    return apiMessage;
                }
                Log.w(TAG, "HTTP " + status + ": " + response);
                return "The request failed with status " + status + ".";
        }
    }

    // Caller holds the history lock.
    private void trimHistory() {
        while (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
        // Role alternation requires the first message to be "user"; an assistant-first history is
        // rejected by the Anthropic API. If trimming from the front left an assistant in first
        // position, drop it too so the next request stays valid.
        while (!history.isEmpty() && !"user".equals(history.get(0).optString("role"))) {
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
