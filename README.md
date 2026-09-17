# Shop AI — Android Native

Kotlin + **Jetpack Compose** Android app for Shop AI. Mirrors the Expo mobile app (`apps/mobile`) UI and connects to the same backend API (`apps/backend`).

## Stack

| Layer | Technology |
|-------|------------|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Navigation | Navigation Compose |
| Networking | Retrofit + OkHttp |
| Auth storage | DataStore Preferences |
| Min SDK | 26 |
| Target SDK | 35 |

## Project structure

```
apps/android-native/
  app/src/main/java/com/shopai/app/
    data/          API client, repositories, models
    ui/
      components/  Shared Compose widgets
      screens/     Splash, Login, OTP, BusinessSetup, Home, …
      theme/       Colors & typography (matches apps/mobile theme)
    MainActivity.kt
```

## Prerequisites

- Android Studio Ladybug (2024.2+) or newer
- JDK 17
- Android SDK 35
- Backend running locally (`npm run backend:dev` from repo root)

## Run

1. Open `apps/android-native` in Android Studio.
2. Create `local.properties` with your SDK path (Android Studio does this automatically):

   ```properties
   sdk.dir=/path/to/Android/Sdk
   ```

3. Start the backend on port 4000.
4. Run on emulator or device.

### API URL

Default dev URL is `http://10.0.2.2:4000` (emulator → host `localhost`).

For a **physical device**, override in `app/build.gradle.kts`:

```kotlin
buildConfigField("String", "API_BASE_URL", "\"http://192.168.x.x:4000\"")
```

Use your machine's LAN IP.

### TTS proxy (Sarvam AI)

Natural Tamil voice uses the same Vercel proxy as the Expo app (`apps/tts-proxy`).
Defaults match `apps/mobile/eas.json`:

| BuildConfig field | Expo env var |
|-------------------|--------------|
| `TTS_PROXY_URL` | `EXPO_PUBLIC_TTS_PROXY_URL` |
| `TTS_PROXY_KEY` | `EXPO_PUBLIC_TTS_PROXY_KEY` |

Override in `app/build.gradle.kts` if you deploy your own proxy instance.
If the proxy is unreachable, the app falls back to the device's offline TTS.

## Localization & settings

- **English** (`res/values/strings.xml`) and **Tamil** (`res/values-ta/strings.xml`)
- Language and dark theme persisted via DataStore
- **Settings** → Language, Appearance, About App, Subscription link
- **Subscription** screen with plan UI (billing not wired — see `BillingConfig.kt`)

## Screens implemented

- Splash (session bootstrap)
- Login (phone OTP + test login)
- OTP verification
- Business setup (category chips, Tamil labels)
- Home dashboard (health, cash flow, priorities, funding card, quick actions)
- Customers & Suppliers lists (with add credit/debit shortcuts)
- Voice entry (“பேசுங்க”) — Android speech recognition + text parse via `POST /voice/parse`
- Add credit / add debit forms
- Reminders (list, add, mark done)
- AI insights (health, cash flow, insights, seasonal, dismiss)
- Funding qualification (interested → lead creation)
- More (menu + logout)

## Build from CLI

```bash
cd apps/android-native
./gradlew assembleDebug
```

If `gradlew` is missing, open the project once in Android Studio or run:

```bash
gradle wrapper --gradle-version 8.9
```
