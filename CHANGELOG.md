# Changelog

v0.0 — v0.3.18 为旧版本号方案下的历史发布说明（保留备查）。
自 v1.2 起改用上游版本号方案（versionName 1.2 / versionCode 3），发布由 GitHub Actions 自动完成。

## v0.3.18 — 让布布变大吧！  (2026-08-28)

新功能：调整 Musume / Blake 动态表情在聊天中的显示大小，versionCode 22。

## 新功能
- **让布布变大吧**：界面设置新增三档选择——
  - **小**：当前大小（原版 2.6em）
  - **中**：两倍边长（5.2em）
  - **大**：四倍边长（10.4em）
- 设置即时生效并持久保存，切换后聊天中的布布立刻变大

## 说明
- applicationId：`mk.ry.redollars.mod`（可与原版共存）
- mod.keystore 签名

---

## v0.3.17 — 滑动回复手感调优  (2026-08-28)

调低 v0.3.16 中过高的滑动回复触发门槛，versionCode 21。

## 调整
- **接管更快**：手势判定门槛从 3× 触摸容差降到 1.5×，消除拖动开始时的无响应死区（防误触滑屏主要靠 2:1 方向优势判定，保持不变——真实回复拖动轻松通过，滚动漂移永远不会）
- **跟随更跟手**：弹性阻尼从 150dp 降到 90dp，触发回复所需拖行距离从约半屏（~165dp）降到约 1/4 屏（~99dp），接近 Telegram 手感

## 说明
- applicationId：`mk.ry.redollars.mod`（可与原版共存）
- mod.keystore 签名

---

## v0.3.16 — 滑动回复手势优化  (2026-08-28)

修复滑动回复与列表滚动的手势冲突，versionCode 20。

## 修复
- **滑屏翻页不再被打断**：此前只要滑动方向略有横向偏差，消息气泡就会抢选手势触发拖动回复，导致翻页中断。现在气泡只在横向意图明确时（横向位移达到 3 倍触摸容差、且 2:1 压过纵向漂移）才接管手势；纵向滑动直接交还列表滚动，横向拖动回复功能不受影响。

## 说明
- applicationId：`mk.ry.redollars.mod`（可与原版共存）
- mod.keystore 签名

---

## v0.3.15 — P3 收尾修复（推荐）  (2026-08-28)

P1/P2/P3 三轮修复的收尾版本，versionCode 19。**推荐使用本版本。**

## 修复（P3）
- **大文件上传**：移除 60 秒总超时（原实现下移动网络实际最多传 ~70MB，与 200MB 承诺矛盾）；单次读写空闲超时保留
- **死连接检测**：WebSocket 心跳加入 pong 超时看门狗（2× 心跳间隔无回应即重连），不再依赖共享 OkHttp readTimeout 的副作用
- **数据库迁移兜底**：漏写迁移或安装旧版 APK 时清缓存重建，绝不再启动崩溃（消息缓存可自动重建）
- **发送队列**：WebView 返回未知 pendingId 时忽略而非误删队头，避免发送状态错乱
- **UA 版本号**：User-Agent 携带真实 versionName（此前一直是硬编码的 /0.2）
- **BBCode 解析**：内联递归深度上限 32，超深嵌套消息不再可能打爆调用栈

## 说明
- applicationId：`mk.ry.redollars.mod`（可与原版共存）
- mod.keystore 签名
- 本版本起，安装旧版 Release 回滚也不会因数据库版本崩溃

---

## v0.3.14 — P2 资源与并发修复  (2026-08-28)

在 v0.3.13 基础上的 P2 修复版本，versionCode 18。

## 修复（P2）
- **内存占用**：视频首帧缓存改为按字节计费（约 24MB 上限），不再可能堆出数百 MB 导致 OOM
- **取帧卡死**：远程视频取帧加 12 秒有界等待（守护线程池），慢主机不再挂住 IO 线程（原 runBlocking+withTimeoutOrNull 无法取消阻塞的原生调用，已替换）
- **线程安全**：typingClearJobs / probedDurations 换成 ConcurrentHashMap，presenceSubscribed 加 @Volatile
- **草稿持久化**：手打文本现在实时持久化（进程被杀后可恢复），此前只有表情/提及等程序化插入会被保存

## 说明
- applicationId：`mk.ry.redollars.mod`（可与原版共存）
- mod.keystore 签名

---

## v0.3.13 — P1 稳定性修复  (2026-08-28)

基于 v0.3.12（合并 fix/auth-stability-0.3.7 全部功能）的 P1 修复版本，versionCode 17。

## 修复（P1）
- **语音消息丢失**：语音还在上传时点发送会静默丢失语音，现在上传中禁用发送，上传完成后自动可发
- **搜索页崩溃**：offset 分页遇新消息插入会返回重复 id 导致 LazyColumn 崩溃，已按 id 去重
- **分页永久失效**：一次网络失败不再被当成到底——历史翻页/图片墙/搜索失败后可无限重试（按钮显示加载失败，点击重试）

## 说明
- applicationId：`mk.ry.redollars.mod`（可与原版共存）
- mod.keystore 签名

---

## v0.3.12  (2026-08-27)

### v0.3.12 界面设置与聊天交互修复

- 新增「界面设置」面板（顶栏 ⋮ 菜单）：显示自己的头像/用户名、自己的消息靠右开关、显示他人头像、自动加载图片与视频预览、显示贴贴
- 自己的消息移到左侧后，滑动回复的方向随消息所在侧自动镜像（左侧右划、右侧左划）
- 图片/贴图长按弹出气泡快捷菜单（回复、复制、编辑、删除）
- 关闭媒体预览后 [img] 变为点击加载占位框、视频不再抓取首帧海报，节省流量
- versionCode 16 / versionName 0.3.12 / package mk.ry.redollars.feng.test

---

## v0.3.11  (2026-08-27)

### v0.3.11 lightbox brightness fix

- 图片预览始终维持正常亮度
- 移除顶部黑色渐变遮罩，以及下载/收藏按钮后的半透明黑色胶囊背景
- 关闭/下载/收藏按钮保留自动隐藏，缩放/拖拽/双击交互不变
- versionCode 15 / versionName 0.3.11 / package mk.ry.redollars.feng.test

---

## v0.3.10  (2026-08-26)

### v0.3.10 token-login persistent auth + bridge stability

- rymk-auth JWT 通过 /auth/token-login 交换为长期 RD token，并存入加密 preferences
- 保留 JWT 供 up.ry.mk 图片上传；启动时静默升级已保存的 JWT
- PostBridge/AuthBridge 回调统一回到主线程，并增加精确来源校验
- 重试改为生成新 nonce/URL，不再依赖 WebView.reload() 时序
- 增加 auth mutex / revision / session generation，避免旧请求覆盖新登录
- 迁移旧明文 token 到加密 preferences；授权日志不再输出完整 JWT
- 新增 5 个认证 API 单元测试
- versionCode 14 / versionName 0.3.10 / package mk.ry.redollars.feng.test

---

## v0.3.9  (2026-08-26)

### v0.3.9 fix rmyk popup timing

- Fix onLoggedIn early return causing login without popup for same uid
- Fix onAuthToken NetworkError handling with retry and session defer
- WebView refresh now calls retryAuth, add 60s timeout
- versionCode 13 / versionName 0.3.9 / package mk.ry.redollars.feng.test

---

## v0.3.8  (2026-08-25)

### v0.3.8 fix rmyk popup timing

- Fix onLoggedIn early return causing login without popup for same uid
- Fix onAuthToken NetworkError handling with retry and session defer, show user-friendly messages
- WebView refresh now calls retryAuth, add 60s timeout
- versionCode 12 / versionName 0.3.8 / package mk.ry.redollars.feng.test

---

## v0.3.7  (2026-08-25)

### v0.3.7 rmyk auth stability (10 iterations)

- Diagnose JWT lifecycle, silent refresh with periodic check (30min) + validateAuthTokenWithRetry (exponential backoff + jitter)
- WS/REST decoupling: setAuthToken reconnects WS, add reidentify()
- WebView: add reauthTimeoutJob (60s) + retryAuth(), MainActivity refresh calls retryAuth
- Backoff: WS 2s->60s with full jitter + circuit breaker, REST retry with decorrelated jitter
- EncryptedPrefs, CertificatePinner backup pins, network_security_config, Upload idempotency

versionCode 11 / versionName 0.3.7 / package mk.ry.redollars.feng.test

---

## v0.3.6  (2026-08-25)

### v0.3.6 Lightbox fix

- Lightbox: allow temp shrink to 0.55x with spring-back, auto-hide chrome (2.2s) to avoid covering large image top, moved actions to bottom capsule
- Chat: fix far jump vm scope (onJumpToMessage)

versionCode 10 / versionName 0.3.6 / package mk.ry.redollars.feng.test (side-by-side with main)

---

## v0.3.5  (2026-08-25)

### v0.3.5 (10 iterations round 2)

**Security/UX Balance**: revert WebView path allowlist to host-only (keep bridge origin check), Video timeout + http check, Search far-jump window, EncryptedPrefs fallback, CertificatePinner backup pins, network_security_config pin-set, Upload idempotency

**Usability**: draft dual persist, video retry, MessageRow a11y

**Release**: fix branch test package mk.ry.redollars.feng.test, versionCode 9 / versionName 0.3.5

---

## v0.3.4  (2026-08-25)

### v0.3.4 (10 iterations)

**Security**: WebView bridge origin check, file access disabled, path-aware allowlist, EncryptedSharedPreferences, CertificatePinner + network_security_config, data_extraction_rules, Upload idempotency, Room downgrade guard

**Usability**: draft dual persist (SavedStateHandle+SharedPreferences), video thumbnail retry + progress, MessageRow a11y, composer improvements

**Release**: fix branch isolated test signature (mk.ry.redollars.feng.test, release-test.keystore), main retains upstream signature

versionCode 8 / versionName 0.3.4

---

## v0.3.3  (2026-08-25)

Re:Dollars 0.3.3

- 草稿：进程被杀后仍保留输入框草稿（SavedStateHandle 持久化）
- 视频：首帧缩略图加载中显示进度，失败显示播放角标
- 登录：登录/授权页新增刷新按钮，网络错误可手动重试
- 基于 mk.ry.redollars.feng + 0.3.1 起的新签名

---

## v0.3.2  (2026-08-25)

### v0.3.2 hotfix

- Video: revert LruCache to 48 and hashCode, fix preview freeze
- Search: revert displayLimit cap, restore far history paging
- Signature: keep new keystore (android/release) - v0.3 users need clean install (uninstall old) due to v0.3.1 rotation

versionCode 6 / versionName 0.3.2

---

## v0.3.1  (2026-08-25)

### v0.3.1 resilient bugfix

P1: guard send queue with correlated pendingId, restore draft on HTML/!ok, fix notification jump to newer messages (syncNewer)
P1: guard site blocklist from incomplete resolves, restrict media schemes
P2: close leaked WS on reconnect/background, add pong timeout, cap resource downloads, fix upload size bypass, dedupe anonymous retry, isolate account state on logout, add Room migrations, serialize reactions/edits with Mutex
P3: cap displayLimit/LruCache, fix hash collisions, bound FCM/BMO fetches, fix WebView reload loss on rotation

versionCode 5 / versionName 0.3.1

---

## v0.3  (2026-08-25)

Re:Dollars 0.3

- 鉴权：/auth/me 三态校验，过期 token 自动清除并触发 rymk-auth 重新授权；网络抖动不再误删 token
- 发帖：检测 Bangumi cookie 过期（401/403 或跳转 /login），自动还原草稿并弹出登录
- 图片上传：带失效 JWT 时自动匿名重试，插图不再被卡
- 编辑/删除：操作前自动校验并恢复授权；推送注册随授权恢复重试
- 顶栏：后端授权未验证时显示提示
- 视频缩略图：图片墙与输入框气泡均显示视频首帧
- 基于 mk.ry.redollars.feng + 新签名，可与原包共存

---

## v0.2  (2026-08-24)

Re:Dollars 0.2

- 搜索：点击结果直接跳转到聊天对应消息（自动翻页定位）
- 图片墙：视频项显示封面+播放角标，点击全屏播放
- 顶栏：断开时点按状态栏手动重连（显示"点击重连"）
- 输入框：草稿中的图片/视频标签显示可点按预览气泡，多张并排，点击查看大图/全屏视频
- 基于 mk.ry.redollars.feng + 新签名，可与原包共存

---

## v0.1  (2026-08-24)

Re:Dollars 0.1

- 大图预览新增「下载」按钮：原图保存到系统相册 Pictures/ReDollars（Android 10+ 免权限，8/9 请求存储权限）
- 长按用户头像：自动在输入框填入可读 @用户名（发送时转为用户链接），解析失败时回退为 BBCode
- 构建基于全新签名与应用ID mk.ry.redollars.feng，可与原包共存

---

## v0.0  (2026-08-24)

Re:Dollars Android v0.0 (fork release)

- Package: mk.ry.redollars.feng (coexists with original mk.ry.redollars)
- Signed with a new key
- Debug/release build environment: JDK 21, AGP 9.2.1, Gradle 9.6.1

---

