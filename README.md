# Modern SDA Android

A native Android Steam Authenticator app built with Jetpack Compose. Manage multiple Steam accounts, generate TOTP codes, and approve/decline trade and market confirmations — all from your phone.

## Features

- **Multi-account support** — import any number of `.mafile` accounts
- **TOTP code generation** — 30-second Steam guard codes with animated countdown
- **Trade & market confirmations** — view, accept, or decline confirmations with swipe-to-refresh
- **Auto session refresh** — silently re-authenticates using your stored refresh token (no repeated password prompts)
- **Material 3 UI** — dynamic color, dark/light theme, per-device theming
- **No network telemetry** — all traffic goes directly to Steam's official APIs

## Requirements

| Requirement | Version |
|---|---|
| Android | 11 (API 31) or higher |
| JDK | 17 |
| Android Studio | Ladybug (2024.2) or newer |
| Gradle | Managed by wrapper (no install needed) |

## Building from source

### 1. Clone the repo

```bash
git clone https://github.com/HouwyTwitch/Modern-SDA-Android.git
cd Modern-SDA-Android
```

### 2. Open in Android Studio

Open the project folder in **Android Studio Ladybug (2024.2)** or newer.
Gradle will sync automatically on first open.

### 3. Install the required SDK

The app targets **API 35** and runs on any device from API 31 upward, including API 36 (Android 16).

In Android Studio:
1. **SDK Manager** → **SDK Platforms** tab
2. Check **Android 15 (API 35)** — required for compilation
3. Optionally check **Android 16 (API 36)** if you want to run the emulator at that API level
4. Click **Apply**

### 4. Build a debug APK (for sideloading)

From the terminal inside the project directory:

```bash
./gradlew assembleDebug
```

The APK is written to:
```
app/build/outputs/apk/debug/app-debug.apk
```

### 5. Build a release APK

A release build requires a signing keystore. Create one if you don't have one:

```bash
keytool -genkey -v \
  -keystore my-release-key.jks \
  -alias my-key-alias \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

Then add a `keystore.properties` file in the project root (never commit this):

```properties
storeFile=../my-release-key.jks
storePassword=your_store_password
keyAlias=my-key-alias
keyPassword=your_key_password
```

Or sign manually after building an unsigned APK:

```bash
./gradlew assembleRelease

# sign with apksigner (part of Android build-tools)
apksigner sign \
  --ks my-release-key.jks \
  --ks-key-alias my-key-alias \
  app/build/outputs/apk/release/app-release-unsigned.apk
```

## Installing on a Pixel 6 Pro (API 36 / Android 16)

### Enable developer options and USB debugging

1. **Settings → About phone → Build number** — tap 7 times
2. **Settings → System → Developer options → USB debugging** — enable

### Install via ADB

Connect your Pixel 6 Pro via USB, then:

```bash
# Verify the device is detected
adb devices

# Install the debug APK
adb install app/build/outputs/apk/debug/app-debug.apk

# Or install a release APK
adb install app/build/outputs/apk/release/app-release-unsigned.apk
```

### Install via Android Studio

1. Connect your Pixel 6 Pro
2. In Android Studio, select your device in the toolbar
3. Click **Run** (▶) — it builds and installs automatically

### Wireless debugging (Android 11+)

1. **Settings → System → Developer options → Wireless debugging** — enable
2. **Pair device** — note the IP:port shown
3. ```bash
   adb pair <IP>:<PORT>   # enter the pairing code shown on screen
   adb connect <IP>:5555  # connect
   adb install app-debug.apk
   ```

## Importing your accounts

Modern SDA uses the standard `.mafile` format exported by the original [Steam Desktop Authenticator](https://github.com/Jessecar96/SteamDesktopAuthenticator).

1. Locate your `.mafile` files (usually in `SDA/maFiles/`)
2. Open the app → tap the **+** button
3. Paste the `.mafile` JSON content into the dialog
4. Optionally enter your Steam password if you want automatic re-login when sessions expire
5. Tap **Import**

The app reads the following fields from the `.mafile`:
- `account_name`, `steam_id`, `shared_secret`, `identity_secret`
- `device_id`, `Session` (for initial session data)

## Confirmations

Navigate to the **Confirmations** tab after selecting an account. The list loads automatically. Pull down to refresh or wait for the background auto-refresh (if enabled in Settings).

## Architecture

```
app/
├── data/
│   ├── db/          Room database (accounts)
│   ├── model/       Account, Confirmation, MafileData
│   ├── preferences/ DataStore (settings)
│   └── repository/  AccountRepository, ConfirmationRepository
├── di/              Hilt modules (NetworkModule, DatabaseModule)
├── domain/steam/    SteamLogin, SteamTotp, SteamConfirmations, ProtoUtils
└── ui/
    ├── screens/     Accounts, Confirmations, Settings (MVVM)
    ├── components/  AccountCard, TotpCodeDisplay
    ├── navigation/  Type-safe Compose Navigation routes
    └── theme/       Material 3 theme
```

## Security notes

- Credentials are stored in an unencrypted Room database on the device. Protect your device with a screen lock.
- Device backups are disabled (`allowBackup=false`) to prevent credentials appearing in Android cloud backups.
- All communication goes directly to `api.steampowered.com`, `login.steampowered.com`, and `steamcommunity.com` — no third-party servers.
- Release builds have all debug logging stripped and HTTP traffic is not intercepted.

## License

This project is provided as-is for personal use. Steam and the Steam logo are trademarks of Valve Corporation.
