# Claude Voice for Android TV

Claude as a voice assistant on an Android TV, speaking **Tunisian Derja** and **French**.

Press OK on the remote, speak, and the reply is spoken back. Built for a
Toshiba hiSmartTV A4 (Hisense hardware, Android TV 9, SDK 28), and it should run on any
Android TV from 8.0 up.

## How it works

```
remote mic -> Android SpeechRecognizer -> Claude Messages API -> Android TextToSpeech
```

| Button | What it does |
|---|---|
| **OK** (or the mic / search key) | Start listening |
| **◀ ▶** | Switch the input language between الدارجة and Français |
| **Stop** | Start a new conversation, forgetting the previous turns |

The reply's voice follows the language Claude answered in, not the one you asked in: ask in
French, get an answer in Derja, and it still reads correctly.

## Languages: what works and what does not

| | Understanding | Speech to text | Text to speech |
|---|---|---|---|
| **French** | Full | Exact (`fr-FR`) | Native voice |
| **Tunisian Derja** | Full | Approximate (`ar-TN`, the closest Google offers) | **Modern Standard Arabic voice** |

There is no Derja text-to-speech voice on Android. The MSA voice reads Derja written in
Arabic script approximately — understandable, not native. This is a platform limit, not a
setting, and nothing in this app can work around it.

That is also why the system prompt forces Derja into **Arabic script**: written in Latin
letters (`3`, `7`, `9` for ع ح ق) the speech engine reads it as nonsense.

Code-switching mid-sentence is normal and expected — the prompt tells Claude to follow your
mix rather than correct it.

## Build and install

Needs JDK 17 and the Android SDK on the machine doing the building, and that machine must be
on the same Wi-Fi as the TV.

```sh
# On the TV: Settings > Device Preferences > About > press OK on Build seven times,
# then Developer options > USB debugging on. That opens port 5555 on this firmware.

adb connect 192.168.0.67:5555
./gradlew assembleRelease
adb -s 192.168.0.67:5555 install -r app/build/outputs/apk/release/app-release.apk
```

## Set the API key

Nobody types an API key on a TV remote, so the app takes it over adb:

```sh
adb -s 192.168.0.67:5555 shell am start \
  -n io.github.ghazi1235.claudevoice/.MainActivity \
  --es api_key 'sk-ant-...'
```

It is stored in the app's private `SharedPreferences` and the extra is cleared off the Intent
afterwards. Two things worth knowing: a key passed this way is visible in the TV's logcat at
the moment it is sent, and anyone on your Wi-Fi who can reach port 5555 can read the app's
data. Turn USB debugging back off when you are done setting up.

Get a key at [console.anthropic.com](https://console.anthropic.com). Usage is billed per
token against that key.

## Microphone

On this TV the microphone is in the **Bluetooth remote**, not the panel. The remote must be
paired and connected or `SpeechRecognizer` reports `ERROR_AUDIO`. The app asks for
`RECORD_AUDIO` on first run; accept it on screen.

Speech recognition also needs the Google app present. The CleanYourTV debloat list for this
panel keeps it (`katniss`, `pumpkin`), but a heavier list may not — if it is disabled, the app
says so instead of failing silently.

## Status

Written but **not yet run on hardware**. The Java parses cleanly and the XML is well-formed,
but it has never been compiled against the Android SDK or installed on a TV, so expect to fix
something on first build.
