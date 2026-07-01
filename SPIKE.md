# Phase 0 Spike

A minimal Compose/Material 3 app that validates the three riskiest assumptions from
`../ANDROID_APP_SPEC.md` before committing to the full architecture.

## What it exercises
1. **Bangumi login + credential extraction** — a WebView logs into `chii.in`, captures
   session cookies (shared `CookieManager`), and extracts `uid` + `formhash`
   (`ui/LoginWebView.kt`). If the landing page has no `formhash`, it navigates once to
   `/dollars` to get it.
2. **Posting to Bangumi** — done *inside the WebView* (same-origin `fetch` to
   `/dollars?ajax=1`, see `MainActivity.buildPostJs` + the `AndroidPost` bridge in
   `ui/BangumiWebView.kt`), then confirms via `POST /api/v1/messages/confirm`.
   Request shape matches a real captured post: body is **just `message=…` (no
   formhash)** + `X-Requested-With: XMLHttpRequest`; auth is entirely via the
   `chii_auth` / `chii_sec_id` cookies the browser sends automatically.
   > First attempt used OkHttp with cookies copied from `CookieManager`; Bangumi
   > returned `200` **with the full HTML page** and silently ignored the post — the
   > httpOnly session cookie/CSRF context isn't reproduced faithfully outside the
   > origin. Posting from within the WebView (exactly like the userscript) is the fix.
   > If posting still returns the HTML page on `chii.in`, try switching
   > `Config.BGM_HOST` to `https://bangumi.tv` (the host of the known-good request).
3. **Realtime + reads** — `net/RestApi.kt` loads recent messages; `net/DollarsWs.kt`
   opens `wss://rd.ry.mk/ws`, sends `identify`, heartbeats, and renders `new_messages`
   + `online_count_update` live.

## Build / run
```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk   # AGP 8.13 needs JDK 17–21, not 26
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Or open in Android Studio and Run.

## Verified on-device (2026-07-02) — all three goals PASS
- [x] WebView login → session bar shows `Logged in as <name> (uid …)`; `CHOBITS_UID`
      extraction works against real HTML.
- [x] Sending a message → `WebView POST -> ok=true status=200 head={"status":"ok"}`,
      then the message appears (WS echo / `Confirmed`). Body is `message=` only + the
      session cookies; no formhash required.
- [x] Feed populates on launch and updates live (green dot = WS connected, online count).

## Domains
Bangumi has three interchangeable domains — `bangumi.tv`, `bgm.tv`, `chii.in` — that
share one backend but use **separate cookies (per-domain)** and different CDNs. Log in
and post on the **same** domain. The spike standardizes on `chii.in` (`Config.BGM_HOST`).

## Notes / known simplifications
- Messages render as **raw BBCode** (no renderer yet — Phase 1).
- No reconnect/gap-recovery, avatars, reactions, or auth token — out of spike scope.
- Toolchain: AGP 8.13.2, Kotlin 2.2.20, Compose BOM 2025.10.01, Gradle 8.14.3,
  compileSdk/buildTools 36, minSdk 26.
