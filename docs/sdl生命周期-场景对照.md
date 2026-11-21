# SDL 生命周期：本项目实际行为 vs 官方推荐

> **用途**：把「各场景到底发生了什么」和「SDL 官方认为该怎样」摆在一起，
> 出问题时一眼定位在哪一层、是不是我们偏离了官方。
>
> **职责**（2026-11-21 起）：本项目 SDL 生命周期控制由我专门负责。
> 动 `SdlService` / `SdlReceiver` / `BootReceiver` / `SyncRouterService` 之前先读这份文件。
>
> **2026-11-21 重要更新**：官方文档原文（6 份）已由用户提供，本文从"二手转述"
> 升级为**有原文依据**。原文要点见第 1、2 节，风险见第 7 节。

---

## 0. 证据来源

| 等级 | 来源 | 说明 |
|---|---|---|
| 🟢 **官方原文** | Integration Basics - Java | 生命周期主文档，本文件第 1/2 节的主要依据 |
| 🟢 **官方原文** | Connecting to an Infotainment System | `MultiplexTransportConfig(context, APP_ID)` |
| 🟢 **官方原文** | Trusted Router Service (FAQ) | 信任列表的机制，解释了 `bindService` 从哪来 |
| 🟢 **官方原文** | SDL Notifications (FAQ) | 通知可以被用户长按关闭，属正常现象 |
| 🟢 **官方原文** | Multiple Processes (FAQ) | **Router 独立进程的目的：两边互不拖垮** |
| 🟢 **官方原文** | Template Custom Buttons | 软键、ID 范围、能不能和 ScreenManager 混用 |
| 🟢 **硬证据** | 反编译 `sdl_android 5.8.0` AAR | `SdlBroadcastReceiver` / `SdlRouterService` 字节码 |
| 🟢 **硬证据** | 实车落盘日志 | 2026-11-21 15:13~15:17、18:29~18:34 |
| 🟢 **硬证据** | 本项目代码 | 行号可核对 |

> 原文是用户从官网另存的文本（`smartdevicelink.com` 是 JS 渲染，直接抓只拿到导航目录）。
> 路径记在 `AGENTS.md` §1.2 的"已知的坑"附近，不进本仓库。

---

## 1. 官方划定的职责边界（原文要点）

### 1.1 Service 是唯一的宿主

> "A SmartDeviceLink Service should be created to **manage the lifecycle of the SDL session**.
> The SdlService should build and start an instance of the SdlManager which will automatically
> connect with a head unit when available."
>
> "**SDL apps should only use a foreground service to host the SDL implementation.**"
> （Activity 不行 —— Android 10 限制后台启动 Activity）

→ 我们正是 `SdlService` 承载 `SdlManager`。✅

### 1.2 Router Service 必须在独立进程，且**故意隔离**

> "The SdlRouterService will listen for a connection with an SDL enabled module.
> **When a connection happens, it will alert all SDL enabled apps** that a connection has been
> established and they should start their SDL services."

> "The router service is designed to live **outside the normal lifecycle of the app**…
> if the router service unexpectedly stops or crashes, **it will not take down the hosting app**.
> **This relationship also works in the opposite direction.**"

→ 这解释了实车上那个现象：**手机 App 崩了，车机通知却还显示 connected** ——
Router 是独立进程，它活着；死的是我们的主进程。**不是异常，是设计。** ✅

### 1.3 `SdlBroadcastReceiver` 是应用的唯一启动入口

> "The intent `sdl.router.startservice` is a custom intent that will come from the
> SdlRouterService to tell us that we have just connected to an SDL enabled piece of hardware."
>
> "**The `onSdlEnabled` method will be the main start point for our SDL connection session.**"

Manifest 必须监听这两个 action：

```
android.bluetooth.device.action.ACL_CONNECTED
sdl.router.startservice
```

→ 我们满足（`SdlReceiver` 声明了，且 `exported=true`）。✅
→ 另外我们还多声明了 `PACKAGE_ADDED/REPLACED/REMOVED`（官方没提，无害）。

### 1.4 `Application` 在多进程都会创建，必须自己加 flag

> "the Android OS creates **a new instance of the supplied Application class for each process**…
> your custom Application class needs to be ready to run on different processes."

官方示例用 `ActivityManager.getRunningAppProcesses()` 匹配 pid 判断是不是 router 进程。

→ 我们用 `Application.getProcessName()`（API 28+）或读 `/proc/self/cmdline`，
**比官方示例更新**，效果相同。✅

### 1.5 重连完全靠官方链路，没有应用层重试

> "The `onDestroy()` method from the `SdlManagerListener` is called whenever the manager
> detects some disconnect in the connection, **whether initiated by the app, by SDL,
> or by the device's connection**."

→ 所以 `onDestroy` 是「断开」的**唯一信号源**，不管谁先断。
我们的 `handleConnectionClosed()` 正是挂在这里。✅

---

## 2. 官方 Must / Note 清单 vs 我们的落实 ★核心表

> 这张表是本文最重要的部分。「✅」= 严格照做；「⚠」= 有理由的偏离；「❗」= **未落实的官方要求**。

| # | 官方要求 | 级别 | 我们的实现 | 判定 |
|---|---|---|---|---|
| 1 | Service 声明 `exported="true"` + `enabled="true"` + `foregroundServiceType="connectedDevice"` | Note（API 31） | 已补齐 | ✅ |
| 2 | `onCreate` 里 `startForeground`（Android O+） | Note | `startAsForeground()` | ✅ |
| 3 | `onDestroy` 里 `stopForeground(true)`，**不要把通知留在通知栏** | Note | `stopForeground(STOP_FOREGROUND_REMOVE)` | ✅ 等价 |
| 4 | 官方还 `deleteNotificationChannel` | 示例代码 | 我们**不删渠道** | ⚠ 无害（留渠道反而避免反复创建） |
| 5 | `onStartCommand` 里 `if (sdlManager == null)` 才建 | 示例 | `needRebuild()`：null **或** 已 DISCONNECTED 都重建 | ✅ 更严格 |
| 6 | `return START_STICKY` | 示例 | 保留 | ✅ |
| 7 | `SdlManagerListener.onDestroy()` 里 `stopSelf()` | 示例 | 一样 | ✅ |
| 8 | `SdlService.onDestroy()` 里 `sdlManager.dispose()` | **Must** | 有 | ✅ |
| 9 | `SdlManager` **不可复用**，断开后必须新建 | Note | 用「代际号」忽略旧对象迟到回调 | ✅ |
| 10 | `onSystemInfoReceived` 必须返回 `true`（AS 默认生成 false，会导致连不上） | **Must** | 返回 `true` | ✅ |
| 11 | `SdlBroadcastReceiver` 必须 `exported` | **Must** | 是 | ✅ |
| 12 | 覆写 `onReceive` 必须调 `super.onReceive` | **Must** | 我们**没覆写** `onReceive`，只覆写 `onSdlEnabled` | ✅ 不适用 |
| 13 | `defineLocalSdlRouterClass()` 返回本地 Router 类 | **Must** | 返回 `SyncRouterService.class` | ✅ |
| 14 | 本地 Router 类**必须命名为 `SdlRouterService`** | **Must** | 我们叫 `SyncRouterService` | ⚠ **偏离 Must**（实车能用，见 §7.2） |
| 15 | 本地 Router 类必须与 `SdlReceiver` **同包** | **Must** | 都在 `receiver` 包 | ✅ |
| 16 | Router Service 的 intent-filter `com.smartdevicelink.router.service` | **Must** | 有 | ✅ |
| 17 | Router Service 必须在 `com.smartdevicelink.router` 进程 | **Must** | `android:process="com.smartdevicelink.router"` | ✅ |
| 18 | `sdl_router_version` meta-data | 要求 | 有（`@integer/sdl_router_service_version_value`） | ✅ |
| 19 | `onSdlEnabled` 里 **S 及以上用 PendingIntent** 启动 | **Must**（API 31+） | **双路**：先 `startForegroundService`，再发 PendingIntent | ⚠ 有实车证据的偏离（§6 #3） |
| 20 | **API 34+ 要先检查 `AndroidTools.hasForegroundServiceTypePermission(context)`**，没有就 return | **Must**（API 34+） | **没有检查** | ❗ **未落实**（§7.1） |
| 21 | `getSdlServiceName()` 默认返回 `"SdlService"`，类名不同要覆写 | Note | 我们的类就叫 `SdlService`，默认值正确 | ✅ |
| 22 | `MultiplexTransportConfig` 连车机 | 要求 | `new MultiplexTransportConfig(ctx, APP_ID)` | ✅ |
| 23 | 官方示例传 `FLAG_MULTI_SECURITY_OFF` | 示例 | 我们**不传**（用默认安全等级） | ⚠ 见 §7.3（这直接关系到那个崩溃） |
| 24 | MainActivity.onCreate 里 `SdlReceiver.queryForConnectedService(this)` | 要求 | 有 | ✅ |
| 25 | Lock Screen 必须实现（否则不能上架） | **Must**（发布） | 没做 | ⚠ 不上架，接受 |
| 26 | 软键：**不要混用 ScreenManager 与手动 `Show`** | Note | 只用手动 `Show` | ✅ |
| 27 | 软键 ID `0~10000` 是 ScreenManager 保留区 | Note | 我们用 9001/9002/9003 | ⚠ 不冲突（不用 ScreenManager），但记一笔 |

---

## 3. 三个全局状态机

### 3.1 连接状态（`SyncLifecycle.State`）

```
DISCONNECTED ──startProxy()──> CONNECTING ──OnHMIStatus──> NONE
                                                          ↓ 车机选中
                                                      BACKGROUND
                                                          ↓ 用户切到本 App
                                                       LIMITED
                                                          ↓
                                                        FULL   ← 唯一能发 RPC 的等级
```

- 掉出 FULL 会沿原路退回，**连接不断**，只是没焦点
- 车机断链 / 应用 dispose / SDL 主动断 → 都会走 `SdlManagerListener.onDestroy()` → `DISCONNECTED`

### 3.2 服务的生命周期（官方语义）

```
onSdlEnabled → startForegroundService → onCreate（建通知）
             → onStartCommand（needRebuild? startProxy）
             → 连接期间常驻（START_STICKY）
             → SdlManagerListener.onDestroy → handleConnectionClosed → stopSelf()
             → onDestroy（stopForeground + flushNow + dispose）
```

### 3.3 软键的生命周期（**独立于上面两个**）

软键是 `Show.softButtons` 的一部分，**没有独立的存在形式**：

```
FULL + 数据变化 → flush() → Show(含 softButtons) → 车机画出来
掉出 FULL      → 不再发 Show → 车机自己清屏（我们不主动删）
回 FULL        → markDirty → 重新 Show → 软键重新出现
断开           → 会话结束 → 全部消失
```

官方对"删除软键"的说法（Template Custom Buttons）：

> "To delete soft buttons, simply pass the screen manager a new array of soft buttons.
> To delete all soft buttons, simply pass the screen manager an **empty array**."

→ 官方只提供"主动删"的手段，**没有要求 HMI 降级时必须删**。我们不主动删，可接受。

---

## 4. 场景逐个对照

### 4.1 上车首次连接

**触发**：手机与车机建立蓝牙 → 系统发 `ACL_CONNECTED`

| # | 谁 | 做什么 | 日志特征 |
|---|---|---|---|
| 1 | `BootReceiver` | 记日志 + 白名单，**什么都不做** | `[Boot] 收到: ...ACL_CONNECTED` |
| 2 | 库的 `SdlReceiver.onReceive` | `wakeUpRouterService()` → `ServiceFinder` → `startRouterService()`（异步 ~110ms） | `[Router] RouterService 上线` |
| 3 | `SyncGlanceApp.onCreate`（进程被拉起时） | `queryForConnectedService`（Application context） | `[Boot] 应用启动，补一次…探测` |
| 4 | 库 + 车机 | SDP → RFCOMM → 连上 Router | — |
| 5 | 库 | 回调 `onSdlEnabled` | `[SdlReceiver] ★ onSdlEnabled` |
| 6 | `SdlReceiver` | 双路启动服务 | `已直接启动 SdlService` |
| 7 | `SdlService` | `onCreate` → 通知 → 前台 | `[SyncPlay] 服务创建` |
| 8 | `SdlService` | `onStartCommand` → `startProxy()` | `启动时发现连接不可用…重建连接` |
| 9 | `SdlService` | `SdlManager.build().start()` | `✓ SdlManager 已启动` |
| 10 | 车机 | `OnPermissionsChange` → `OnHMIStatus` NONE→BACKGROUND→LIMITED→FULL | `→ 已连接(FULL)` |
| 11 | `SdlService` | 首次 FULL → `initializeForConnection()`：建菜单、订阅 13 键、补发计时器 | `★ 进入 FULL —— 开始初始化` |
| 12 | `StateRenderer` | `flush()` → `Show`（3 个软键） | `[Render] Show[空闲] …` |

**软键**：FULL 后出现 `播放/暂停` | `-` | `计时` + 车机自带「更多」。

**官方**：🟢 完全一致 —— `onSdlEnabled` 是官方指定的唯一启动点；
`onStartCommand` 建 manager、`START_STICKY`、首次 FULL 初始化，全照官方。

**历史坑**：这里曾经在 `ACL_CONNECTED` 时由 `BootReceiver` 直接启动服务，
抢在 Router 之前 → `SdlManager` 7ms 自毁 → 车机卡「正在连接」2 分钟。已修（`fcbef0a`）。

---

### 4.2 上车恢复连接（下车 → 再上车）

| 阶段 | 发生什么 |
|---|---|
| 下车 | `SdlManagerListener.onDestroy()` → `handleConnectionClosed()` → `stopSelf()` → 服务销毁、通知消失（§4.3） |
| 中间 | **Router 进程可能还活着**（官方原文：它故意活在应用生命周期之外） |
| 再上车 | 与 §4.1 完全相同 |

**⚠ 已修的雷**：Router 还活着时，`queryForConnectedService` 会走信任列表校验
（`bindService`）。这个调用若发生在 **BroadcastReceiver 的 context** 里 →
`ReceiverCallNotAllowedException` → **闪退**。这解释了 18:31/18:33 两次崩溃。

> 官方原文（Trusted Router Service FAQ）确认了这套机制：
> "Each app will retrieve and cache a list of Trusted Router Services from the back-end server…
> **if trusted it will bind to the Trusted Router Service.**"
> —— 也就是说 `bindService` 是**设计内行为**，只是库在广播上下文里做它，违反了 Android 规则。
>
> **官方文档里 `queryForConnectedService` 只出现在 `MainActivity.onCreate` 一处，
> 从来没说要放在广播里。** 所以我们的修复（删掉 `BootReceiver` 的调用）
> 就是回到官方用法。

**本车特别注意**：SYNC 1.1 不会自动发现 App，必须人工在车机上点「查找移动应用」，
或按手机上的「唤醒车机发现」重开窗口。**这是车机行为，不是 bug。**

**软键**：下车后随会话结束消失；再上车 FULL 后重建。

---

### 4.3 车机断开连接（熄火 / 蓝牙断）

**触发**：蓝牙链路断 → 库/传输层感知 → `SdlManagerListener.onDestroy()`
（官方原文：**任何一方断开**都会调这个回调）

```
① [Boot] 收到 ACL_DISCONNECTED                  ← BootReceiver 只记日志
② [Boot] SYNC 车机已断开（SYNC ）
③ [SyncPlay] 连接结束（原状态 FULL），按官方语义停掉 Service
     ［建连至今 XXXXms，RouterAlive=…，代际=…］   ← 诊断，见 §7.4
④ [Core] 发送通道已摘除（预览仍可工作）
⑤ [SyncPlay] 服务销毁（连接层退出，…）
```

**`handleConnectionClosed()` 逐项**：

| 动作 | 依据 |
|---|---|
| `closedOnce = true`（若之前不在 CONNECTING） | 只为通知文案 |
| `sdlManager = null` + `lifecycle.onProxyClosed()` | 官方：manager 不可复用 |
| `AmapLinkClient.setHeadUnitConnected(false)` | 省 CPU |
| `menusBuilt = false` | 官方：断开后车机侧菜单/订阅**全删** |
| `pendingTimerResend = true` | 回 FULL 补发 |
| `renderer.onDisconnected()` + `core.detachSender()` | 预览保留 |
| `updateNotification()` → 「未连接（连接关闭）」 | 我们的补充 |
| **`stopSelf()`** | 🟢 官方示例：`SdlService.this.stopSelf()` |

**`SdlService.onDestroy()`**：`stopForeground(STOP_FOREGROUND_REMOVE)` → `Log2.flushNow()`
→ `dispose()` → `instance = null`。

> ⚠ 这里**故意不**调 `NotificationCatcher.setMusicListener(null)` ——
> 那会把数据采集一起掐死（踩过）。

**软键**：随会话结束消失；手机通知也消失。

---

### 4.4 车机退出应用（在车机上按「离开 SyncPlay」）

**触发**：车机下发 `OnHMIStatus`，`hmiLevel` → **NONE**（连接还在！）

```
① OnHMIStatus: FULL → 已连接(NONE) firstRun=false ctx=MAIN
② [SyncPlay] ⚠ 失去焦点（NONE），进行中的操作已被车机终止
③ [AmapLink] 预览页已关闭（非 FULL 时停止解析）
④ 通知文案 → 「已连接（未选中）」
```

**`onLostFocus()` 只做三件事**：`menusBuilt = false`、`renderer.onDisconnected()`、
`pendingTimerResend = true`。**不 `stopSelf`** —— 用户可能再点回来。

**软键**：车机已切走，我们的软键不主动删也不会出现；回 FULL 时 `initializeForConnection()`
重建后恢复。

**官方**：🟢 一致（"非 FULL 不能发 RPC"，我们 `sendRpc` 有守卫）。
⚠ 官方文档没细说 `systemContext = MENU / VRSESSION / HMI_OBSCURED` 的差异，
我们统一当"不能发 RPC"处理，这是**推测而非官方结论**。

---

### 4.5 手机端崩溃 / 意外关闭

| 步骤 | 结果 |
|---|---|
| 崩溃瞬间 | `UncaughtExceptionHandler`：写 `[Crash]` + 堆栈 → `flushNow()` → 照常崩 |
| 进程死亡 | `SdlService.onDestroy()` **通常不执行** → `dispose()` 不执行 |
| 通知 | 随进程消失 |
| Router | **独立进程，可能还活着**（官方：故意隔离）→ 车机通知仍显示 connected |
| 车机 | 会话消失 → 回 AppLink 菜单 |

**恢复**：全靠官方链路（`ACL_CONNECTED` → 库唤醒 Router → `onSdlEnabled`；
或用户打开 App → `queryForConnectedService`）。**没有任何应用层重试** —— 官方也没有。

**软键**：随会话结束消失。

**官方**：🟢 一致的"无特殊处理"。而且官方原文（Multiple Processes）明确：
Router 与宿主应用**互不拖垮**，所以"App 崩了车机还显示 connected"是预期行为。

**这次修的短板**：以前崩溃时堆栈不落盘（`Log2.e` 只写 `getMessage()`，常为 null）。
现在带堆栈了 —— 车里没 adb 也能拿到证据。

---

### 4.6 手机端正常退出（用户划掉 App / 系统正常回收）

- `SdlService` 还在 → `onDestroy()`：`stopForeground(REMOVE)` → `flushNow()` → `dispose()`
- 已因断连 `stopSelf` 过 → `onDestroy` 早已执行，本轮无事
- `NotificationCatcher` 可能仍在（数据采集不依赖连接层）

**软键**：随会话结束消失。**官方**：🟢 一致（`dispose()` 是 Must）。

**顺序注意**：`flushNow()` 必须在 `dispose()` 之前，否则断开瞬间日志会丢。

---

### 4.7 手机端蓝牙关闭

| 步骤 | 谁 | 做什么 |
|---|---|---|
| 1 | `BootReceiver` | 收到 `STATE_CHANGED`，`state != ON` → **完全静默** |
| 2 | 蓝牙链路断 | 车机超时 → 库 → `SdlManager` onDestroy → **走 §4.3** |
| 3 | Router | 可能退出，发 `ROUTER_DEAD` → 通知变「未连接（蓝牙未连接）」 |

**软键**：同 §4.3。**官方**：🟢 一致（无需特殊处理）。

**可改进**：`STATE_OFF` 现在不打日志，排查时看不出是用户关的蓝牙。建议加一行。

---

### 4.8 应用安装 / 升级

`MY_PACKAGE_REPLACED` → `BootReceiver` 只记日志 → 进程被拉起 → `SyncGlanceApp.onCreate` 探测。
**官方无此 receiver**（我们自加，无害）。

⚠ debug 包**安装后第一次** `queryForConnectedService` 会抛 `RuntimeException`
（`IntegrationValidator` 报 `✕ ... SDL Receiver properly`）—— **这是误报**，
`cmd package query-receivers -a sdl.router.startservice` 已确认声明正确。**不要动 manifest。**

---

### 4.9 手机重启

`BOOT_COMPLETED` → 同 §4.8。

⚠ 国内 ROM 会在应用被杀后**重发假 `BOOT_COMPLETED`**
（实车 15:14:57 / 15:15:17 / 15:15:52 连发三次，而 `uptime` 已 6.2 天）。
以前每来一次建一个僵尸 `SdlManager`；现在只多一次无害查询。

---

## 5. 软键专项（跨场景）

### 5.1 软键不是独立对象

`StateRenderer.flush()` 的守卫：

```java
if (sender == null || lifecycle == null || !lifecycle.canSendRpc()) return;   // 非 FULL 不发
```

| HMI 等级 | 发 Show？ | 车机上看到什么 |
|---|---|---|
| `FULL` | ✅ | 我们的 3 个软键 + 车机「更多」 |
| `LIMITED` / `BACKGROUND` | ❌ | 车机自己的界面 |
| `NONE` | ❌ | 车机已切走 |
| 断开 | ❌ | 全部消失 |

### 5.2 布局与 ID

| 槽位 | 内容 | customButtonID |
|---|---|---|
| 1 | `播放/暂停` | 9001 |
| 2 | `-`（占位） | 9002 |
| 3 | `计时`（三态循环） | 9003 |
| 4 | 「更多」 | 车机自带，不是我们的软键 |

> 官方（Template Custom Buttons）：软键 ID `0~10000` 是 **ScreenManager 的保留区**，
> 手动发 `Show` 时"ids you use are **outside of this range**"。
> 我们用 9001~9003 落在保留区内 —— **但因为我们完全不用 ScreenManager**，
> 不构成冲突。**如果将来改用 ScreenManager，必须把 ID 挪到 10000 以上。**

### 5.3 各场景下按软键

| 场景 | 能按吗 | 会发生什么 |
|---|---|---|
| FULL | ✅ | `OnButtonPress{CUSTOM_BUTTON, 900x}` → `CommandRouter.handleSoftButton` → 本地动作 → `markDirty` → `Show` |
| 掉出 FULL | ❌ 看不到 | — |
| 刚回 FULL | ✅（可能短暂是旧键） | `initializeForConnection` / `markDirty("reFULL")` 会立刻重发 |
| 断连 | ❌ | — |
| 回调缺 `customButtonID` | — | 打 `⚠ 软键：回调未带 customButtonID` 并忽略 |

### 5.4 软键与计时器耦合

软键 9003 是车机上操作计时器的**唯一入口**，纯本地状态驱动：

- 状态在 `StateRenderer.timerMode`（正计时 / 暂停 / 清零），**不依赖连接**
- 断电断连后仍是上次的样子（预览器能看到）
- 断开期间改的计时器，`pendingTimerResend` 会在回 FULL 时补发
- 车机失焦会终止正在跑的时钟 → 回 FULL 必须 `resendTimerIfNeeded()`

---

## 6. 偏离官方清单（全量）

> 按 `AGENTS.md` §1.3：给清单，不用「全部」。

| # | 偏离 | 为什么 | 实车证据 |
|---|---|---|---|
| 1 | `queryForConnectedService` 在 **`SyncGlanceApp.onCreate`** 也调 | 应用可能因广播被拉起、界面没开，需补探测 | 🟢 多次成功 |
| 2 | 保留 `BootReceiver`（官方没有） | 记录启动来源 + 蓝牙白名单 | 🟢 |
| 3 | `onSdlEnabled` **双路启动**（`startSelf` + PendingIntent） | 官方二选一；踩过 `pendingIntent.send()` 返回成功但服务没起来 | 🟢 12:49 日志 |
| 4 | `RouterAlive` 跨进程广播（`ROUTER_ALIVE/DEAD`） | 5.8 没有 `isRouterServiceRunning()` | 🟢 通知依赖它 |
| 5 | `MainActivity` 的「唤醒车机发现」按钮 | 本车不自动发现 App | 🟢 |
| 6 | `closedOnce` + `ConnectionStatus` 六种文案 | 官方只要求前台通知，未规定文案 | — |
| 7 | `handleConnectionClosed` 里 `stopSelf()` 前先 `updateNotification()` | 让状态可见 | — |
| 8 | 崩溃兜底 `UncaughtExceptionHandler` | 只写日志，不吞异常 | — |
| 9 | 不 `deleteNotificationChannel`（官方示例删） | 留渠道避免反复创建 | — |
| 10 | Router 类命名 `SyncRouterService`（官方 **Must** 叫 `SdlRouterService`） | 早期命名沿用至今，实车能连 | 🟢 正常连接 |
| 11 | 不传 `FLAG_MULTI_SECURITY_OFF`（官方示例传） | 不擅自降低安全等级 | 🟢 能连，但见 §7.3 |
| 12 | 不做 Lock Screen（官方发布 Must） | 不上架 | — |

**结论**：偏离项**都不改变官方时序**。唯一踩过的雷是把「启动服务」也偏离到广播里（已撤回），
以及把「探测」偏离到广播里（已撤回）。

---

## 7. 未落实的官方要求 / 风险

### 7.1 ❗ API 34+ 缺 `hasForegroundServiceTypePermission` 检查（**最该修的**）

官方示例（Android UPSIDE_DOWN_CAKE = API 34 及以上）：

```java
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
    if (!AndroidTools.hasForegroundServiceTypePermission(context)) {
        DebugTool.logInfo(TAG, "Permission missing for ForegroundServiceType connected device.");
        return;                                   // ← 直接放弃启动
    }
}
```

**我们没有这个检查。** 后果：在 Android 14+ 上如果没有 `connectedDevice` 类型的前台服务权限，
`startForegroundService` 可能抛异常（被我们的 try/catch 吞掉）→ 服务起不来 → 车机超时。

**本车机实测**：Android 版本未确认；之前 15:14 那次 `startForegroundService` 失败过一次
（日志：本机测试时 `connectedDevice` FGS 类型被系统拒），说明这条**可能真会踩到**。

**建议**：补上这个检查（只加 4 行，不改时序）。

### 7.2 ⚠ Router 类名违反官方 Must

官方原文：

> "**The local extension of the `com.smartdevicelink.transport.SdlRouterService`
> must be named `SdlRouterService`.**"

我们叫 `SyncRouterService`。虽然实车一直能连，但这是明确的 Must。
改名影响面：`SyncRouterService.java` + Manifest + `defineLocalSdlRouterClass()` + 若干引用。

**建议**：**先不动**（能跑就不折腾），但如果以后出现 Router 相关的诡异问题，
这是第一个该排查的点。

### 7.3 ⚠ `FLAG_MULTI_SECURITY_OFF` 与那个崩溃的关系

官方示例传 `FLAG_MULTI_SECURITY_OFF`（降低安全等级）。我们不传，于是走完整安全等级
→ 走**信任列表校验**（`RouterServiceValidator.createTrustedListRequest` → `bindService`）
→ 这才是 18:31/18:33 崩溃的触发路径。

**换句话说**：如果照官方示例传这个 flag，那个崩溃可能压根不会发生。

但这是**降低安全性**换取稳定性，我们的选择是「保持安全等级，改用合规的调用位置」。
**如果以后还想进一步降低崩溃风险，可以考虑这个 flag** —— 但要先确认它对本车没有副作用。

### 7.4 未解：`onSdlEnabled` 后 58~115ms 自毁

见 [连接可靠性排查.md](连接可靠性排查.md) 第 9.1 节。
已在 `handleConnectionClosed` 加了「建连至今 ms / RouterAlive / 代际」诊断，等实车数据。

---

## 8. 验证清单（按场景）

| 场景 | 应该看到 | 不应该看到 |
|---|---|---|
| 首次连接 | `onSdlEnabled` → 服务创建 → FULL → `Show` 成功 | 服务创建后 < 1 秒就 `连接结束` |
| 恢复连接 | 同上；车机可能需人工「查找移动应用」 | `[Crash]`、`ReceiverCallNotAllowedException` |
| 车机断开 | `连接结束` + 诊断三值 + `服务销毁` | 服务还活着、通知残留 |
| 车机退出应用 | `失去焦点（NONE）`，**服务仍在** | 服务被 `stopSelf` |
| 手机崩溃 | `[Crash]` + 完整堆栈 + Router 仍显示 connected | 只有一行 null |
| 手机正常退出 | `服务销毁`，`dispose` 无异常 | `onDestroy` 抛异常 |
| 蓝牙关闭 | 同「车机断开」 | — |

---

## 9. 修改这份文件的规矩

- 任何生命周期改动 → 必须同步更新第 4 节对应场景
- 新增偏离 → 加进第 6 节，**写清实车证据**
- 落实了第 7 节的某项 → 把它从"风险"移到第 2 节核心表并标 ✅
- 拿到新官方文档 → 更新第 0 节来源表

---

## 附：官方原文里值得反复引用的三句话

> 1. "**SDL apps should only use a foreground service to host the SDL implementation.**"
>
> 2. "**The `onSdlEnabled` method will be the main start point for our SDL connection session.**"
>
> 3. "The `onDestroy()` method from the `SdlManagerListener` is called whenever the manager
>    detects some disconnect in the connection, **whether initiated by the app, by SDL,
>    or by the device's connection**."
