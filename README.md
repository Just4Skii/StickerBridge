# StickerBridge ⚡

> **Bridge your favorite TikTok stickers directly into WhatsApp with a single tap.**

StickerBridge is an Android application engineered to extract, normalize, and export static and animated stickers from TikTok into WhatsApp sticker packs. It bypasses walled gardens, avoids costly app store paywalls, and operates seamlessly on all Android devices (Android 8.0+ through Android 15+).

---

## 🌟 Key Features

- **⚡ 1-Tap Auto-Scan:** Automatically scans TikTok's internal media storage and cache directories (`com.zhiliaoapp.musically`) for user-saved and favorited stickers.
- **🎞️ Lossless Animated WebP Preservation:** Custom VP8X/ANMF parser that guarantees 512×512 square canvas framing while preserving full frame animations, frame durations, and sub-500KB compliance required by WhatsApp.
- **🔗 Smart Link & Carousel Extraction:** Paste any TikTok video or photo carousel link to automatically extract all slides and media assets. Automatically pads sets of 1–2 stickers to satisfy WhatsApp's mandatory 3-sticker pack minimum.
- **📦 Official WhatsApp Sticker Provider:** Native Android `ContentProvider` compliant with WhatsApp's official Sticker Specification (`com.example.tiktoktowhatsappstickers.stickercontentprovider`).
- **🎨 Modern Jetpack Compose UI:** Reactive, smooth Material 3 user interface featuring dark mode, instant pack previews, and direct WhatsApp export actions.
- **🌐 Universal Android Support:** Compatible with Android 8.0 (Oreo / API 26) all the way to Android 15+ (API 35).

---

## 🏗️ Project Architecture

```
tiktok-whatsapp-stickers/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── AndroidManifest.xml
│   │   │   ├── java/com/example/tiktoktowhatsappstickers/
│   │   │   │   ├── MainActivity.kt
│   │   │   │   ├── core/
│   │   │   │   │   ├── contentprovider/
│   │   │   │   │   │   └── StickerContentProvider.kt    # Serves stickers to WhatsApp via ContentProvider
│   │   │   │   │   ├── engine/
│   │   │   │   │   │   └── WebpProcessingEngine.kt      # VP8X / ANMF animated & static 512x512 engine
│   │   │   │   │   ├── ingestion/
│   │   │   │   │   │   └── TikTokIngester.kt            # Web link parsing, carousel unescaping & padding
│   │   │   │   │   ├── scanner/
│   │   │   │   │   │   └── TikTokCacheScanner.kt        # 1-Tap auto-discovery in TikTok media storage
│   │   │   │   │   └── whatsapp/
│   │   │   │   │       └── WhatsAppIntegration.kt       # Intent launcher for official WhatsApp import
│   │   │   │   │   ├── data/
│   │   │   │   │   │   ├── model/StickerPack.kt         # Sticker pack & sticker data definitions
│   │   │   │   │   │   └── storage/StickerStorage.kt    # Local persistence & pack management
│   │   │   │   │   ├── theme/                           # Material 3 colors, typography & theming
│   │   │   │   │   └── ui/
│   │   │   │   │       ├── main/                        # Home screen & ViewModel
│   │   │   │   │       └── pack_detail/                 # Pack inspector & preview
│   │   │   │   └── res/                             # Icons, themes, layouts, and XML rules
│   │   │   └── test/                                # Unit & UI test suites
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── gradle/
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

---

## 🚀 Building & Running

### Prerequisites
- **Android SDK:** API 35 (Android 15) compile SDK, Minimum API 26 (Android 8.0)
- **JDK:** Java 17 or Java 21
- **Gradle:** 8.x+ (bundled via Gradle Wrapper)

### Build Debug APK
```bash
./gradlew assembleDebug
```
The output APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

### Install on Device via ADB
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 📲 How to Use

1. **Auto-Scan Saved Stickers:**
   - Open **StickerBridge**.
   - Tap **"1-Tap Auto-Scan"**.
   - Review detected stickers and tap **"Add to WhatsApp"**.
2. **Import via Link / Carousel:**
   - Copy a TikTok video or photo carousel link.
   - Paste it into the link input box and tap **"Fetch Stickers"**.
   - Preview and export to WhatsApp.

---

## 📄 License
Open source under the [MIT License](LICENSE).
