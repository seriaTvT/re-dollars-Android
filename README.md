# Re:Dollars — Android

Native Android client for [Bangumi Dollars](https://bgm.tv/dollars), the realtime chat room on
[Bangumi](https://bgm.tv). Kotlin + Jetpack Compose + Material 3.

Reading and realtime updates go through the [re-dollars-backend](../re-dollars-backend) service;
posting goes directly to Bangumi using your logged-in session, the same way the
[re-dollars-preact](../re-dollars-preact) userscript does. See
[ANDROID_APP_SPEC.md](../ANDROID_APP_SPEC.md) for the full technical spec this app was built from.

## Features

- Live message stream over WebSocket, with REST-backed history/scrollback and gap recovery on reconnect
- Sending messages with optimistic bubbles, send/confirm, and retry on failure
- Replies (quote), reactions, edit/delete for your own messages
- BBCode rendering: text styles, links, `[user]` mentions, `[quote]`, `[img]`/`[video]`/`[audio]`, `[code]`, `[mask]` spoilers, smilies, and BMO emoji
- Image lightbox, media gallery, in-app search
- Presence (online count, typing indicators), push notifications (FCM), voice message recording
- Per-user blocking, account sheet, Material 3 theming with dynamic color and light/dark support

## Requirements

- Android Studio (current stable)
- JDK 17
- Android SDK: minSdk 26, targetSdk 36, compileSdk 37

## Setup

1. Open the `re-dollars-Android/` directory in Android Studio and let it sync Gradle.
2. Push notifications require Firebase: drop your own `app/google-services.json` in place of/next
   to the existing one, or leave it as-is if you're not touching FCM. The build stays green either
   way (the `google-services` plugin is only applied if the file exists).
3. Release builds are signed using `release.keystore` at the project root, with credentials from
   `local.properties` (`RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`) or the
   matching environment variables. Not needed for debug builds.
4. Backend/auth endpoints are hardcoded in
   [`Config.kt`](app/src/main/java/mk/ry/redollars/net/Config.kt) — edit there to point at a
   different backend or Bangumi host.

## Build & run

```bash
./gradlew assembleDebug      # debug APK
./gradlew installDebug       # install on a connected device/emulator
./gradlew assembleRelease    # signed release APK
```

## Authentication

There's no separate account system — login happens inside an in-app WebView against Bangumi
itself (`bgm.tv`), and posting reuses that browser session (cookies + `formhash`). A second,
one-time login via [rymk-auth](https://auth.ry.mk) obtains a backend JWT, needed for reactions,
edit/delete, read state, uploads, and push registration. See §3 of the spec for details.

## Architecture

MVVM with a single-activity Compose UI:

```
app/src/main/java/mk/ry/redollars/
 ├─ net/       REST client, WebSocket client, models, config, uploads
 ├─ data/      MessageRepository — merges WS events + REST paging + Room cache
 ├─ di/        Hilt modules
 ├─ ui/
 │   ├─ chat/    ChatScreen and its sheets (account, block manager, gallery,
 │   │            notifications, reactions, search, typing indicator, ...)
 │   ├─ render/  BBCode → Compose renderer, image/video/audio, smilies
 │   └─ theme/   Material 3 theme
 ├─ bmo/       BMO emoji codec/renderer (Bangumi's custom emoji system)
 ├─ push/      FCM messaging service
 └─ voice/     Voice message recording
```

`ChatViewModel` drives `ChatScreen`; `MessageRepository` is the single source of truth, backed by
Room and kept in sync by `DollarsWs` (WebSocket) and `RestApi`.
