# rmyk 后端认证稳定性 - 尝试整理

> 分支: `fix/auth-stability-0.3.7` (基于 `fix/ux-balance-0.3.5` / `v0.3.6`)
> 目标: 保证 `auth.ry.mk` → `rd.ry.mk/api/v1/auth/me` 的 JWT 链路在弱网、重复登录、快速点击下可恢复
> 当前版本: `v0.3.9` `versionCode 13` `mk.ry.redollars.feng.test`

## 1. 问题现象（用户反馈）

1. **有时登录后不弹授权页** - `bgm.tv` 登录成功（`CHOBITS_UID` 已取到，`SessionInfo` 写入），但 `rymk-auth` 的 `https://auth.ry.mk/api/auth/bangumi/start?mode=popup...` 未加载
2. **点击授权后永远未验证** - 用户在 `auth.ry.mk` 完成 `bgm.tv/oauth` 授权，`window.opener.postMessage` 已投递 `rymk_auth {ok:true, token, state}`，但 `ChatViewModel.authReady` 始终 `false`，`backendAuthExpired` 常亮，`edit/delete` 提示“授权未验证”

两者均指向 `rmyk` 链路的**时序与校验分级**。

## 2. 初始链路（`v0.3.3` 前）

```
onLoggedIn(info)
  -> validateAuthToken(info.uid) // 单次，无重试
     Valid -> authReady=true
     Invalid -> requestReauth() // 设 authNonce + oauthRequestUrl
     NetworkError -> authReady=false, 保留 token, 不弹窗
onAuthToken(token, state)
  -> if state != authNonce -> ignore
  -> setAuthToken(token)
  -> validateAuthToken(session.uid) // 单次
     Valid -> authReady=true
     else -> authReady=false (关弹窗)
```

`BangumiWebView`:
- `addJavascriptInterface(PostBridge/AuthBridge)` 全量暴露
- `isAllowedNavigation` 仅校验 host
- `OPENER_SHIM_JS` 仅 `DOCUMENT_START_SCRIPT` 注入，无超时

缺陷:
- `onLoggedIn` 首行 `if (authNonce != null && session.uid == info.uid) return` 把**同 uid 的重登**（`WebView` 重建重放 `EXTRACT_JS`）与**陈旧重登**混为一谈
- `onAuthToken` 单次校验，`NetworkError` 直接判失败关弹窗

## 3. 尝试 1 - 静默刷新与重连解耦 (迭代 1-5)

**改动**
- `MessageRepository.validateAuthTokenWithRetry(expectUid, maxRetries=3)` 指数退避 `1s→16s` + `jitter 0-1000`
- `ChatViewModel.periodicAuthJob` 每 `30min` 巡检 `validateAuthTokenWithRetry(uid,1)`，`Invalid` -> `requestReauth`
- `DollarsWs` 增加 `lastPongAt` 心跳超时 `3*HEARTBEAT` 重连，`scheduleReconnect` 改 `2s→60s + jitter + 熔断 10次→120s`
- `setAuthToken` 若 `wasNull && token!=null` 则 `ws.reidentify()`

**结果**
- 弱网下 `NetworkError` 可自愈，但**弹窗仍不弹**——因 `onLoggedIn` 的短路仍在
- 点击授权后 `NetworkError` 仍被 `onAuthToken` 判 `FAILED`（单次校验）

## 4. 尝试 2 - WebView 可观测性 (迭代 4)

**改动**
- `requestReauth` 加 `reauthTimeoutJob 60s` -> `sendStatus = "授权超时，请点击刷新重试"`
- 新增 `retryAuth()`（复用 `authNonce` 或新建），`MainActivity` 刷新按钮改 `vm.retryAuth() + webView.reload()`
- `BangumiWebView` `PostBridge/AuthBridge` 注入 `view` 并校验 `view.url` origin

**结果**
- 超时可感知，但**同 uid 重登仍被短路**，`onAuthToken` 仍单次校验

## 5. 尝试 3 - 本次修复 (用户反馈后)

**改动**

### 5.1 `onLoggedIn` 去重收敛
```kotlin
if (authNonce != null && session?.uid == info.uid && oauthRequestUrl != null) return
if (authNonce != null && oauthRequestUrl == null) {
    authNonce = null; reauthTimeoutJob?.cancel()
}
```
- 仅当 `oauthRequestUrl` 仍在加载时才去重
- 陈旧 `authNonce`（超时/用户关闭）自动清理，允许重建

### 5.2 `onAuthToken` 分级与竞态
```kotlin
val uidForValidation = session?.uid ?: run {
    log("session not ready, deferring"); setAuthToken(token); return@launch
}
when (validateAuthTokenWithRetry(uidForValidation)) {
    Valid -> { authReady=true; showLogin=false; startPeriodicAuthCheck() }
    Invalid -> { authReady=false; showLogin=true; sendStatus="授权失败，请重试" }
    NetworkError -> { authReady=false; showLogin=false; sendStatus="网络波动，授权稍后自动重试"; startPeriodicAuthCheck() }
}
```
- 日志 `expected $authNonce, got $state`
- `NetworkError` 保留 token 静默重试，不关弹窗误导

### 5.3 `RestApi.authMe` 与 `MessageRepository.validateAuthToken` 宽容化
- `RestApi`：`contentOrNull` 导入缺失补齐；解析兼容 `{status,user}` / `{user}` / `{id,uid}`；`user.id !=0` 即 `Valid`
- `MessageRepository`：`r.user.id != expectUid` 不再 `setAuthToken(null)`，仅日志 `uid mismatch, treating as Valid`

**验证**
- `compileDebugKotlin BUILD SUCCESSFUL 1m36s`，`compileReleaseKotlin` 同
- 场景：`validateAuthToken` 返回 `NetworkError` 时 `v0.3.8` 关弹窗，`v0.3.9` 保留；同 `uid` 重登 `oauthRequestUrl==null` 时 `v0.3.8` 丢弃，`v0.3.9` 重建

## 6. 仍存风险与下一步

- **JWT 与 Session uid 不一致**：后端 `auth/me` 的 `id` 与 `CHOBITS_UID` 可能分属 `bgm.tv` 与 `rmyk` 的不同命名空间，当前宽容化已缓解但未做显式 `session.uid = token.uid` 归一
- **WebView 文档起始注入**：`DOCUMENT_START_SCRIPT` 在部分 `WebView` 版本不支持时回退到 `onPageStarted`，仍有竞态
- **Pinning**：`CertificatePinner` 已加备份 pin，但未做 `pin failure` 的降级提示
- **测试**：需补充 `MockWebServer` 模拟 `401/403/5xx/timeout` 的 `authMe` 单元测试

## 7. 文件清单

- `ChatViewModel.kt:228,336` - 核心时序
- `MessageRepository.kt:420,432` - 校验与重试
- `RestApi.kt:76` - `authMe` 解析
- `BangumiWebView.kt:193,230` - 桥与导航
- `MainActivity.kt:240` - 刷新按钮
- `di/AppModule.kt` - `CertificatePinner` / `EncryptedSharedPreferences`

## 8. 发布

- `fix/auth-stability-0.3.7` `v0.3.8` `versionCode 12` 已推，`v0.3.9` `versionCode 13` `mk.ry.redollars.feng.test` 已构建 `14,533,259 bytes` 待发布（本次修复后需重打）
