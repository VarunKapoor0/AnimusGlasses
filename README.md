# AnimusGlasses
### Animus for Meta Ray-Ban Display glasses.

Point your glasses at any object. It identifies it, gives it a personality, and speaks to you in its own voice — through the glasses speakers.

Built on the [Meta Wearables Device Access Toolkit](https://wearables.developer.meta.com) (DAT SDK v0.6.0).

**[Animus web app →](https://animusai.app)**

---

## What it does

1. **Look** — point your Meta glasses at any object
2. **Scan** — tap Scan on the companion Android app
3. **Listen** — Gemini Vision identifies the object, generates a personality, and speaks its opening line through your glasses speakers
4. **Talk** — type or speak back through the companion app; the object responds in character through the glasses

The camera input comes from the glasses. The audio output goes to the glasses speakers. The phone is the AI processing layer.

---

## Demo

Built and demoed on **Meta Ray-Ban Display glasses** (May 2025).

- Glasses camera → Gemini Vision → object personality generated
- Opening line spoken through glasses speakers via Orpheus TTS
- Full conversation maintained in-character

---

## Tech stack

- **Kotlin + Jetpack Compose** — Android companion app
- **Meta Wearables DAT SDK v0.6.0** — glasses camera stream, Bluetooth session management
- **Gemini 2.5 Flash** — vision (object ID + personality generation) and chat (in-character conversation)
- **Groq Orpheus TTS** (`canopylabs/orpheus-v1-english`) — neural voice synthesis, routed to glasses speakers
- **Groq Whisper** (`whisper-large-v3-turbo`) — STT for voice input
- **OkHttp** — API calls to Gemini and Groq

---

## Architecture

```
Meta Ray-Ban Display glasses
    ↓ I420 YUV frames over Bluetooth (Wearables DAT SDK)
Android companion app (this repo)
    ↓ i420ToBitmap() → JPEG → base64
Gemini 2.5 Flash Vision API
    ↓ object_type, personality_summary, opening_line, voice, vocal_direction
Groq Orpheus TTS
    ↓ WAV audio
Android AudioTrack → Bluetooth A2DP → glasses speakers
```

**Session flow:**
```
App launch
    → Android permissions (BLUETOOTH_CONNECT, RECORD_AUDIO)
    → Wearables.RequestPermissionContract() — SDK camera permission via Meta AI app
    → Check RegistrationState — startRegistration() if needed
    → Wearables.createSession(AutoDeviceSelector())
    → session.addStream(StreamConfiguration(MEDIUM, 7fps))
    → stream.videoStream.collect { frame → i420BufferToJpeg() → stored as lastFrameJpeg }
    → user taps Scan → lastFrameJpeg → Gemini Vision → personality
    → Orpheus TTS → AudioTrack → glasses speakers
    → user types/speaks → Gemini chat → TTS → glasses speakers
```

---

## Project structure

```
app/src/main/java/com/varun/animusglasses/
├── AnimusApplication.kt     # Wearables.initialize() on app start
├── MainActivity.kt          # Permission flow, SDK camera permission, Compose UI host
├── AnimusViewModel.kt       # Session management, Gemini calls, TTS, STT, frame conversion
└── ui/
    ├── ScanScreen.kt        # Connection status + scan button
    └── ChatScreen.kt        # Object personality + conversation UI
```

---

## Setup

### Prerequisites
- Android phone (API 31+)
- Meta Ray-Ban glasses (Gen 1, Gen 2, or Display) connected via Meta AI app
- Meta Wearables Developer Center account at [wearables.developer.meta.com](https://wearables.developer.meta.com)
- GitHub PAT with `read:packages` scope (for SDK download)

### local.properties
```
github_token=YOUR_GITHUB_PAT
```

### API keys
In `AnimusViewModel.kt`:
```kotlin
private const val GEMINI_API_KEY = "your_gemini_key"
private const val GROQ_API_KEY = "your_groq_key"
```

### Glasses setup
1. Install **Meta AI app** on Android, pair your glasses
2. Enable **Developer Mode** on glasses: Meta AI app → glasses settings → About → tap version 7 times
3. Enable **Developer Mode** on phone: Settings → About Phone → tap Build Number 7 times

### Build
```bash
git clone https://github.com/VarunKapoor0/AnimusGlasses
# Add github_token to local.properties
# Add API keys to AnimusViewModel.kt
# Open in Android Studio, sync Gradle, run
```

---

## Notes

- Groq Orpheus requires accepting model terms at [console.groq.com](https://console.groq.com/playground?model=canopylabs%2Forpheus-v1-english) before first use
- The SDK currently supports camera and audio. Neural Band gesture input is not yet available in the public SDK.
- Audio output routes to glasses speakers automatically via Bluetooth A2DP
- Glasses mic input (Bluetooth SCO routing) is a planned next step

---

## Related

- [Animus web app](https://animusai.app) — the browser version, no glasses required
- [Animus source](https://github.com/VarunKapoor0/Animus) — React + Vite + WebXR

---

*Built by [Varun Kapoor](https://varkapoor.com)*
