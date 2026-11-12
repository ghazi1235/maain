package io.github.ghazi1235.claudevoice;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PorterDuff;
import android.widget.ImageView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Claude as a voice assistant on an Android TV.
 *
 * Press OK (or the remote's mic key) to speak. The phrase goes through Android's
 * SpeechRecognizer, the text goes to the Claude Messages API, and the reply is spoken
 * back with TextToSpeech.
 *
 * Remote map: OK speak · ‹ › language · ■ new conversation · YELLOW (or INFO, or MENU where the
 * firmware lets it through; see MENU_KEY) opens the on-screen menu (Cameras · Language · New
 * conversation · Status) · RED jumps straight to the camera wall.
 *
 * The API key is not typed on a TV remote. Set it over adb:
 *   adb shell am start -n io.github.ghazi1235.claudevoice/.MainActivity --es api_key sk-ant-...
 */
public class MainActivity extends android.app.Activity {

    private static final String TAG = "ClaudeVoice";
    private static final String PREFS = "claude_voice";
    private static final String KEY_API = "api_key";
    private static final String KEY_CAM_HOST = "cam_host";
    private static final int REQ_MIC = 1;

    // Punica gives MAX a real Tunisian voice. The /audio/speech endpoint on the "dido" model
    // returns a WAV (PCM 16-bit mono 24 kHz) for the text; nour_tn is the default voice. The key
    // and voice arrive over adb like the API key and live in the same prefs.
    private static final String KEY_PUNICA = "punica_key";
    private static final String KEY_PUNICA_VOICE = "punica_voice";
    private static final String KEY_SAY = "say";
    private static final String PUNICA_URL = "https://punica.tn/api/v1/audio/speech";
    private static final String PUNICA_MODEL = "dido";
    private static final String PUNICA_DEFAULT_VOICE = "nour_tn";

    /**
     * Keys that open the on-screen menu besides KEYCODE_MENU. This Toshiba's firmware takes the
     * physical MENU button for its own Picture/Sound menu (TVFunctionMenu) before any app sees it
     * (SETTINGS and BUTTON_MODE likewise), so keys that do reach the app are bound as well: the
     * YELLOW colour key, shown in the legend as a yellow dot, and INFO for remotes that have one.
     * Both verified over adb with `input keyevent 185` / `165` while MAX was in front. Note this
     * TV's KeyEvent.keyCodeToString() labels are off by one from the real values (a vendor keycode
     * was slotted into the native label table), so trust the raw number the key log prints, not
     * the name.
     */
    private static final int MENU_KEY = KeyEvent.KEYCODE_PROG_YELLOW;
    private static final int MENU_KEY_ALT = KeyEvent.KEYCODE_INFO;
    /** What the key cap shows for MENU_KEY: the yellow dot, coloured @color/acid in the layouts. */
    private static final String MENU_KEY_LABEL = "●";

    private TextView state;
    private TextView heard;
    private TextView reply;

    // Cosmetic layer: the language chip, the animated core, the luminous floor and the idle
    // screensaver. None of it feeds back into the flow.
    private TextView langLabel;
    private OrbFx orbFx;
    private NeonGridView backdrop;
    private View content;
    private Screensaver saver;
    private MaxMenu menu;

    /** The screensaver fades in after this long without a key press or a spoken exchange. */
    private static final long IDLE_MS = 30_000L;
    private final Runnable idleTick = () -> {
        if (!saver.show()) {
            armIdle();  // MAX was busy; try again later
        }
    };

    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private volatile ClaudeClient claude;

    // Punica TTS playback. The clip is raw PCM, played through AudioTrack rather than MediaPlayer:
    // this panel's MediaPlayer/NuPlayer layer pauses the stream a few ms after start() (even with
    // audio focus held), so the low-level AudioTrack path — which this code drives directly — is
    // the one that actually sounds. `audioTrack` holds the clip now speaking; `speakGen` is bumped
    // on every new reply and every new listen, so a fetch that returns late never plays over a
    // newer state; `focusRequest` is the media audio focus held for the duration of a clip.
    private AudioTrack audioTrack;
    private int speakGen;
    private AudioFocusRequest focusRequest;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    // Separate worker for the screensaver's paintings + news + weather, so their network I/O never
    // queues behind (or delays) a voice request on `io`.
    private final ExecutorService saverIo = Executors.newSingleThreadExecutor();
    // The Punica text-to-speech fetch gets its own worker: a slow/hung voice fetch must never
    // queue behind (and delay) the next question's AI call. They used to share `io`.
    private final ExecutorService ttsIo = Executors.newSingleThreadExecutor();
    // Set in onDestroy. A posted callback that slips through teardown must not submit work to a
    // shut-down executor (the RejectedExecutionException crash) or touch a destroyed view.
    private volatile boolean destroyed;
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

        langLabel = findViewById(R.id.langLabel);
        content = findViewById(R.id.content);
        // With animations switched off in the TV's developer settings every state is a still.
        boolean animate = Settings.Global.getFloat(getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
        orbFx = new OrbFx(findViewById(R.id.orb), findViewById(R.id.orbHot),
                findViewById(R.id.orbGlow), findViewById(R.id.orbGlowHot),
                findViewById(R.id.orbHalo), findViewById(R.id.orbRing),
                findViewById(R.id.orbitA), findViewById(R.id.orbitB),
                findViewById(R.id.orbitTiltA), findViewById(R.id.led), animate);
        backdrop = findViewById(R.id.backdrop);
        backdrop.setAnimated(animate);
        saver = new Screensaver(findViewById(R.id.screensaver), animate);
        menu = new MaxMenu(findViewById(R.id.menu), animate);
        ((TextView) findViewById(R.id.legendMenuKey)).setText(MENU_KEY_LABEL);

        storeKeyFrom(getIntent());
        storeCamHostFrom(getIntent());
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
        maybeSay(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        storeKeyFrom(intent);
        storeCamHostFrom(intent);
        refreshIdleHint();
        maybeSay(intent);
    }

    /**
     * Accepts --es api_key / --es punica_key / --es punica_voice from adb, so none of them has to
     * be typed with a remote. Each is stored only when present and stripped off the Intent after,
     * so a later onNewIntent cannot resend a stale value.
     */
    private void storeKeyFrom(Intent intent) {
        if (intent == null) {
            return;
        }
        String key = intent.getStringExtra(KEY_API);
        if (!TextUtils.isEmpty(key)) {
            prefs().edit().putString(KEY_API, key.trim()).apply();
            claude = null;  // rebuilt lazily with the new key
            intent.removeExtra(KEY_API);
            Log.i(TAG, "api key stored");
        }
        String punicaKey = intent.getStringExtra(KEY_PUNICA);
        if (!TextUtils.isEmpty(punicaKey)) {
            prefs().edit().putString(KEY_PUNICA, punicaKey.trim()).apply();
            intent.removeExtra(KEY_PUNICA);
            Log.i(TAG, "punica key stored");
        }
        String punicaVoice = intent.getStringExtra(KEY_PUNICA_VOICE);
        if (!TextUtils.isEmpty(punicaVoice)) {
            prefs().edit().putString(KEY_PUNICA_VOICE, punicaVoice.trim()).apply();
            intent.removeExtra(KEY_PUNICA_VOICE);
            Log.i(TAG, "punica voice stored");
        }
    }

    /**
     * Test hook: if the launch Intent carries --es say "<text>", MAX speaks it once through the
     * same speak() path a real reply takes, so the voice can be tried without the microphone. The
     * extra is removed after, so a later onNewIntent cannot replay it.
     */
    private void maybeSay(Intent intent) {
        if (intent == null) {
            return;
        }
        String say = intent.getStringExtra(KEY_SAY);
        if (TextUtils.isEmpty(say)) {
            return;
        }
        intent.removeExtra(KEY_SAY);
        final String text = say;
        ui.post(() -> speak(text));
    }

    /**
     * Accepts --es cam_host from adb and stores it for the camera wall. The launcher Intent for
     * MainActivity carries it (that is where the TV is pointed at the center PC); WallActivity then
     * reads it from the same prefs. Default lives in WallActivity.
     */
    private void storeCamHostFrom(Intent intent) {
        if (intent == null) {
            return;
        }
        String host = intent.getStringExtra(KEY_CAM_HOST);
        if (TextUtils.isEmpty(host)) {
            return;
        }
        prefs().edit().putString(KEY_CAM_HOST, host.trim()).apply();
        intent.removeExtra(KEY_CAM_HOST);
        Log.i(TAG, "cam host stored");
    }

    /**
     * Opens the full-screen camera wall; BACK there returns to the voice screen. Reached from the
     * menu's Cameras item, or directly with the RED key.
     */
    private void openWall() {
        startActivity(new Intent(this, WallActivity.class));
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private String apiKey() {
        return prefs().getString(KEY_API, "");
    }

    private void refreshIdleHint() {
        // The input language lives in its own chip now, so the hint line stays a plain hint.
        langLabel.setText(INPUT_LABELS[lang]);
        orbFx.mode(OrbFx.IDLE);
        if (TextUtils.isEmpty(apiKey())) {
            state.setText(R.string.hint_no_key);
            return;
        }
        state.setText(R.string.hint_idle);
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

    /**
     * Every key first goes past the screensaver. While it shows, the key only wakes the screen
     * (and its release is swallowed too, so waking never also starts listening). Then the menu:
     * while it is open it owns the key (▲▼ OK BACK), so nothing reaches onKeyDown. Otherwise the
     * key just pushes the idle timer back and reaches onKeyDown as usual.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (saver.intercept(event)) {
            armIdle();
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            armIdle();
            if (event.getRepeatCount() == 0) {
                // Which keycode each remote button sends is this TV's secret (its keylayouts are
                // not readable); this line is how a new button gets mapped. Raw number only: the
                // TV's keyCodeToString() names are off by one (see MENU_KEY).
                Log.d(TAG, "key " + event.getKeyCode());
            }
        }
        if (menu.intercept(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void armIdle() {
        ui.removeCallbacks(idleTick);
        ui.postDelayed(idleTick, IDLE_MS);
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
            // The menu key opens the on-screen menu (Cameras live there now). It never starts
            // listening (that is OK / mic only). While the screensaver shows, dispatchKeyEvent has
            // already swallowed the key to wake it, so it cannot also open the menu.
            case KeyEvent.KEYCODE_MENU:
            case MENU_KEY:
            case MENU_KEY_ALT:
                menu.open();
                return true;
            // The one direct shortcut kept: RED still jumps straight to the camera wall.
            case KeyEvent.KEYCODE_PROG_RED:
                openWall();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    private void newConversation() {
        speakGen++;        // a reply still in flight is now stale: it must neither show nor speak
        busy = false;      // and never leave the screen wedged in "thinking"
        stopPlayback();
        ClaudeClient c = claude;
        if (c != null) {
            c.reset();
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
        stopPlayback();  // a new listen interrupts any Punica clip, as tts.stop() does the fallback
        speakGen++;      // and invalidates a Punica fetch that may still be in flight
        busy = true;
        armIdle();
        heard.setText("");
        state.setText(R.string.state_listening);
        orbFx.mode(OrbFx.LISTENING);

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

    /**
     * Submits a task to a background worker, tolerating the teardown race where the executor is
     * already shut down (returns false instead of throwing RejectedExecutionException).
     */
    private boolean submit(ExecutorService ex, Runnable task) {
        if (destroyed) {
            return false;
        }
        try {
            ex.execute(task);
            return true;
        } catch (java.util.concurrent.RejectedExecutionException e) {
            return false;
        }
    }

    private void onHeard(String text) {
        armIdle();
        heard.setText('"' + text + '"');
        state.setText(R.string.state_thinking);
        orbFx.mode(OrbFx.THINKING);

        final int gen = speakGen;   // a new listen / new conversation bumps this -> a stale answer is dropped
        submit(io, () -> {
            String answer;
            try {
                ClaudeClient c = claude;
                if (c == null) {
                    c = new ClaudeClient(apiKey());
                    claude = c;
                }
                answer = c.ask(text);
            } catch (ClaudeClient.ApiException e) {
                answer = e.getMessage();
            } catch (Exception e) {
                Log.w(TAG, "request failed", e);
                answer = "I could not reach Claude. Check the TV's internet connection.";
            }
            final String spoken = answer;
            ui.post(() -> {
                if (gen == speakGen && !isFinishing() && !destroyed) {
                    speak(spoken);   // still the current turn: show + speak it
                }
            });
        });
    }

    /**
     * Speaks one reply. Punica TTS is the real Tunisian voice and comes first: its request runs
     * off the main thread and the returned WAV is played with AudioTrack. Android TextToSpeech is
     * the fallback, used whenever no Punica key is set or the fetch fails for any reason, so a
     * reply is never left unspoken — and never spoken twice (a Punica success returns before the
     * fallback can run).
     */
    private void speak(String text) {
        armIdle();  // the screensaver waits a full idle period after the reply starts
        reply.setText(text);
        busy = false;
        final int gen = ++speakGen;  // any older in-flight Punica fetch / clip is now stale
        final String key = punicaKey();
        if (TextUtils.isEmpty(key)) {
            speakAndroid(text);  // no Punica configured: straight to the Android voice
            return;
        }
        final String voice = punicaVoice();
        submit(ttsIo, () -> {
            final byte[] wav = fetchPunica(text, key, voice);   // held in memory, no temp file
            ui.post(() -> {
                if (gen != speakGen || isFinishing() || destroyed) {
                    return;  // a newer reply / new listen / teardown superseded this one; drop it
                }
                if (wav != null) {
                    // The real voice is ready: show the speaking state, then play it.
                    state.setText(R.string.state_speaking);
                    orbFx.mode(OrbFx.SPEAKING);
                    if (playPunica(wav, gen)) {
                        return;  // Punica is speaking; never also run Android TTS
                    }
                }
                speakAndroid(text);  // Punica fetch (or playback setup) failed: fall back
            });
        });
    }

    /** The Android TextToSpeech path — the original voice, now the fallback behind Punica. */
    private void speakAndroid(String text) {
        if (!ttsReady) {
            refreshIdleHint();
            return;
        }
        state.setText(R.string.state_speaking);
        orbFx.mode(OrbFx.SPEAKING);
        if (!applyVoice(voiceFor(text))) {
            // The reply still shows on screen; only the voice is missing.
            reply.setText(text + "\n\n" + getString(R.string.no_voice));
            refreshIdleHint();
            return;
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply");
        // No utterance callback: the hint goes back to idle once speech has had time to start,
        // which is all the screen needs to say.
        ui.postDelayed(this::refreshIdleHint, 1200);
    }

    private String punicaKey() {
        return prefs().getString(KEY_PUNICA, "");
    }

    private String punicaVoice() {
        String v = prefs().getString(KEY_PUNICA_VOICE, "");
        return TextUtils.isEmpty(v) ? PUNICA_DEFAULT_VOICE : v;
    }

    /**
     * Calls Punica's /audio/speech off the main thread and writes the returned WAV to
     * getCacheDir()/max_tts.wav. Returns the file on HTTP 200 with a non-empty body, or null on
     * any failure (HTTP error, timeout, exception) so speak() falls back to Android TextToSpeech.
     */
    private byte[] fetchPunica(String text, String key, String voice) {
        HttpURLConnection c = null;
        try {
            JSONObject body = new JSONObject();
            body.put("model", PUNICA_MODEL);
            body.put("input", text);
            body.put("voice", voice);
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);

            c = (HttpURLConnection) new URL(PUNICA_URL).openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Authorization", "Bearer " + key);
            c.setRequestProperty("content-type", "application/json");
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setDoOutput(true);
            try (OutputStream os = c.getOutputStream()) {
                os.write(payload);
            }

            int status = c.getResponseCode();
            if (status != 200) {
                Log.w(TAG, "Punica TTS HTTP " + status + "; falling back to Android TTS");
                return null;
            }
            byte[] wav = readBytes(c.getInputStream(), 16 * 1024 * 1024);
            if (wav == null || wav.length == 0) {
                Log.w(TAG, "Punica TTS returned an empty body; falling back to Android TTS");
                return null;
            }
            Log.i(TAG, "Punica TTS ok: HTTP 200, " + wav.length + " WAV bytes (in memory)");
            return wav;
        } catch (Exception e) {
            Log.w(TAG, "Punica TTS request failed; falling back to Android TTS", e);
            return null;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /**
     * Plays a fetched Punica WAV through AudioTrack (the clip is raw PCM, so no decoder is needed).
     * Returns true once playback has started, so speak() knows not to also run Android TTS; the UI
     * returns to idle when the clip reaches its end marker. Any setup failure returns false, which
     * sends speak() to the Android TextToSpeech fallback.
     */
    private boolean playPunica(byte[] bytes, final int gen) {
        stopPlayback();  // release any clip (and its focus) still held from a previous reply
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        AudioTrack track = null;
        try {
            Wav pcm = parseWav(bytes);
            if (pcm == null) {
                Log.w(TAG, "Punica WAV could not be parsed; falling back to Android TTS");
                return false;
            }
            int channelMask = pcm.channels >= 2
                    ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
            track = new AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(pcm.sampleRate)
                            .setChannelMask(channelMask)
                            .build())
                    .setBufferSizeInBytes(pcm.length)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
            int wrote = track.write(pcm.data, pcm.offset, pcm.length);
            if (wrote < pcm.length) {
                Log.w(TAG, "AudioTrack wrote " + wrote + "/" + pcm.length
                        + " bytes; falling back to Android TTS");
                track.release();
                return false;
            }
            final int frames = pcm.length / (2 * pcm.channels);
            final AudioTrack t = track;
            track.setNotificationMarkerPosition(frames);
            track.setPlaybackPositionUpdateListener(
                    new AudioTrack.OnPlaybackPositionUpdateListener() {
                        @Override public void onMarkerReached(AudioTrack who) {
                            Log.i(TAG, "Punica playback complete");
                            // Tear down only this clip; a marker from a superseded one must not
                            // touch the clip now speaking.
                            if (audioTrack == t) {
                                stopPlayback();
                            }
                            if (gen == speakGen) {
                                refreshIdleHint();
                            }
                        }
                        @Override public void onPeriodicNotification(AudioTrack who) { }
                    }, ui);
            requestAudioFocus(attrs);  // hold focus for the clip; this TV enforces it
            track.play();
            audioTrack = track;
            armIdle();  // keep the screensaver back while the real voice is talking
            Log.i(TAG, "Punica AudioTrack started: " + frames + " frames @ "
                    + pcm.sampleRate + " Hz, " + pcm.channels + " ch");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Punica AudioTrack failed to start; falling back to Android TTS", e);
            if (track != null) {
                try {
                    track.release();
                } catch (Exception ignored) {
                }
            }
            abandonAudioFocus();
            return false;
        }
    }

    /** Takes transient media audio focus for the clip about to play. */
    private void requestAudioFocus(AudioAttributes attrs) {
        abandonAudioFocus();
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            return;
        }
        AudioFocusRequest req = new AudioFocusRequest.Builder(
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                // The short reply plays through even as the TV's assistant grabs focus back, so a
                // focus change must not tear the clip down: the listener deliberately does nothing.
                .setOnAudioFocusChangeListener(change -> { })
                .build();
        am.requestAudioFocus(req);
        focusRequest = req;
        Log.i(TAG, "media audio focus requested");
    }

    /** Releases any media audio focus previously taken. Safe to call when none is held. */
    private void abandonAudioFocus() {
        if (focusRequest == null) {
            return;
        }
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            am.abandonAudioFocusRequest(focusRequest);
        }
        focusRequest = null;
    }

    /** Stops and releases the Punica clip (and its audio focus) if one is playing. */
    private void stopPlayback() {
        if (audioTrack != null) {
            try {
                audioTrack.pause();
            } catch (Exception ignored) {
            }
            try {
                audioTrack.flush();
            } catch (Exception ignored) {
            }
            try {
                audioTrack.stop();
            } catch (Exception ignored) {
            }
            try {
                audioTrack.release();
            } catch (Exception ignored) {
            }
            audioTrack = null;
        }
        abandonAudioFocus();
    }

    /** A parsed PCM WAV: where the samples sit inside the file bytes, and how to play them. */
    private static final class Wav {
        final byte[] data;
        final int offset;
        final int length;
        final int sampleRate;
        final int channels;

        Wav(byte[] data, int offset, int length, int sampleRate, int channels) {
            this.data = data;
            this.offset = offset;
            this.length = length;
            this.sampleRate = sampleRate;
            this.channels = channels;
        }
    }

    /**
     * Reads a RIFF/PCM WAV header: walks the chunks for `fmt ` (sample rate, channels, bit depth)
     * and `data` (the samples). Returns null for anything that is not 16-bit PCM, since AudioTrack
     * is built for ENCODING_PCM_16BIT. Punica serves PCM 16-bit mono 24 kHz, which this accepts.
     */
    private static Wav parseWav(byte[] b) {
        if (b == null || b.length < 44) {
            return null;
        }
        if (b[0] != 'R' || b[1] != 'I' || b[2] != 'F' || b[3] != 'F'
                || b[8] != 'W' || b[9] != 'A' || b[10] != 'V' || b[11] != 'E') {
            return null;
        }
        int sampleRate = 0, channels = 0, bits = 0, dataOff = -1, dataLen = 0;
        int i = 12;
        while (i + 8 <= b.length) {
            int sz = (b[i + 4] & 0xff) | ((b[i + 5] & 0xff) << 8)
                    | ((b[i + 6] & 0xff) << 16) | ((b[i + 7] & 0xff) << 24);
            int body = i + 8;
            if (b[i] == 'f' && b[i + 1] == 'm' && b[i + 2] == 't' && b[i + 3] == ' '
                    && body + 16 <= b.length) {
                channels = (b[body + 2] & 0xff) | ((b[body + 3] & 0xff) << 8);
                sampleRate = (b[body + 4] & 0xff) | ((b[body + 5] & 0xff) << 8)
                        | ((b[body + 6] & 0xff) << 16) | ((b[body + 7] & 0xff) << 24);
                bits = (b[body + 14] & 0xff) | ((b[body + 15] & 0xff) << 8);
            } else if (b[i] == 'd' && b[i + 1] == 'a' && b[i + 2] == 't' && b[i + 3] == 'a') {
                dataOff = body;
                dataLen = sz;
                break;  // samples run to the end of the file
            }
            if (sz < 0) {
                break;
            }
            i = body + sz + (sz & 1);  // chunks are word-aligned
        }
        if (dataOff < 0 || bits != 16) {
            return null;
        }
        if (channels < 1) {
            channels = 1;
        }
        if (sampleRate <= 0) {
            sampleRate = 24000;
        }
        if (dataLen <= 0 || dataOff + dataLen > b.length) {
            dataLen = b.length - dataOff;  // lenient: play whatever samples are actually present
        }
        dataLen -= dataLen % (2 * channels);  // whole frames only
        if (dataLen <= 0) {
            return null;
        }
        return new Wav(b, dataOff, dataLen, sampleRate, channels);
    }

    private void show(String message) {
        busy = false;
        state.setText(R.string.hint_idle);
        orbFx.mode(OrbFx.IDLE);
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
        @Override public void onRmsChanged(float rms) { orbFx.level(rms); }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() {
            state.setText(R.string.state_thinking);
            orbFx.mode(OrbFx.THINKING);
        }
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
    protected void onResume() {
        super.onResume();
        orbFx.resume();
        backdrop.setRunning(true);
        armIdle();
    }

    @Override
    protected void onPause() {
        // No point breathing while another app is on screen; the TV's GPU is small.
        ui.removeCallbacks(idleTick);
        saver.hide(false);
        menu.close(false, KeyEvent.KEYCODE_UNKNOWN);  // back from the wall, the voice screen shows
        // A listen interrupted by pausing must not leave the mic held or `busy` wedged (OK would
        // then never start a new listen). Release the recognizer and clear the in-flight state.
        if (recognizer != null) {
            try { recognizer.cancel(); recognizer.destroy(); } catch (Exception ignored) { }
            recognizer = null;
        }
        if (busy) {
            busy = false;
            speakGen++;  // drop any reply still in flight so it can't speak over the next screen
        }
        if (tts != null) {
            tts.stop();
        }
        stopPlayback();
        backdrop.setRunning(false);
        orbFx.pause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        ui.removeCallbacksAndMessages(null);  // no posted speak()/refreshIdleHint survives to hit a dead executor
        saver.hide(false);
        backdrop.setRunning(false);
        orbFx.pause();
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
        stopPlayback();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        io.shutdownNow();
        ttsIo.shutdownNow();
        saverIo.shutdownNow();
        super.onDestroy();
    }


    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    /**
     * Drives the energy core from the same state changes that set the text label: a slow magenta
     * breath when idle, a cyan core whose ring swells with the voice while listening, a spinning
     * arc while thinking, a quick magenta pulse while speaking. Two orbitals and the status LED
     * run all the time. Cosmetic only; nothing here reaches recognition, the request, or speech.
     */
    private static final class OrbFx {
        static final int IDLE = 0;
        static final int LISTENING = 1;
        static final int THINKING = 2;
        static final int SPEAKING = 3;

        private static final int CYAN = 0xFF05D9E8;
        private static final int MAGENTA = 0xFFFF2A6D;

        private final View core;      // the magenta core (R.id.orb)
        private final View hot;       // cyan core faded in over it while listening
        private final View glow;      // magenta bloom behind everything
        private final View glowHot;   // cyan bloom faded in while listening
        private final View halo;      // ring that swells with the microphone level
        private final View ring;      // sweep-gradient arc that spins while thinking
        private final View orbitA;    // cyan orbital spark
        private final View orbitB;    // magenta orbital spark
        private final View orbitTilt; // the cyan orbital's tilt, which wobbles slowly
        private final View led;       // status point of light in the top bar
        private final boolean animate;

        private AnimatorSet set;
        private AnimatorSet ambient;
        private int mode = -1;
        private float level;

        OrbFx(View core, View hot, View glow, View glowHot, View halo, View ring,
              View orbitA, View orbitB, View orbitTilt, View led, boolean animate) {
            this.core = core;
            this.hot = hot;
            this.glow = glow;
            this.glowHot = glowHot;
            this.halo = halo;
            this.ring = ring;
            this.orbitA = orbitA;
            this.orbitB = orbitB;
            this.orbitTilt = orbitTilt;
            this.led = led;
            this.animate = animate;
        }

        void mode(int newMode) {
            if (newMode == mode) {
                return;  // refreshIdleHint() is called often; never restart a running loop
            }
            mode = newMode;
            play();
        }

        /** Stops every animator; the views keep their current look. */
        void pause() {
            halo.animate().cancel();
            if (set != null) {
                set.cancel();
                set = null;
            }
            if (ambient != null) {
                ambient.cancel();
                ambient = null;
            }
        }

        void resume() {
            if (set == null && mode >= 0) {
                play();
            }
            if (ambient == null && animate) {
                startAmbient();
            }
        }

        /** The orbitals and the LED: slow, constant, independent of the state. */
        private void startAmbient() {
            List<Animator> anims = new ArrayList<>();
            ObjectAnimator spinA = ObjectAnimator.ofFloat(orbitA, View.ROTATION, 0f, 360f);
            spinA.setDuration(9000);
            spinA.setInterpolator(new LinearInterpolator());
            spinA.setRepeatCount(ValueAnimator.INFINITE);
            spinA.setRepeatMode(ValueAnimator.RESTART);
            anims.add(spinA);
            ObjectAnimator spinB = ObjectAnimator.ofFloat(orbitB, View.ROTATION, 360f, 0f);
            spinB.setDuration(14500);
            spinB.setInterpolator(new LinearInterpolator());
            spinB.setRepeatCount(ValueAnimator.INFINITE);
            spinB.setRepeatMode(ValueAnimator.RESTART);
            anims.add(spinB);
            anims.add(loop(ObjectAnimator.ofFloat(orbitTilt, View.ROTATION, -24f, -13f), 7600));
            anims.add(loop(ObjectAnimator.ofFloat(led, View.ALPHA, 1f, 0.35f), 1700));
            ambient = new AnimatorSet();
            ambient.playTogether(anims);
            ambient.start();
        }

        private void play() {
            halo.animate().cancel();
            if (set != null) {
                set.cancel();
                set = null;
            }
            level = 0f;
            tintHalo(mode == SPEAKING ? MAGENTA : CYAN);
            if (!animate) {
                hot.setAlpha(mode == LISTENING ? 1f : mode == THINKING ? 0.5f : 0f);
                glowHot.setAlpha(mode == LISTENING ? 1f : mode == THINKING ? 0.45f : 0f);
                glow.setAlpha(mode == LISTENING ? 0.2f : mode == IDLE ? 0.6f : 0.95f);
                halo.setAlpha(mode == LISTENING ? 0.6f : 0.18f);
                ring.setAlpha(mode == THINKING ? 1f : 0f);
                return;
            }
            List<Animator> anims = new ArrayList<>();
            switch (mode) {
                case LISTENING:
                    anims.add(fade(hot, 1f));
                    anims.add(fade(glow, 0.2f));
                    anims.add(fade(halo, 0.5f));
                    anims.add(settle(halo, 1f));
                    anims.add(fade(ring, 0f));
                    pulse(anims, core, 1f, 1.06f, 1000);
                    pulse(anims, hot, 1f, 1.06f, 1000);  // in step, so no magenta rim peeks out
                    breathe(anims, glowHot, 0.8f, 1f, 1.02f, 1.12f, 1000);
                    break;
                case THINKING:
                    anims.add(fade(hot, 0.5f));
                    anims.add(fade(glowHot, 0.45f));
                    anims.add(fade(halo, 0.12f));
                    anims.add(settle(halo, 1f));
                    anims.add(fade(ring, 1f));
                    ObjectAnimator spin = ObjectAnimator.ofFloat(ring, View.ROTATION, 0f, 360f);
                    spin.setDuration(1400);
                    spin.setInterpolator(new LinearInterpolator());
                    spin.setRepeatCount(ValueAnimator.INFINITE);
                    spin.setRepeatMode(ValueAnimator.RESTART);
                    anims.add(spin);
                    pulse(anims, core, 1f, 1.025f, 1700);
                    pulse(anims, hot, 1f, 1.025f, 1700);
                    breathe(anims, glow, 0.5f, 0.75f, 1f, 1.05f, 1700);
                    break;
                case SPEAKING:
                    anims.add(fade(hot, 0f));
                    anims.add(fade(glowHot, 0f));
                    anims.add(fade(halo, 0.35f));
                    anims.add(fade(ring, 0f));
                    pulse(anims, core, 1f, 1.07f, 520);
                    pulse(anims, halo, 1f, 1.1f, 520);
                    breathe(anims, glow, 0.8f, 1f, 1f, 1.12f, 520);
                    break;
                default:  // IDLE
                    anims.add(fade(hot, 0f));
                    anims.add(settle(hot, 1f));
                    anims.add(fade(glowHot, 0f));
                    anims.add(fade(halo, 0.18f));
                    anims.add(settle(halo, 1f));
                    anims.add(fade(ring, 0f));
                    pulse(anims, core, 1f, 1.045f, 2800);
                    breathe(anims, glow, 0.55f, 0.85f, 1f, 1.06f, 2800);
                    break;
            }
            set = new AnimatorSet();
            set.playTogether(anims);
            set.start();
        }

        /** The ring is cyan while MAX listens or thinks and magenta while it speaks. */
        private void tintHalo(int color) {
            if (halo.getBackground() != null) {
                halo.getBackground().mutate().setTint(color);
            }
        }

        /** Microphone level while listening: the halo swells with the voice. */
        void level(float rms) {
            if (mode != LISTENING || !animate) {
                return;
            }
            float target = Math.max(0f, Math.min(1f, (rms + 2f) / 12f));
            level += (target - level) * 0.45f;
            float scale = 1f + level * 0.3f;
            halo.animate().scaleX(scale).scaleY(scale).alpha(0.4f + level * 0.5f)
                    .setDuration(90).start();
        }

        /** One-shot fade from wherever the view is now. */
        private static ObjectAnimator fade(View v, float alpha) {
            ObjectAnimator a = ObjectAnimator.ofFloat(v, View.ALPHA, alpha);
            a.setDuration(320);
            return a;
        }

        /** One-shot scale from wherever the view is now. */
        private static ObjectAnimator settle(View v, float scale) {
            ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(v,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, scale),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, scale));
            a.setDuration(240);
            return a;
        }

        /** Endless scale pulse between lo and hi, entered smoothly from the current size. */
        private static void pulse(List<Animator> out, View v, float lo, float hi, long ms) {
            out.add(settle(v, lo));
            ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(v,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, lo, hi),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, lo, hi));
            out.add(loop(a, ms));
        }

        /** Endless alpha + scale swell, for the blooms. */
        private static void breathe(List<Animator> out, View v,
                                    float aLo, float aHi, float sLo, float sHi, long ms) {
            out.add(fade(v, aLo));
            out.add(settle(v, sLo));
            ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(v,
                    PropertyValuesHolder.ofFloat(View.ALPHA, aLo, aHi),
                    PropertyValuesHolder.ofFloat(View.SCALE_X, sLo, sHi),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, sLo, sHi));
            out.add(loop(a, ms));
        }

        private static ObjectAnimator loop(ObjectAnimator a, long ms) {
            a.setDuration(ms);
            a.setStartDelay(320);  // after the one-shot settle/fade has landed
            a.setInterpolator(new AccelerateDecelerateInterpolator());
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.setRepeatMode(ValueAnimator.REVERSE);
            return a;
        }
    }

    /**
     * The idle screensaver: a dark room lit by slowly drifting neon blooms, a faint luminous
     * floor, motes of light, and the core resting over a thin clock. Shown by the idle timer,
     * dismissed by any key (which it swallows, so waking never also starts listening). It keeps
     * the panel awake while it shows and never touches recognition, the request, or speech.
     */
    private final class Screensaver {
        private final View root;
        private final NeonGridView grid;
        private final View drift;
        private final View bloomA;
        private final View bloomB;
        private final View bloomC;
        private final View core;
        private final View coreHot;
        private final View glow;
        private final View glowHot;
        private final View orbitA;
        private final View orbitB;
        private final View tiltA;
        private final View status;
        private final ViewGroup motes;
        private final TextView clock;
        private final TextView date;
        private final boolean animate;

        private final Random rnd = new Random();
        private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.US);
        private final SimpleDateFormat dateFmt =
                new SimpleDateFormat("EEEE · d MMMM", Locale.getDefault());
        private final Runnable tick = this::tick;
        private final List<Animator> motion = new ArrayList<>();

        private boolean showing;
        private boolean motesBuilt;
        private int wakeKey = KeyEvent.KEYCODE_UNKNOWN;

        // ===== Gallery (paintings) + news/weather overlays =====
        private static final long SLIDE_MS = 45_000L;
        private static final long CROSSFADE_MS = 1_500L;
        private static final long SLIDE_RETRY_MS = 4_000L;
        private static final long WEATHER_MS = 20 * 60_000L;
        private static final long NEWS_MS = 15 * 60_000L;
        private static final long ROTATE_MS = 7_000L;

        private final View stage;
        private final View[] slot = new View[2];
        private final ImageView[] blur = new ImageView[2];
        private final ImageView[] paint = new ImageView[2];
        private final TextView weatherText;
        private final TextView newsText;
        private final TextView caption;
        private final AnimatorSet[] kb = new AnimatorSet[2];
        private final Art[] shownArt = new Art[2];
        private final List<String> headlines = new ArrayList<>();

        private int frontSlot;
        private int slideIdx;
        private int slideGen;
        private boolean paintingShown;
        private Art backArt;
        private boolean backReady;
        private int headlineIdx;
        private boolean weatherShown;
        private boolean newsShown;

        private final Runnable slideTick = new Runnable() {
            @Override public void run() {
                boolean advanced = advanceSlide();
                ui.postDelayed(this, advanced ? SLIDE_MS : SLIDE_RETRY_MS);
            }
        };
        private final Runnable weatherTick = new Runnable() {
            @Override public void run() {
                fetchWeather();
                ui.postDelayed(this, WEATHER_MS);
            }
        };
        private final Runnable newsTick = new Runnable() {
            @Override public void run() {
                fetchNews();
                ui.postDelayed(this, NEWS_MS);
            }
        };
        private final Runnable rotateTick = new Runnable() {
            @Override public void run() {
                rotateHeadline();
                ui.postDelayed(this, ROTATE_MS);
            }
        };

        Screensaver(View root, boolean animate) {
            this.root = root;
            this.animate = animate;
            grid = root.findViewById(R.id.saverGrid);
            drift = root.findViewById(R.id.saverDrift);
            bloomA = root.findViewById(R.id.saverBloomA);
            bloomB = root.findViewById(R.id.saverBloomB);
            bloomC = root.findViewById(R.id.saverBloomC);
            core = root.findViewById(R.id.saverCore);
            coreHot = root.findViewById(R.id.saverCoreHot);
            glow = root.findViewById(R.id.saverGlow);
            glowHot = root.findViewById(R.id.saverGlowHot);
            orbitA = root.findViewById(R.id.saverOrbitA);
            orbitB = root.findViewById(R.id.saverOrbitB);
            tiltA = root.findViewById(R.id.saverTiltA);
            status = root.findViewById(R.id.saverStatus);
            motes = root.findViewById(R.id.saverMotes);
            clock = root.findViewById(R.id.saverClock);
            date = root.findViewById(R.id.saverDate);
            stage = root.findViewById(R.id.saverStage);
            slot[0] = root.findViewById(R.id.saverSlot0);
            slot[1] = root.findViewById(R.id.saverSlot1);
            blur[0] = root.findViewById(R.id.saverBlur0);
            blur[1] = root.findViewById(R.id.saverBlur1);
            paint[0] = root.findViewById(R.id.saverPaint0);
            paint[1] = root.findViewById(R.id.saverPaint1);
            weatherText = root.findViewById(R.id.saverWeather);
            newsText = root.findViewById(R.id.saverNews);
            caption = root.findViewById(R.id.saverCaption);
            grid.setAnimated(animate);
        }

        boolean isShowing() {
            return showing;
        }

        /**
         * True when the event must go no further. While showing, a key press wakes the screen;
         * that key's repeats and release are swallowed too, so a wake is only ever a wake.
         */
        boolean intercept(KeyEvent event) {
            if (showing) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    wakeKey = event.getKeyCode();
                    hide(true);
                }
                return true;
            }
            if (wakeKey != KeyEvent.KEYCODE_UNKNOWN && event.getKeyCode() == wakeKey) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    wakeKey = KeyEvent.KEYCODE_UNKNOWN;
                }
                return true;
            }
            return false;
        }

        /** Fades the room in. Returns false when MAX is mid-exchange and it must wait. */
        boolean show() {
            if (showing || isFinishing()) {
                return true;
            }
            if (busy) {
                return false;
            }
            showing = true;
            wakeKey = KeyEvent.KEYCODE_UNKNOWN;
            menu.close(false, KeyEvent.KEYCODE_UNKNOWN);  // never wake back into a stale menu
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            orbFx.pause();
            backdrop.setRunning(false);
            tick();
            resetGallery();
            startGallery();
            root.animate().cancel();
            content.animate().cancel();
            root.setVisibility(View.VISIBLE);
            if (!animate) {
                root.setAlpha(1f);
                content.setVisibility(View.INVISIBLE);
                return true;
            }
            root.animate().alpha(1f).setDuration(1100).withEndAction(() -> {
                if (showing) {
                    content.setVisibility(View.INVISIBLE);
                }
            }).start();
            content.animate().alpha(0f).setDuration(800).start();
            grid.setRunning(true);
            startMotion();
            return true;
        }

        void hide(boolean fade) {
            if (!showing) {
                return;
            }
            showing = false;
            ui.removeCallbacks(tick);
            ui.removeCallbacks(slideTick);
            ui.removeCallbacks(weatherTick);
            ui.removeCallbacks(newsTick);
            ui.removeCallbacks(rotateTick);
            slideGen++;  // invalidate any in-flight image / feed loads
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            stopMotion();
            grid.setRunning(false);
            root.animate().cancel();
            content.animate().cancel();
            content.setVisibility(View.VISIBLE);
            if (fade && animate) {
                content.animate().alpha(1f).setDuration(450).start();
                root.animate().alpha(0f).setDuration(450).withEndAction(() -> {
                    if (!showing) {
                        root.setVisibility(View.GONE);
                        releaseGallery();
                    }
                }).start();
            } else {
                content.setAlpha(1f);
                root.setAlpha(0f);
                root.setVisibility(View.GONE);
                releaseGallery();
            }
            backdrop.setRunning(true);
            orbFx.resume();
        }

        /** Clock and date, refreshed on the minute. */
        private void tick() {
            Date now = new Date();
            clock.setText(timeFmt.format(now));
            date.setText(dateFmt.format(now));
            long ms = System.currentTimeMillis();
            ui.removeCallbacks(tick);
            ui.postDelayed(tick, 60_000L - (ms % 60_000L) + 50L);
        }

        private void startMotion() {
            stopMotion();
            // The whole centre block wanders a little, so nothing burns in.
            motion.add(sway(drift, View.TRANSLATION_X, -dp(60), dp(60), 26000));
            motion.add(sway(drift, View.TRANSLATION_Y, -dp(20), dp(20), 19000));
            // Ambient light drifts across the room.
            motion.add(sway(bloomA, View.TRANSLATION_X, -dp(280), -dp(120), 31000));
            motion.add(sway(bloomA, View.TRANSLATION_Y, -dp(60), dp(40), 23000));
            motion.add(sway(bloomB, View.TRANSLATION_X, dp(320), dp(150), 37000));
            motion.add(sway(bloomB, View.TRANSLATION_Y, dp(140), dp(30), 29000));
            motion.add(sway(bloomC, View.TRANSLATION_X, dp(60), -dp(150), 41000));
            motion.add(sway(bloomC, View.TRANSLATION_Y, -dp(200), -dp(70), 27000));
            motion.add(sway(bloomA, View.ALPHA, 0.75f, 0.5f, 17000));
            motion.add(sway(bloomB, View.ALPHA, 0.7f, 0.42f, 13000));
            // The core breathes and slowly turns from magenta to cyan and back.
            motion.add(sway(core, View.SCALE_X, 1f, 1.05f, 3600));
            motion.add(sway(core, View.SCALE_Y, 1f, 1.05f, 3600));
            motion.add(sway(coreHot, View.SCALE_X, 1f, 1.05f, 3600));
            motion.add(sway(coreHot, View.SCALE_Y, 1f, 1.05f, 3600));
            motion.add(sway(coreHot, View.ALPHA, 0f, 1f, 7000));
            motion.add(sway(glowHot, View.ALPHA, 0f, 0.8f, 7000));
            motion.add(sway(glow, View.ALPHA, 0.75f, 0.3f, 7000));
            motion.add(sway(glow, View.SCALE_X, 1f, 1.1f, 4200));
            motion.add(sway(glow, View.SCALE_Y, 1f, 1.1f, 4200));
            motion.add(spin(orbitA, 0f, 360f, 11000));
            motion.add(spin(orbitB, 360f, 0f, 17000));
            motion.add(sway(tiltA, View.ROTATION, -24f, -12f, 9000));
            motion.add(sway(status, View.ALPHA, 0.85f, 0.3f, 1900));
            buildMotes();
            for (Animator a : motion) {
                a.start();
            }
            // Motes start part-way through their rise, so the room is already full of them.
            int n = motes.getChildCount();
            for (int i = 0; i < n; i++) {
                View m = motes.getChildAt(i);
                Object rise = m.getTag();
                if (rise instanceof ValueAnimator) {
                    ValueAnimator r = (ValueAnimator) rise;
                    r.setCurrentPlayTime(rnd.nextInt((int) r.getDuration()));
                }
            }
        }

        private void stopMotion() {
            for (Animator a : motion) {
                a.cancel();
            }
            motion.clear();
        }

        /** Sixteen points of light rising slowly through the room. */
        private void buildMotes() {
            int w = content.getWidth();
            int h = content.getHeight();
            if (w == 0 || h == 0) {
                return;
            }
            if (!motesBuilt) {
                motesBuilt = true;
                for (int i = 0; i < 16; i++) {
                    View m = new View(MainActivity.this);
                    m.setBackgroundResource(R.drawable.mote);
                    int size = Math.round(dp(3 + rnd.nextInt(6)));
                    motes.addView(m, new FrameLayout.LayoutParams(size, size));
                    m.setX(rnd.nextInt(w));
                }
            }
            int n = motes.getChildCount();
            for (int i = 0; i < n; i++) {
                View m = motes.getChildAt(i);
                float x = rnd.nextInt(w);
                long riseMs = 24000 + rnd.nextInt(26000);
                ObjectAnimator rise = ObjectAnimator.ofFloat(m, View.Y, h + dp(12), -dp(12));
                rise.setDuration(riseMs);
                rise.setInterpolator(new LinearInterpolator());
                rise.setRepeatCount(ValueAnimator.INFINITE);
                rise.setRepeatMode(ValueAnimator.RESTART);
                m.setTag(rise);
                motion.add(rise);
                motion.add(sway(m, View.X, x - dp(10), x + dp(10), 3000 + rnd.nextInt(3000)));
                motion.add(sway(m, View.ALPHA, 0.1f, 0.75f, 1500 + rnd.nextInt(2500)));
            }
        }

        private ObjectAnimator sway(View v, android.util.Property<View, Float> p,
                                    float from, float to, long ms) {
            ObjectAnimator a = ObjectAnimator.ofFloat(v, p, from, to);
            a.setDuration(ms);
            a.setInterpolator(new AccelerateDecelerateInterpolator());
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.setRepeatMode(ValueAnimator.REVERSE);
            return a;
        }

        private ObjectAnimator spin(View v, float from, float to, long ms) {
            ObjectAnimator a = ObjectAnimator.ofFloat(v, View.ROTATION, from, to);
            a.setDuration(ms);
            a.setInterpolator(new LinearInterpolator());
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.setRepeatMode(ValueAnimator.RESTART);
            return a;
        }

        // ===== Painting gallery =====

        /** Kicks off the slideshow and the weather / news timers for this showing. */
        private void startGallery() {
            loadFirst(slideGen);
            ui.removeCallbacks(slideTick);
            ui.postDelayed(slideTick, SLIDE_MS);
            ui.removeCallbacks(weatherTick);
            ui.post(weatherTick);
            ui.removeCallbacks(newsTick);
            ui.post(newsTick);
            ui.removeCallbacks(rotateTick);
            ui.postDelayed(rotateTick, ROTATE_MS);
        }

        /** Clears gallery state and shows the neon room as the fallback until a painting loads. */
        private void resetGallery() {
            slideGen++;
            paintingShown = false;
            frontSlot = 0;
            slideIdx = rnd.nextInt(ART_URLS.length);
            recycle(backArt);
            backArt = null;
            backReady = false;
            for (int i = 0; i < 2; i++) {
                stopSlideMotion(i);
                slot[i].animate().cancel();
                slot[i].setAlpha(0f);
                blur[i].setImageDrawable(null);
                paint[i].setImageDrawable(null);
                recycle(shownArt[i]);
                shownArt[i] = null;
            }
            stage.setVisibility(View.VISIBLE);
            bloomA.setVisibility(View.VISIBLE);
            bloomB.setVisibility(View.VISIBLE);
            bloomC.setVisibility(View.VISIBLE);
            grid.setVisibility(View.VISIBLE);
            motes.setVisibility(View.VISIBLE);
            weatherShown = false;
            newsShown = false;
            weatherText.animate().cancel();
            weatherText.setAlpha(0f);
            weatherText.setText("");
            newsText.animate().cancel();
            newsText.setAlpha(0f);
            newsText.setText("");
            caption.animate().cancel();
            caption.setAlpha(0f);
            caption.setText("");
            headlines.clear();
            headlineIdx = 0;
        }

        /** Frees every decoded painting and stops slide motion. Safe to call repeatedly. */
        private void releaseGallery() {
            stopSlideMotion(0);
            stopSlideMotion(1);
            recycle(backArt);
            backArt = null;
            backReady = false;
            for (int i = 0; i < 2; i++) {
                slot[i].animate().cancel();
                slot[i].setAlpha(0f);
                blur[i].setImageDrawable(null);
                paint[i].setImageDrawable(null);
                recycle(shownArt[i]);
                shownArt[i] = null;
            }
            caption.animate().cancel();
            weatherText.animate().cancel();
            newsText.animate().cancel();
        }

        /** Loads the first painting that decodes (starting at slideIdx) into the front slot. */
        private void loadFirst(final int gen) {
            runIo(() -> {
                final Art a = loadFirstSuccess(slideIdx, gen);
                ui.post(() -> {
                    if (gen != slideGen || !showing) {
                        recycle(a);
                        return;
                    }
                    if (a == null) {
                        return;  // offline: the neon room stays
                    }
                    slideIdx = a.idx;
                    final int s = frontSlot;
                    showArt(s, a);
                    setCaption(ART_CAPTIONS[a.idx]);
                    startSlideMotion(s);
                    if (animate) {
                        slot[s].animate().alpha(1f).setDuration(1200)
                                .withEndAction(this::onFirstPainting).start();
                    } else {
                        slot[s].setAlpha(1f);
                        onFirstPainting();
                    }
                    preloadNext(gen);
                });
            });
        }

        /** Loads the next painting into backArt, ready for the following crossfade. */
        private void preloadNext(final int gen) {
            final int start = (slideIdx + 1) % ART_URLS.length;
            runIo(() -> {
                final Art a = loadFirstSuccess(start, gen);
                ui.post(() -> {
                    if (gen != slideGen || !showing) {
                        recycle(a);
                        return;
                    }
                    recycle(backArt);
                    backArt = a;
                    backReady = a != null;
                });
            });
        }

        /** Tries indices in order from start; returns the first that decodes, or null. */
        private Art loadFirstSuccess(int start, int gen) {
            int n = ART_URLS.length;
            for (int k = 0; k < n; k++) {
                if (gen != slideGen) {
                    return null;
                }
                int idx = (start + k) % n;
                Bitmap full = loadImage(ART_URLS[idx]);
                if (full != null) {
                    return new Art(idx, full, blurDown(full));
                }
            }
            return null;
        }

        /** Crossfades to the preloaded next painting. Returns false if it was not ready. */
        private boolean advanceSlide() {
            if (!showing || !paintingShown) {
                return false;
            }
            if (!backReady || backArt == null) {
                preloadNext(slideGen);
                return false;
            }
            final int oldFront = frontSlot;
            final int back = 1 - frontSlot;
            final Art a = backArt;
            backArt = null;
            backReady = false;
            showArt(back, a);
            slideIdx = a.idx;
            setCaption(ART_CAPTIONS[a.idx]);
            startSlideMotion(back);
            if (animate) {
                slot[back].animate().alpha(1f).setDuration(CROSSFADE_MS).start();
                slot[oldFront].animate().alpha(0f).setDuration(CROSSFADE_MS)
                        .withEndAction(() -> clearSlot(oldFront)).start();
            } else {
                slot[back].setAlpha(1f);
                slot[oldFront].setAlpha(0f);
                clearSlot(oldFront);
            }
            frontSlot = back;
            preloadNext(slideGen);
            return true;
        }

        private void clearSlot(int s) {
            stopSlideMotion(s);
            blur[s].setImageDrawable(null);
            paint[s].setImageDrawable(null);
            recycle(shownArt[s]);
            shownArt[s] = null;
        }

        /** The whole (uncropped) painting, fit on screen over a blurred, darkened copy of itself. */
        private void showArt(int s, Art a) {
            shownArt[s] = a;
            blur[s].setImageBitmap(a.blur);
            blur[s].setColorFilter(0xFF565663, PorterDuff.Mode.MULTIPLY);
            paint[s].setImageBitmap(a.full);
            blur[s].setScaleX(1f);
            blur[s].setScaleY(1f);
            blur[s].setTranslationX(0f);
            blur[s].setTranslationY(0f);
            paint[s].setScaleX(1f);
            paint[s].setScaleY(1f);
        }

        /** Once the first painting is up, put the neon room away — the art is the star. */
        private void onFirstPainting() {
            if (!showing) {
                return;
            }
            paintingShown = true;
            stage.setVisibility(View.INVISIBLE);
            bloomA.setVisibility(View.INVISIBLE);
            bloomB.setVisibility(View.INVISIBLE);
            bloomC.setVisibility(View.INVISIBLE);
            motes.setVisibility(View.INVISIBLE);
            grid.setRunning(false);
            grid.setVisibility(View.INVISIBLE);
        }

        /**
         * Very subtle motion: a slow drift / zoom on the blurred fill (cropping it is fine), and a
         * barely-there breath on the artwork that only ever shrinks it, so nothing leaves the frame.
         */
        private void startSlideMotion(int s) {
            stopSlideMotion(s);
            if (!animate) {
                return;
            }
            List<Animator> anims = new ArrayList<>();
            float z0 = 1.0f, z1 = 1.10f;
            if (rnd.nextBoolean()) {
                float t = z0; z0 = z1; z1 = t;
            }
            float tx = dp(rnd.nextBoolean() ? 22 : -22);
            float ty = dp(rnd.nextBoolean() ? 14 : -14);
            anims.add(panZoom(blur[s], View.SCALE_X, z0, z1));
            anims.add(panZoom(blur[s], View.SCALE_Y, z0, z1));
            anims.add(panZoom(blur[s], View.TRANSLATION_X, 0f, tx));
            anims.add(panZoom(blur[s], View.TRANSLATION_Y, 0f, ty));
            ObjectAnimator breathe = ObjectAnimator.ofPropertyValuesHolder(paint[s],
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 0.985f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 0.985f));
            breathe.setDuration(13000);
            breathe.setInterpolator(new AccelerateDecelerateInterpolator());
            breathe.setRepeatCount(ValueAnimator.INFINITE);
            breathe.setRepeatMode(ValueAnimator.REVERSE);
            anims.add(breathe);
            AnimatorSet set = new AnimatorSet();
            set.playTogether(anims);
            set.start();
            kb[s] = set;
        }

        private ObjectAnimator panZoom(View v, android.util.Property<View, Float> p,
                                       float from, float to) {
            ObjectAnimator a = ObjectAnimator.ofFloat(v, p, from, to);
            a.setDuration(SLIDE_MS + CROSSFADE_MS + 2000L);
            a.setInterpolator(new LinearInterpolator());
            return a;
        }

        private void stopSlideMotion(int s) {
            if (kb[s] != null) {
                kb[s].cancel();
                kb[s] = null;
            }
        }

        private void setCaption(final String text) {
            if (!animate) {
                caption.setText(text);
                caption.setAlpha(0.85f);
                return;
            }
            if (caption.getAlpha() < 0.05f) {
                caption.setText(text);
                caption.animate().alpha(0.85f).setDuration(700).start();
            } else {
                caption.animate().alpha(0f).setDuration(350).withEndAction(() -> {
                    caption.setText(text);
                    caption.animate().alpha(0.85f).setDuration(500).start();
                }).start();
            }
        }

        // ===== Weather + news =====

        private void fetchWeather() {
            if (!showing) {
                return;
            }
            final int gen = slideGen;
            runIo(() -> {
                final String w = loadWeather();
                ui.post(() -> {
                    if (!showing || gen != slideGen || w == null) {
                        return;
                    }
                    weatherText.setText(w);
                    if (!weatherShown) {
                        weatherShown = true;
                        reveal(weatherText);
                    }
                });
            });
        }

        private void fetchNews() {
            if (!showing) {
                return;
            }
            final int gen = slideGen;
            runIo(() -> {
                final List<String> items = loadNews();
                ui.post(() -> {
                    if (!showing || gen != slideGen || items == null || items.isEmpty()) {
                        return;
                    }
                    headlines.clear();
                    headlines.addAll(items);
                    headlineIdx = 0;
                    newsText.setText(headlines.get(0));
                    if (!newsShown) {
                        newsShown = true;
                        reveal(newsText);
                    }
                });
            });
        }

        private void rotateHeadline() {
            if (!showing || headlines.size() < 2) {
                return;
            }
            headlineIdx = (headlineIdx + 1) % headlines.size();
            final String next = headlines.get(headlineIdx);
            if (!animate) {
                newsText.setText(next);
                return;
            }
            newsText.animate().alpha(0f).setDuration(380).withEndAction(() -> {
                newsText.setText(next);
                newsText.animate().alpha(1f).setDuration(420).start();
            }).start();
        }

        private void reveal(View v) {
            if (animate) {
                v.animate().alpha(1f).setDuration(650).start();
            } else {
                v.setAlpha(1f);
            }
        }

        private void runIo(Runnable r) {
            try {
                saverIo.execute(r);
            } catch (Exception ignored) {
                // executor is shutting down; nothing to do
            }
        }
    }

    /**
     * The on-screen menu: Cameras · Language · New conversation · Status, a neon list on dark glass
     * over the dimmed voice screen (layout/menu_overlay.xml). Opened by the menu key, driven by the
     * D-pad: ▲▼ slide the focus highlight, OK selects, BACK (or the menu key again) closes; on the
     * Language row ‹ › switch the language in place, and Status slides a second panel in beside the
     * list. While open it owns every key except volume, so nothing falls through to startListening
     * or the wall; the key that closes it is swallowed until released, like the screensaver's wake
     * key, so closing never also starts listening. It only ever calls the same openWall /
     * cycleLanguage / newConversation the remote keys always called.
     */
    private final class MaxMenu {
        static final int CAMERAS = 0;
        static final int LANGUAGE = 1;
        static final int NEW = 2;
        static final int STATUS = 3;

        private static final int CYAN = 0xFF05D9E8;

        private final View root;
        private final View scrim;
        private final View panel;
        private final View focus;
        private final View list;
        private final View statusPanel;
        private final View[] rows = new View[4];
        private final ImageView[] icons = new ImageView[4];
        private final TextView[] labels = new TextView[4];
        private final TextView[] subs = new TextView[4];
        private final TextView langValue;
        private final TextView statusValue;
        private final TextView statusVersion;
        private final TextView statusVoice;
        private final TextView statusBrain;
        private final TextView statusInput;
        private final TextView statusLink;
        private final TextView statusKeys;
        private final boolean animate;
        private final int dimIcon;
        private final int dimLabel;

        private boolean menuOpen;
        private boolean statusShown;
        private int sel;
        private int closeKey = KeyEvent.KEYCODE_UNKNOWN;

        MaxMenu(View root, boolean animate) {
            this.root = root;
            this.animate = animate;
            scrim = root.findViewById(R.id.menuScrim);
            panel = root.findViewById(R.id.menuPanel);
            focus = root.findViewById(R.id.menuFocus);
            list = root.findViewById(R.id.menuList);
            statusPanel = root.findViewById(R.id.menuStatus);
            int[] rowIds = {R.id.menuRow0, R.id.menuRow1, R.id.menuRow2, R.id.menuRow3};
            int[] iconIds = {R.id.menuIc0, R.id.menuIc1, R.id.menuIc2, R.id.menuIc3};
            int[] labelIds = {R.id.menuLabel0, R.id.menuLabel1, R.id.menuLabel2, R.id.menuLabel3};
            int[] subIds = {R.id.menuSub0, R.id.menuSub1, R.id.menuSub2, R.id.menuSub3};
            for (int i = 0; i < rows.length; i++) {
                rows[i] = root.findViewById(rowIds[i]);
                icons[i] = root.findViewById(iconIds[i]);
                labels[i] = root.findViewById(labelIds[i]);
                subs[i] = root.findViewById(subIds[i]);
            }
            langValue = root.findViewById(R.id.menuValue1);
            statusValue = root.findViewById(R.id.menuValue3);
            statusVersion = root.findViewById(R.id.statusVersion);
            statusVoice = root.findViewById(R.id.statusVoice);
            statusBrain = root.findViewById(R.id.statusBrain);
            statusInput = root.findViewById(R.id.statusInput);
            statusLink = root.findViewById(R.id.statusLink);
            statusKeys = root.findViewById(R.id.statusKeys);
            dimIcon = getColor(R.color.menu_icon_dim);
            dimLabel = getColor(R.color.menu_label_dim);
            ((TextView) root.findViewById(R.id.menuKeyCap)).setText(MENU_KEY_LABEL);
            // The overlay is laid out while still invisible, so the highlight can sit on its row
            // before the first fade-in; this keeps it there through any later relayout as well.
            list.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> placeFocus(false));
            paint();
        }

        boolean isOpen() {
            return menuOpen;
        }

        /**
         * True when the event must go no further: the menu is open (the key drives it), or the key
         * that just closed it is still held. Volume keys pass, so the TV's volume keeps working.
         */
        boolean intercept(KeyEvent event) {
            int code = event.getKeyCode();
            if (menuOpen) {
                if (isVolumeKey(code)) {
                    return false;
                }
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    onKey(code, event.getRepeatCount());
                }
                return true;
            }
            if (closeKey != KeyEvent.KEYCODE_UNKNOWN && code == closeKey) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    closeKey = KeyEvent.KEYCODE_UNKNOWN;
                }
                return true;
            }
            return false;
        }

        private void onKey(int code, int repeat) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    move(-1);
                    return;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    move(1);
                    return;
                default:
                    break;
            }
            if (repeat > 0) {
                return;  // a held key selects, switches or closes once, not on every repeat
            }
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    select(code);
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (sel == LANGUAGE) {
                        cycleLanguage(code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                        paint();
                    }
                    break;
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_ESCAPE:
                case KeyEvent.KEYCODE_MENU:
                case MENU_KEY:
                case MENU_KEY_ALT:
                    if (statusShown) {
                        showStatus(false);  // first BACK puts the status panel away
                    } else {
                        close(true, code);
                    }
                    break;
                default:
                    break;  // every other key stops here while the menu shows
            }
        }

        private void move(int delta) {
            int next = Math.floorMod(sel + delta, rows.length);
            if (next == sel) {
                return;
            }
            sel = next;
            if (statusShown && sel != STATUS) {
                showStatus(false);
            }
            paint();
            placeFocus(animate);
        }

        private void select(int code) {
            switch (sel) {
                case CAMERAS:
                    close(false, code);  // instantly, so the voice screen is clean on return
                    openWall();
                    break;
                case LANGUAGE:
                    cycleLanguage(1);
                    paint();
                    break;
                case NEW:
                    newConversation();
                    close(true, code);
                    break;
                case STATUS:
                default:
                    showStatus(!statusShown);
                    break;
            }
        }

        void open() {
            if (menuOpen) {
                return;
            }
            menuOpen = true;
            closeKey = KeyEvent.KEYCODE_UNKNOWN;
            statusShown = false;
            sel = CAMERAS;  // always lands on Cameras, so menu key + OK is the wall
            statusPanel.animate().cancel();
            statusPanel.setAlpha(0f);
            statusPanel.setVisibility(View.INVISIBLE);
            paint();
            placeFocus(false);
            root.animate().cancel();
            scrim.animate().cancel();
            panel.animate().cancel();
            root.setAlpha(1f);
            root.setVisibility(View.VISIBLE);
            if (!animate) {
                scrim.setAlpha(1f);
                panel.setAlpha(1f);
                panel.setTranslationX(0f);
                return;
            }
            scrim.setAlpha(0f);
            scrim.animate().alpha(1f).setDuration(220).start();
            panel.setAlpha(0f);
            panel.setTranslationX(-dp(28));
            panel.animate().alpha(1f).translationX(0f).setDuration(260)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }

        /**
         * Puts the menu away. closeKey is the key that did it (or KEYCODE_UNKNOWN when nothing
         * did, e.g. the screensaver or onPause); its repeats and release are swallowed.
         */
        void close(boolean fade, int closeKey) {
            if (!menuOpen) {
                return;
            }
            menuOpen = false;
            this.closeKey = closeKey;
            statusShown = false;
            root.animate().cancel();
            scrim.animate().cancel();
            panel.animate().cancel();
            statusPanel.animate().cancel();
            if (fade && animate) {
                panel.animate().alpha(0f).translationX(-dp(16)).setDuration(180).start();
                root.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                    if (!menuOpen) {
                        root.setVisibility(View.INVISIBLE);
                        statusPanel.setAlpha(0f);
                        statusPanel.setVisibility(View.INVISIBLE);
                    }
                }).start();
            } else {
                root.setAlpha(0f);
                root.setVisibility(View.INVISIBLE);
                statusPanel.setAlpha(0f);
                statusPanel.setVisibility(View.INVISIBLE);
            }
        }

        private void showStatus(boolean show) {
            if (show == statusShown) {
                return;
            }
            statusShown = show;
            statusPanel.animate().cancel();
            if (show) {
                fillStatus();
            }
            if (!animate) {
                statusPanel.setAlpha(show ? 1f : 0f);
                statusPanel.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
                return;
            }
            if (show) {
                statusPanel.setVisibility(View.VISIBLE);
                statusPanel.setTranslationX(-dp(20));
                statusPanel.animate().alpha(1f).translationX(0f).setDuration(240)
                        .setInterpolator(new DecelerateInterpolator()).start();
            } else {
                statusPanel.animate().alpha(0f).translationX(-dp(12)).setDuration(160)
                        .withEndAction(() -> {
                            if (!statusShown) {
                                statusPanel.setVisibility(View.INVISIBLE);
                            }
                        }).start();
            }
        }

        /** Row colours, the live values (current language, online / no key) and the status text. */
        private void paint() {
            langValue.setText(INPUT_LABELS[lang]);
            statusValue.setText(TextUtils.isEmpty(apiKey()) ? "no key" : "online");
            int bright = getColor(R.color.text_primary);
            int subOn = getColor(R.color.text_secondary);
            int subOff = getColor(R.color.text_dim);
            for (int i = 0; i < rows.length; i++) {
                boolean on = i == sel;
                icons[i].setColorFilter(on ? CYAN : dimIcon);
                labels[i].setTextColor(on ? bright : dimLabel);
                subs[i].setTextColor(on ? subOn : subOff);
                rows[i].setAlpha(on ? 1f : 0.8f);
            }
            if (statusShown) {
                fillStatus();
            }
        }

        private void placeFocus(boolean slide) {
            float y = rows[sel].getTop();
            focus.animate().cancel();
            if (slide) {
                focus.animate().translationY(y).setDuration(170)
                        .setInterpolator(new DecelerateInterpolator()).start();
            } else {
                focus.setTranslationY(y);
            }
        }

        @SuppressWarnings("deprecation")
        private void fillStatus() {
            String version;
            try {
                PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
                long build = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                        ? pi.getLongVersionCode() : pi.versionCode;
                version = pi.versionName + " · build " + build + " · Android "
                        + Build.VERSION.RELEASE;
            } catch (PackageManager.NameNotFoundException e) {
                version = "—";
            }
            statusVersion.setText(version);
            boolean recognizer = SpeechRecognizer.isRecognitionAvailable(MainActivity.this);
            statusVoice.setText((recognizer ? "Recognizer ready" : "No speech recognizer")
                    + " · " + (ttsReady ? "voice ready" : "no voice")
                    + (busy ? " · busy" : " · idle"));
            statusBrain.setText(TextUtils.isEmpty(apiKey())
                    ? "Claude · no API key set"
                    : "Claude · API key set · "
                            + (claude == null ? "no conversation yet" : "conversation open"));
            statusInput.setText(INPUT_LANGS[lang].toLanguageTag() + " · " + INPUT_LABELS[lang]);
            statusLink.setText("Center PC · " + prefs().getString(KEY_CAM_HOST,
                    WallActivity.DEFAULT_CAM_HOST) + " · go2rtc over Tailscale");
            statusKeys.setText("YELLOW " + MENU_KEY_LABEL + " or INFO menu · RED cameras · OK speak"
                    + " · ‹ › language · ■ new conversation");
        }

        private boolean isVolumeKey(int code) {
            return code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                    || code == KeyEvent.KEYCODE_VOLUME_MUTE || code == KeyEvent.KEYCODE_MUTE;
        }
    }

    // ===== Painting set for the screensaver gallery. Resolved from Wikimedia Commons to ~1920px
    // thumbnails and loaded over the network at runtime (never bundled). ART_URLS and ART_CAPTIONS
    // stay in step by index. =====
    private static final String[] ART_URLS = {
            "https://thumb.wikimedia.org/wikipedia/commons/thumb/e/ec/Mona_Lisa%2C_by_Leonardo_da_Vinci%2C_from_C2RMF_retouched.jpg/1920px-Mona_Lisa%2C_by_Leonardo_da_Vinci%2C_from_C2RMF_retouched.jpg",
            "https://thumb.wikimedia.org/wikipedia/commons/thumb/e/ea/Van_Gogh_-_Starry_Night_-_Google_Art_Project.jpg/1920px-Van_Gogh_-_Starry_Night_-_Google_Art_Project.jpg",
            "https://thumb.wikimedia.org/wikipedia/commons/thumb/0/0d/Great_Wave_off_Kanagawa2.jpg/1920px-Great_Wave_off_Kanagawa2.jpg",
            "https://thumb.wikimedia.org/wikipedia/commons/thumb/d/d7/Meisje_met_de_parel.jpg/1920px-Meisje_met_de_parel.jpg",
            "https://thumb.wikimedia.org/wikipedia/commons/thumb/c/c5/Edvard_Munch%2C_1893%2C_The_Scream%2C_oil%2C_tempera_and_pastel_on_cardboard%2C_91_x_73_cm%2C_National_Gallery_of_Norway.jpg/1920px-Edvard_Munch%2C_1893%2C_The_Scream%2C_oil%2C_tempera_and_pastel_on_cardboard%2C_91_x_73_cm%2C_National_Gallery_of_Norway.jpg",
            "https://thumb.wikimedia.org/wikipedia/commons/thumb/7/7d/A_Sunday_on_La_Grande_Jatte%2C_Georges_Seurat%2C_1884.jpg/1920px-A_Sunday_on_La_Grande_Jatte%2C_Georges_Seurat%2C_1884.jpg",
            "https://thumb.wikimedia.org/wikipedia/commons/thumb/0/0b/Sandro_Botticelli_-_La_nascita_di_Venere_-_Google_Art_Project_-_edited.jpg/1920px-Sandro_Botticelli_-_La_nascita_di_Venere_-_Google_Art_Project_-_edited.jpg",
            "https://thumb.wikimedia.org/wikipedia/commons/thumb/9/94/The_Nightwatch_by_Rembrandt_-_Rijksmuseum.jpg/1920px-The_Nightwatch_by_Rembrandt_-_Rijksmuseum.jpg",
            "https://thumb.wikimedia.org/wikipedia/commons/thumb/a/aa/Claude_Monet_-_Water_Lilies_-_1906%2C_Ryerson.jpg/1920px-Claude_Monet_-_Water_Lilies_-_1906%2C_Ryerson.jpg",
            "https://upload.wikimedia.org/wikipedia/commons/f/f3/Gustav_Klimt_016.jpg",
            "https://thumb.wikimedia.org/wikipedia/commons/thumb/2/20/Johannes_Vermeer_-_Het_melkmeisje_-_Google_Art_Project.jpg/1920px-Johannes_Vermeer_-_Het_melkmeisje_-_Google_Art_Project.jpg",
            "https://thumb.wikimedia.org/wikipedia/commons/thumb/b/b9/Caspar_David_Friedrich_-_Wanderer_above_the_sea_of_fog.jpg/1920px-Caspar_David_Friedrich_-_Wanderer_above_the_sea_of_fog.jpg",
            "https://thumb.wikimedia.org/wikipedia/commons/thumb/c/cc/Grant_Wood_-_American_Gothic_-_Google_Art_Project.jpg/1920px-Grant_Wood_-_American_Gothic_-_Google_Art_Project.jpg",
            "https://thumb.wikimedia.org/wikipedia/commons/thumb/f/fc/Pieter_Bruegel_the_Elder_-_The_Tower_of_Babel_%28Vienna%29_-_Google_Art_Project_-_edited.jpg/1920px-Pieter_Bruegel_the_Elder_-_The_Tower_of_Babel_%28Vienna%29_-_Google_Art_Project_-_edited.jpg",
    };
    private static final String[] ART_CAPTIONS = {
            "Mona Lisa · Leonardo da Vinci",
            "The Starry Night · Vincent van Gogh",
            "The Great Wave off Kanagawa · Hokusai",
            "Girl with a Pearl Earring · Johannes Vermeer",
            "The Scream · Edvard Munch",
            "A Sunday on La Grande Jatte · Georges Seurat",
            "The Birth of Venus · Sandro Botticelli",
            "The Night Watch · Rembrandt",
            "Water Lilies · Claude Monet",
            "The Kiss · Gustav Klimt",
            "The Milkmaid · Johannes Vermeer",
            "Wanderer above the Sea of Fog · Caspar David Friedrich",
            "American Gothic · Grant Wood",
            "The Tower of Babel · Pieter Bruegel the Elder",
    };

    /** One decoded painting: its index, the full artwork, and a tiny blurred copy for the fill. */
    private static final class Art {
        final int idx;
        final Bitmap full;
        final Bitmap blur;

        Art(int idx, Bitmap full, Bitmap blur) {
            this.idx = idx;
            this.full = full;
            this.blur = blur;
        }
    }

    private static void recycle(Art a) {
        if (a == null) {
            return;
        }
        if (a.full != null && !a.full.isRecycled()) {
            a.full.recycle();
        }
        if (a.blur != null && !a.blur.isRecycled()) {
            a.blur.recycle();
        }
    }

    /** Downloads and decodes an image (RGB_565, long side capped) off the main thread, or null. */
    private static Bitmap loadImage(String urlStr) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setRequestProperty("User-Agent", "MAXTV/1.0");
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(true);
            int status = c.getResponseCode();
            if (status < 200 || status >= 300) {
                return null;
            }
            byte[] data = readBytes(c.getInputStream(), 16 * 1024 * 1024);
            if (data == null || data.length == 0) {
                return null;
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            o.inSampleSize = sampleSize(o.outWidth, o.outHeight, 2048);
            o.inJustDecodeBounds = false;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Throwable t) {  // includes OutOfMemoryError
            return null;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private static int sampleSize(int w, int h, int max) {
        int s = 1;
        while (w > 0 && h > 0 && (w / s > max || h / s > max)) {
            s <<= 1;
        }
        return s;
    }

    /** A heavily downscaled copy; an ImageView upscales it smoothly into a soft, cheap blur. */
    private static Bitmap blurDown(Bitmap src) {
        try {
            int w = src.getWidth(), h = src.getHeight();
            int longSide = Math.max(w, h);
            if (longSide <= 0) {
                return null;
            }
            float sc = 44f / longSide;
            int bw = Math.max(1, Math.round(w * sc));
            int bh = Math.max(1, Math.round(h * sc));
            return Bitmap.createScaledBitmap(src, bw, bh, true);
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] readBytes(InputStream in, int cap) {
        if (in == null) {
            return null;
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                total += n;
                if (total > cap) {
                    break;
                }
            }
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** Simple keyless GET returning the body as UTF-8 text, or null on any failure. */
    private static String httpGet(String urlStr, String userAgent) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setRequestProperty("User-Agent", userAgent != null ? userAgent : "MAXTV/1.0");
            c.setRequestProperty("Accept", "*/*");
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setInstanceFollowRedirects(true);
            int status = c.getResponseCode();
            if (status < 200 || status >= 300) {
                return null;
            }
            byte[] data = readBytes(c.getInputStream(), 4 * 1024 * 1024);
            return data == null ? null : new String(data, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /** La Soukra current weather from Open-Meteo (keyless), as "glyph  La Soukra · 19° · text". */
    private static String loadWeather() {
        String body = httpGet("https://api.open-meteo.com/v1/forecast?latitude=36.87&longitude=10.23"
                + "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,"
                + "relative_humidity_2m&timezone=auto", "MAXTV/1.0");
        if (body == null) {
            return null;
        }
        try {
            JSONObject cur = new JSONObject(body).optJSONObject("current");
            if (cur == null || !cur.has("temperature_2m")) {
                return null;
            }
            double temp = cur.optDouble("temperature_2m", Double.NaN);
            if (Double.isNaN(temp)) {
                return null;
            }
            String[] wc = wmo(cur.optInt("weather_code", -1));
            return wc[0] + "  La Soukra · " + Math.round(temp) + "° · " + wc[1];
        } catch (Exception e) {
            return null;
        }
    }

    /** WMO weather code to a small glyph + short French label. */
    private static String[] wmo(int c) {
        if (c == 0) {
            return new String[]{"☀", "Ciel dégagé"};
        }
        if (c >= 1 && c <= 3) {
            return new String[]{"☁", "Partiellement nuageux"};
        }
        if (c == 45 || c == 48) {
            return new String[]{"☁", "Brouillard"};
        }
        if (c >= 51 && c <= 67) {
            return new String[]{"☂", "Pluie"};
        }
        if ((c >= 71 && c <= 77) || c == 85 || c == 86) {
            return new String[]{"❄", "Neige"};
        }
        if (c >= 80 && c <= 82) {
            return new String[]{"☂", "Averses"};
        }
        if (c >= 95 && c <= 99) {
            return new String[]{"☇", "Orage"};
        }
        return new String[]{"☁", "Nuageux"};
    }

    /** Latest Tunisian headlines: Mosaique FM (Arabic), falling back to La Presse (French). */
    private static List<String> loadNews() {
        // Mosaique FM is the Arabic primary but is intermittently unreachable (connection resets),
        // so give it a second try before falling back to La Presse.
        for (int attempt = 0; attempt < 2; attempt++) {
            List<String> items = parseRss(httpGet("https://www.mosaiquefm.net/ar/rss",
                    "Mozilla/5.0 (Android TV) MAXTV/1.0"));
            if (!items.isEmpty()) {
                return items;
            }
        }
        return parseRss(httpGet("https://lapresse.tn/feed/",
                "Mozilla/5.0 (Android TV) MAXTV/1.0"));
    }

    /** Pulls up to 8 item titles from an RSS body (skips the channel title; handles CDATA). */
    private static List<String> parseRss(String xml) {
        List<String> out = new ArrayList<>();
        if (xml == null) {
            return out;
        }
        int from = 0;
        while (out.size() < 8) {
            int is = xml.indexOf("<item", from);
            if (is < 0) {
                break;
            }
            int open = xml.indexOf('>', is);
            if (open < 0) {
                break;
            }
            int ie = xml.indexOf("</item>", open);
            if (ie < 0) {
                break;
            }
            String title = extractTag(xml.substring(open + 1, ie), "title");
            if (title != null) {
                title = cleanXml(title);
                if (!title.isEmpty()) {
                    out.add(title);
                }
            }
            from = ie + 7;
        }
        return out;
    }

    private static String extractTag(String s, String tag) {
        int o = s.indexOf("<" + tag);
        if (o < 0) {
            return null;
        }
        int gt = s.indexOf('>', o);
        if (gt < 0) {
            return null;
        }
        int cl = s.indexOf("</" + tag + ">", gt + 1);
        if (cl < 0) {
            return null;
        }
        return s.substring(gt + 1, cl);
    }

    /** Strips CDATA / stray tags and unescapes XML entities into clean display text. */
    private static String cleanXml(String s) {
        if (s == null) {
            return "";
        }
        s = s.replace("<![CDATA[", "").replace("]]>", "");
        s = s.replaceAll("<[^>]+>", " ");
        s = s.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#34;", "\"")
                .replace("&#39;", "'").replace("&apos;", "'")
                .replace("&nbsp;", " ");
        s = unescapeNumeric(s);
        s = s.replace("&amp;", "&");
        return s.replaceAll("\\s+", " ").trim();
    }

    private static String unescapeNumeric(String s) {
        if (s.indexOf("&#") < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0, n = s.length();
        while (i < n) {
            char ch = s.charAt(i);
            if (ch == '&' && i + 2 < n && s.charAt(i + 1) == '#') {
                int semi = s.indexOf(';', i + 2);
                if (semi > i + 2 && semi - i < 12) {
                    String num = s.substring(i + 2, semi);
                    try {
                        int cp = (num.charAt(0) == 'x' || num.charAt(0) == 'X')
                                ? Integer.parseInt(num.substring(1), 16)
                                : Integer.parseInt(num);
                        sb.appendCodePoint(cp);
                        i = semi + 1;
                        continue;
                    } catch (Exception ignored) {
                    }
                }
            }
            sb.append(ch);
            i++;
        }
        return sb.toString();
    }
}
