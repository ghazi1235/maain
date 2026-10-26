package io.github.ghazi1235.claudevoice;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Claude as a voice assistant on an Android TV.
 *
 * Press OK (or the remote's mic key) to speak. The phrase goes through Android's
 * SpeechRecognizer, the text goes to the Claude Messages API, and the reply is spoken
 * back with TextToSpeech.
 *
 * The API key is not typed on a TV remote. Set it over adb:
 *   adb shell am start -n io.github.ghazi1235.claudevoice/.MainActivity --es api_key sk-ant-...
 */
public class MainActivity extends android.app.Activity {

    private static final String TAG = "ClaudeVoice";
    private static final String PREFS = "claude_voice";
    private static final String KEY_API = "api_key";
    private static final int REQ_MIC = 1;

    private TextView state;
    private TextView heard;
    private TextView reply;

    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private ClaudeClient claude;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private boolean busy;
    private boolean ttsReady;

    /**
     * What the person speaks into the remote. Google's recognizer has no Derja model, so
     * Arabic (Tunisia) is the closest it offers and it handles Derja reasonably; French is
     * exact. ◀ and ▶ switch between them, because a recognizer told the wrong language
     * returns confident nonsense rather than an error.
     */
    private static final Locale[] INPUT_LANGS = {
            new Locale("ar", "TN"),
            Locale.FRANCE,
    };
    private static final String[] INPUT_LABELS = {"الدارجة", "Français"};
    private static final String KEY_LANG = "input_lang";

    private int lang;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_main);
        state = findViewById(R.id.state);
        heard = findViewById(R.id.heard);
        reply = findViewById(R.id.reply);

        storeKeyFrom(getIntent());
        lang = prefs().getInt(KEY_LANG, 0);

        tts = new TextToSpeech(this, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (!ttsReady) {
                Log.w(TAG, "text to speech unavailable; replies will be on screen only");
            }
            // The language is chosen per reply in speak(), not once here: a Derja answer and a
            // French answer need different voices, and the person switches between them freely.
        });

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }

        refreshIdleHint();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        storeKeyFrom(intent);
        refreshIdleHint();
    }

    /** Accepts --es api_key from adb so the key never has to be typed with a remote. */
    private void storeKeyFrom(Intent intent) {
        if (intent == null) {
            return;
        }
        String key = intent.getStringExtra(KEY_API);
        if (TextUtils.isEmpty(key)) {
            return;
        }
        prefs().edit().putString(KEY_API, key.trim()).apply();
        claude = null;  // rebuilt lazily with the new key
        // Clear it off the Intent so a later onNewIntent cannot resend a stale key.
        intent.removeExtra(KEY_API);
        Log.i(TAG, "api key stored");
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private String apiKey() {
        return prefs().getString(KEY_API, "");
    }

    private void refreshIdleHint() {
        if (TextUtils.isEmpty(apiKey())) {
            state.setText(R.string.hint_no_key);
            return;
        }
        state.setText(getString(R.string.hint_idle) + "  ·  " + INPUT_LABELS[lang]);
    }

    private void cycleLanguage(int delta) {
        lang = Math.floorMod(lang + delta, INPUT_LANGS.length);
        prefs().edit().putInt(KEY_LANG, lang).apply();
        refreshIdleHint();
    }

    /**
     * Picks the voice for one reply from the script Claude answered in, rather than from the
     * input language: a question asked in French can be answered in Derja and the other way
     * round, and a wrong voice makes the reply unintelligible. Arabic script wins whenever it
     * appears, because a Derja sentence normally carries French words inside it.
     */
    private static Locale voiceFor(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.UnicodeBlock.of(text.charAt(i))
                    == Character.UnicodeBlock.ARABIC) {
                return new Locale("ar", "TN");
            }
        }
        return Locale.FRANCE;
    }

    /**
     * Applies a voice, falling back from Arabic (Tunisia) to plain Arabic. No Android TTS
     * engine ships a Derja voice; Modern Standard Arabic reads Derja approximately, which is
     * the best available on this TV.
     */
    private boolean applyVoice(Locale wanted) {
        int result = tts.setLanguage(wanted);
        if (result != TextToSpeech.LANG_MISSING_DATA
                && result != TextToSpeech.LANG_NOT_SUPPORTED) {
            return true;
        }
        if ("ar".equals(wanted.getLanguage())) {
            result = tts.setLanguage(new Locale("ar"));
            return result != TextToSpeech.LANG_MISSING_DATA
                    && result != TextToSpeech.LANG_NOT_SUPPORTED;
        }
        return false;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_SEARCH:
            case KeyEvent.KEYCODE_VOICE_ASSIST:
                startListening();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                cycleLanguage(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                cycleLanguage(1);
                return true;
            case KeyEvent.KEYCODE_MEDIA_STOP:
                newConversation();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    private void newConversation() {
        if (claude != null) {
            claude.reset();
        }
        heard.setText("");
        reply.setText("");
        refreshIdleHint();
    }

    private void startListening() {
        if (busy) {
            return;
        }
        if (TextUtils.isEmpty(apiKey())) {
            state.setText(R.string.hint_no_key);
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            // Happens when the Google app is disabled. The debloat list keeps it, but a
            // heavier list elsewhere may not.
            show("No speech recognizer on this TV. Google app may be disabled.");
            return;
        }

        if (tts != null) {
            tts.stop();
        }
        busy = true;
        heard.setText("");
        state.setText(R.string.state_listening);

        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new Listener());
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        // Without this the recognizer uses the TV's system language, which on a set sold in
        // India is English, and it then hears Derja as garbled English.
        String tag = INPUT_LANGS[lang].toLanguageTag();
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag);
        recognizer.startListening(intent);
    }

    private void onHeard(String text) {
        heard.setText('"' + text + '"');
        state.setText(R.string.state_thinking);

        io.execute(() -> {
            String answer;
            try {
                if (claude == null) {
                    claude = new ClaudeClient(apiKey());
                }
                answer = claude.ask(text);
            } catch (ClaudeClient.ApiException e) {
                answer = e.getMessage();
            } catch (Exception e) {
                Log.w(TAG, "request failed", e);
                answer = "I could not reach Claude. Check the TV's internet connection.";
            }
            String spoken = answer;
            ui.post(() -> speak(spoken));
        });
    }

    private void speak(String text) {
        reply.setText(text);
        busy = false;
        if (!ttsReady) {
            refreshIdleHint();
            return;
        }
        state.setText(R.string.state_speaking);
        if (!applyVoice(voiceFor(text))) {
            // The reply still shows on screen; only the voice is missing.
            reply.setText(text + "\n\n" + getString(R.string.no_voice));
            refreshIdleHint();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply");
        }
        // No utterance callback: the hint goes back to idle once speech has had time to start,
        // which is all the screen needs to say.
        ui.postDelayed(this::refreshIdleHint, 1200);
    }

    private void show(String message) {
        busy = false;
        state.setText(R.string.hint_idle);
        reply.setText(message);
    }

    private class Listener implements RecognitionListener {
        @Override
        public void onResults(Bundle results) {
            ArrayList<String> matches =
                    results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            release();
            if (matches == null || matches.isEmpty() || matches.get(0).trim().isEmpty()) {
                show("I did not catch that.");
                return;
            }
            onHeard(matches.get(0));
        }

        @Override
        public void onPartialResults(Bundle partial) {
            ArrayList<String> matches =
                    partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (matches != null && !matches.isEmpty()) {
                heard.setText(matches.get(0));
            }
        }

        @Override
        public void onError(int error) {
            release();
            show(errorText(error));
        }

        private void release() {
            if (recognizer != null) {
                recognizer.destroy();
                recognizer = null;
            }
        }

        @Override public void onReadyForSpeech(Bundle params) { }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float rms) { }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { state.setText(R.string.state_thinking); }
        @Override public void onEvent(int type, Bundle params) { }
    }

    private static String errorText(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "I did not catch that.";
            case SpeechRecognizer.ERROR_AUDIO:
                return "No microphone. On this TV the mic is in the remote, so pair it first.";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "Microphone permission was refused.";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "Speech recognition could not reach the network.";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "The recognizer is busy. Try again.";
            default:
                return "Speech recognition failed (code " + error + ").";
        }
    }

    @Override
    protected void onDestroy() {
        if (recognizer != null) {
            recognizer.destroy();
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        io.shutdownNow();
        super.onDestroy();
    }
}
