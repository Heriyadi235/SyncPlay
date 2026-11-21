# SDL 生命周期：本项目实际行为 vs 官方推荐

> **这份文件的用途**：把「各个场景下到底发生了什么」和「SDL 官方认为应该怎样」摆在一起，
> 出问题时一眼能定位在哪一层、是不是我们偏离了官方。
>
> **我的职责范围**（2026-11-21 起）：本项目的 **SDL 生命周期控制**由我专门负责。
> 动 `SdlService` / `SdlReceiver` / `BootReceiver` / `SyncRouterService` 之前先读这份文件。

---

## 0. 证据来源与可信度

| 等级 | 来源 | 说明 |
|---|---|---|
| 🟢 **硬证据** | 反编译 `sdl_android 5.8.0` 的 AAR | `SdlBroadcastReceiver` / `SdlRouterService` / `RouterServiceValidator` 的实际字节码 |
| 🟢 **硬证据** | 实车落盘日志 | 2026-11-21 15:13~15:17、18:29~18:34 两轮 |
| 🟢 **硬证据** | 本项目代码 | 行号都在，可核对 |
| 🟡 **二手** | [docs/design.md](design.md) §5.9 | 你之前把官方 Integration Basics 原文存下来后整理的逐条对照 |
| 🟡 **二手** | [docs/sdl5-机制与生命周期.md](sdl5-机制与生命周期.md) §3 | 同类资料整理 |
| 🔴 **未取得** | smartdevicelink.com 官方指南正文 | **JS 渲染，`web_fetch` 只拿到导航目录**。所有标 `⚠` 的"官方原话"都来自上面两行二手资料，不是我这次亲眼读到的 |

> ⚠ **规矩**：本文里凡是写「官方……」的，都是我**没有直接打开官方页面**的转述。
> 要拿它当依据去改代码之前，先确认 `docs/design.md` §5.9 里那条是不是真的有原文出处。
> 拿不准就问用户要原文。

---

## 1. 参与方与职责边界

```
┌──────────────────────────── 手机 ────────────────────────────┐
│                                                              │
│  主进程 com.xigaigai.syncglance                               │
│  ├─ SyncGlanceApp        Application：Log2 / Core / 探测 / 崩溃兜底
│  ├─ MainActivity         用户界面；也能触发探测
│  ├─ SdlService           前台服务；SdlManager 的宿主 ← 生命周期核心
│  ├─ SdlReceiver          继承库的 SdlBroadcastReceiver；onSdlEnabled 是唯一启动入口
│  └─ BootReceiver         只记日志 + 蓝牙白名单（2026-11-21 起不碰连接）
│                                                              │
│  ┌───────────── 独立进程 com.smartdevicelink.router ─────────┐ │
│  │ SyncRouterService（继承库的 SdlRouterService）             │ │
│  │   · 注册 SDP 记录、监听 RFCOMM、多路复用                   │ │
│  │   · 有自己的 10~20 秒前台窗口 + 通知                       │ │
│  │   · 通过广播上报 ALIVE / DEAD 给主进程                     │ │
│  └──────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────┘
                              ↕ 蓝牙 RFCOMM
┌──────────────────────────── 车机 ────────────────────────────┐
│  SYNC 1.1 / MFD4：主动发起 SDP 与 RFCOMM；主动下发 OnHMIStatus │
└──────────────────────────────────────────────────────────────┘
```

**三条铁律**（都是踩出来的，见 `AGENTS.md` §1.1）：

1. **只有 `SdlReceiver.onSdlEnabled` 可以启动 `SdlService`**
2. **`BootReceiver` 既不能启动服务、也不能调 `queryForConnectedService`**（后者会闪退）
3. **断开就 `stopSelf()`，不重试、不保活、不看门狗**

---

## 2. 三个全局状态机

### 2.1 连接状态（`SyncLifecycle.State`）

```
DISCONNECTED ──startProxy()──> CONNECTING ──OnHMIStatus──> NONE
                                                          ↓ 车机选中
                                                      BACKGROUND
                                                          ↓ 用户切到本 App
                                                       LIMITED
                                                          ↓
                                                        FULL   ← 唯一能发 RPC 的等级
```

- 掉出 FULL 会沿原路退回；**连接不断**，只是没焦点
- 车机断链 → `SdlManagerListener.onDestroy()` → 回 `DISCONNECTED`

### 2.2 服务的生命周期（官方语义）

```
onSdlEnabled → startForegroundService → onCreate（建通知）
             → onStartCommand（needRebuild? startProxy）
             → 连接期间常驻
             → SdlManagerListener.onDestroy → handleConnectionClosed → stopSelf()
             → onDestroy（stopForeground + flushNow + dispose）
```

### 2.3 软键的生命周期（**独立于上面两个**）

软键 = `Show.softButtons` 的一部分，**没有单独的存在形式**：

```
FULL + 数据变化 → flush() → Show(含 softButtons) → 车机画出来
掉出 FULL      → 不再有 Show → 车机自己清屏（我们不主动删）
回 FULL        → markDirty → 重新 Show → 软键重新出现
断开           → 会话结束 → 全部消失
```

---

## 3. 场景逐个对照

### 3.1 上车首次连接

**触发**：手机与车机建立蓝牙（A2DP/HFP）→ 系统发 `ACL_CONNECTED`

**实际时序**

| # | 谁 | 做了什么 | 日志特征 |
|---|---|---|---|
| 1 | `BootReceiver` | 记日志 + 白名单判断，**什么都不做** | `[Boot] 收到: ...ACL_CONNECTED` |
| 2 | 库的 `SdlReceiver.onReceive` | `wakeUpRouterService()` → `ServiceFinder` → `startRouterService()`（异步 ~110ms） | `[Router] RouterService 上线` |
| 3 | `SyncGlanceApp.onCreate`（若进程被拉起） | `queryForConnectedService`（**Application context，安全**） | `[Boot] 应用启动，补一次…探测` |
| 4 | 库 + 车机 | SDP → RFCOMM → 车机连上 Router | — |
| 5 | 库 | 回调 `onSdlEnabled` | `[SdlReceiver] ★ onSdlEnabled` |
| 6 | `SdlReceiver` | `startSelf()` + PendingIntent 双路启动 | `已直接启动 SdlService` |
| 7 | `SdlService` | `onCreate` → 建通知渠道 → 前台服务 | `[SyncPlay] SyncPlay 服务创建` |
| 8 | `SdlService` | `onStartCommand` → `needRebuild()==true` → `startProxy()` | `[SyncPlay] 启动时发现连接不可用…重建连接` |
| 9 | `SdlService` | `SdlManager.build().start()` | `✓ SdlManager 已启动` |
| 10 | 车机 | `OnPermissionsChange` → `OnHMIStatus` NONE→BACKGROUND→LIMITED→FULL | `OnHMIStatus: ... → 已连接(FULL)` |
| 11 | `SdlService` | 首次 FULL → `initializeForConnection()`：建菜单、订阅 13 个按键、补发计时器、`markDirty` | `★ 进入 FULL —— 开始初始化` |
| 12 | `StateRenderer` | `flush()` → `Show`（含 3 个软键） | `[Render] Show[空闲] …` |

**软键表现**：FULL 之后出现 **`播放/暂停` | `-` | `计时` + 车机自带的「更多」**（3 个，不翻页）。
每次数据变化都会重发 `Show`，覆盖式写入，软键跟着刷新。

**官方**：🟢 一致。`onSdlEnabled` 是应用唯一的启动时机（反编译确认）；
🟡 `onStartCommand` 里建 `SdlManager` + `START_STICKY` 也是官方模板（`design.md` §5.9）。

**历史坑**：这里曾经在 `ACL_CONNECTED` 时由 `BootReceiver` 直接 `startForegroundService`，
抢在 Router 之前 → `SdlManager` 7ms 自毁 → 车机卡「正在连接」2 分钟。已修（`fcbef0a`）。

---

### 3.2 上车恢复连接（下车 → 再上车）

**触发**：`ACL_DISCONNECTED`（下车）→ 一段时间后 `ACL_CONNECTED`（再上车）

**实际时序**

| 阶段 | 发生什么 |
|---|---|
| 下车时 | `SdlManagerListener.onDestroy()` → `handleConnectionClosed()` → `stopSelf()` → 服务销毁、通知消失（见 3.3） |
| 中间 | **Router 进程可能还活着**（独立进程，不一定跟着死） |
| 再上车 | 与 3.1 完全相同的流程 |

**⚠ 关键差异点（已修，别再踩）**：Router 还活着时，`ServiceFinder` 能"找到"它，
于是 `queryForConnectedService` 会走**信任列表校验**（`bindService`）。
如果这个调用发生在 **BroadcastReceiver 的 context** 里 → `ReceiverCallNotAllowedException` → **闪退**。
这就是 2026-11-21 18:31/18:33 两次崩溃，也是 `BootReceiver` 现在完全不探测的原因。

**本车特别注意**：SYNC 1.1 **不会自动发现 App**，必须人工在车机上点「查找移动应用」。
上车后车机会尝试「恢复连接」，如果失败就退回 AppLink 主界面 ——
**这是车机行为，不是我们的 bug**。手工点一次即可（或按手机上的「唤醒车机发现」重开窗口）。

**软键表现**：下车后车机侧软键全部消失（会话结束）；再上车重新 FULL 后重建。

**官方**：🟡 一致（断开退出 + 靠广播链路重连，不重试）。
🟢 反编译确认 `ACL_CONNECTED → wakeUpRouterService()` 由库负责，应用不该插手。

---

### 3.3 车机断开连接（熄火 / 蓝牙断）

**触发**：蓝牙链路断（`ACL_DISCONNECTED`）→ Router 感知 transport 断开 → 通知 `SdlManager`

**实际时序**

```
① [Boot] 收到 ACL_DISCONNECTED                    ← BootReceiver 只记日志
② [Boot] SYNC 车机已断开（SYNC ）
③ [SyncPlay] 连接结束（原状态 FULL），按官方语义停掉 Service
     ［建连至今 XXXXms，RouterAlive=…，代际=…］      ← 2026-11-21 新加的诊断
④ [Core] 发送通道已摘除（预览仍可工作）
⑤ [SyncPlay] 服务销毁（连接层退出，…）
```

**`handleConnectionClosed()` 逐项**：

| 动作 | 为什么 |
|---|---|
| `closedOnce = true`（若之前不在 CONNECTING） | 只为了通知文案区分「连接关闭」和「从没连上」 |
| `sdlManager = null` + `lifecycle.onProxyClosed()` | 旧对象已废，不能复用 |
| `AmapLinkClient.setHeadUnitConnected(false)` | 没车机就不解析导航包，省 CPU |
| `menusBuilt = false` | 车机侧菜单/订阅**全被删**，重连必须全量重建 |
| `pendingTimerResend = true` | 计时器可能被车机终止，回 FULL 时补发 |
| `renderer.onDisconnected()` + `core.detachSender()` | 渲染层保留，只是发不出去 |
| `updateNotification()` | 刷成「未连接（连接关闭）」，随后随 `stopSelf` 消失 |
| **`stopSelf()`** | 🟡 官方语义：断开就退出 |

**`SdlService.onDestroy()`**：`stopForeground(STOP_FOREGROUND_REMOVE)` → `Log2.flushNow()`
→ `sdlManager.dispose()` → `instance = null` → `super.onDestroy()`

> ⚠ 这里**故意不**调用 `NotificationCatcher.setMusicListener(null)` ——
> 那会把数据采集一起掐死（预览停在「空闲」）。监听器归 `SyncPlayCore` 管。

**软键表现**：车机侧随会话结束全部消失；手机通知也消失。

**官方**：🟡 一致（`dispose()` 是 Must、`stopSelf` 是官方示例）。
**已知偏差**：`stopSelf` 之前先 `updateNotification()` 是我们的补充，官方没有，无害。

---

### 3.4 车机退出应用（在车机上按「离开 SyncPlay」）

**触发**：车机下发 `OnHMIStatus`，`hmiLevel` 从 FULL/LIMITED → **NONE**

**实际时序**

```
① OnHMIStatus: FULL → 已连接(NONE) firstRun=false ctx=MAIN
② [SyncPlay] ⚠ 失去焦点（NONE），进行中的操作已被车机终止
③ [AmapLink] 预览页已关闭（非 FULL 时停止解析）   ← 省 CPU
④ 通知文案 → 「已连接（未选中）」                  ← 服务没退出！
```

**`onLostFocus()` 只做三件事**：`menusBuilt = false`、`renderer.onDisconnected()`、`pendingTimerResend = true`。
**不 `stopSelf`** —— 连接还在，用户可以再点回来。

**软键表现**：

- 车机已经切走界面，「离开」画面上的按键是车机自己的
- 我们的软键**不会主动删除**，但因为不再发 `Show`，也就不会再出现
- 用户从车机菜单重新选中 SyncPlay → 回到 FULL → `initializeForConnection()` 重建 → 软键回来

**官方**：🟡 一致。官方明确「非 FULL 不能发 RPC」，我们遵守（`sendRpc` 里有守卫）。
⚠ 待确认：官方对 `systemContext = MENU / VRSESSION / HMI_OBSCURED` 是否有额外要求
（我们目前把它只当"不能发 RPC"处理，没有区分上下文）。

---

### 3.5 手机端崩溃 / 意外关闭

**触发**：未捕获异常、系统内存回收、用户长按「强制停止」

**实际发生**

| 步骤 | 结果 |
|---|---|
| 崩溃瞬间 | `SyncGlanceApp` 装的 `UncaughtExceptionHandler`：写 `[Crash]` + 堆栈 → `flushNow()` → 交回系统 → **照常崩** |
| 进程死亡 | `SdlService.onDestroy()` **通常不会执行** → `dispose()` **不会执行** |
| 通知 | 前台服务随进程消失，通知被系统清理 |
| Router | 独立进程，**可能还活着**；它会发现客户端 socket 断了，清掉该会话 |
| 车机 | 会话消失 → 退回 AppLink 菜单/列表 |

**恢复路径**（没有任何"自动重连"逻辑，全靠官方链路）：

```
再上车 → ACL_CONNECTED → 库唤醒 Router → onSdlEnabled → 拉起 SdlService
或：用户打开 App → SyncGlanceApp.onCreate → queryForConnectedService
```

**软键表现**：随会话结束全部消失。

**官方**：🟡 一致 —— 官方也没有"崩溃恢复"机制，靠广播链路重新拉起。
**风险（未修）**：崩溃时没执行 `dispose()`，理论上可能留下半开的 transport。
但进程都死了，socket 由内核回收，实测没观察到残留影响。

**这次修的一个重要短板**：以前崩溃时**堆栈根本不落盘**（`Log2.e` 只写 `getMessage()`，
而它常常是 `null`）。现在带堆栈了 —— 车里没 adb 也能拿到证据。

---

### 3.6 手机端正常退出（用户划掉 App / 系统正常回收）

**触发**：用户从最近任务划掉、或系统正常销毁

**实际发生**

- 如果 `SdlService` 还在跑：`onDestroy()` → `stopForeground(REMOVE)` + `flushNow()` + `dispose()`
- 如果它已经因为断连 `stopSelf` 过：`onDestroy` 早已执行，本轮无事
- **`NotificationCatcher`**：可能在 `SyncPlayCore` 里继续存活（数据采集不依赖连接层）

**软键表现**：同 3.5，随会话结束消失。

**官方**：🟡 一致（`dispose()` 是 Must，且必须在 `onDestroy` 里）。

**易错点**：`onDestroy` 里的顺序是 `stopForeground` → `flushNow` → `dispose`。
`flushNow` 必须在 `dispose` 之前，否则断开瞬间的日志会丢。

---

### 3.7 手机端蓝牙关闭

**触发**：用户在设置里关蓝牙 → `BluetoothAdapter.STATE_CHANGED`（`STATE_OFF`）

**实际时序**

| 步骤 | 谁 | 做什么 |
|---|---|---|
| 1 | `BootReceiver` | 收到 `STATE_CHANGED`，`state != STATE_ON` → **完全静默**（连日志都不打） |
| 2 | 蓝牙链路断 | 车机侧超时 → Router 感知 → `SdlManager` onDestroy → **走 3.3 的流程** |
| 3 | Router | 可能随后退出，`onDestroy` 发 `ROUTER_DEAD` → 通知变「未连接（蓝牙未连接）」 |

**软键表现**：同 3.3。

**官方**：🟡 一致（不需要特殊处理，断链自然触发）。
**可改进**：`STATE_OFF` 现在**完全不打日志**，排查"为什么突然断了"时看不出是用户自己关的蓝牙。
建议加一行 `[Boot] 蓝牙已关闭`。

---

### 3.8 应用安装 / 升级

**触发**：`MY_PACKAGE_REPLACED`

**实际**：`BootReceiver` 只记日志 → 进程被拉起 → `SyncGlanceApp.onCreate` 探测。

**官方**：无此 receiver（我们自加），无害。

**⚠ 已知副作用**：debug 包在**安装后第一次** `queryForConnectedService` 会抛
`RuntimeException`（`IntegrationValidator` 报 `✕ This application has not specified its
SDL Receiver properly`）。**这是误报** ——
`cmd package query-receivers -a sdl.router.startservice` 已确认我们的 receiver
`enabled=true exported=true`。第二次进程启动就正常。**不要动 manifest。**

---

### 3.9 手机重启

**触发**：`BOOT_COMPLETED`

**实际**：同 3.8（只记日志 + Application 探测）。

**⚠ 假开机广播**：国内 ROM 会在应用被杀后**重发** `BOOT_COMPLETED`
（实车 2026-11-21 15:14:57 / 15:15:17 / 15:15:52 连发三次，而 `uptime` 已 6.2 天）。
以前每来一次就建一个收不到回应的僵尸 `SdlManager`；现在只多一次无害查询。

---

## 4. 软键专项（跨场景）

### 4.1 软键不是独立对象

软键**只作为 `Show` 的字段存在**。`StateRenderer.flush()` 的守卫是：

```java
if (sender == null || lifecycle == null || !lifecycle.canSendRpc()) return;   // 非 FULL 不发
```

所以：

| HMI 等级 | 我们发 Show 吗 | 车机上看到什么 |
|---|---|---|
| `FULL` | ✅ 发 | 我们的 3 个软键 + 车机「更多」 |
| `LIMITED` / `BACKGROUND` | ❌ 不发 | 车机自己的界面；我们的软键不可见 |
| `NONE` | ❌ 不发 | 车机已切走 |
| 断开 | ❌ 发不出去 | 全部消失 |

### 4.2 当前布局与 ID

| 槽位 | 内容 | customButtonID | 备注 |
|---|---|---|---|
| 1 | `播放/暂停` | 9001 | 实车验证过 |
| 2 | `-` | 9002 | 占位（还没想好放什么） |
| 3 | `计时` | 9003 | 三态循环：正计时 → 暂停 → 清零 |
| 4 | 「更多」 | — | **车机自带**，不是我们的软键 |

> 软键**最多 8 个**；≤3 个时不翻页（槽 4 是「更多」），>3 个时每屏槽 4 变翻页键。
> 详见 `AGENTS.md` §6.4。

### 4.3 各场景下按软键的响应

| 场景 | 能按吗 | 会发生什么 |
|---|---|---|
| FULL、连上车机 | ✅ | `OnButtonPress{CUSTOM_BUTTON, 900x}` → `CommandRouter.handleSoftButton` → 执行本地动作 → `markDirty` → `Show` 刷新 |
| 掉出 FULL | ❌ 车机上根本看不到 | — |
| 刚回 FULL | ✅ 但可能短暂是旧的 | `initializeForConnection` 或 `markDirty("reFULL")` 会马上重发 |
| 断连 | ❌ | — |
| 回调没带 `customButtonID` | — | `CommandRouter` 打 `⚠ 软键：回调未带 customButtonID` 并忽略 |

### 4.4 软键与计时器的耦合（易错）

软键 9003 是**唯一**能在车机上操作计时器的入口，它是**本地状态驱动**的：

- 状态存在 `StateRenderer.timerMode`（正计时 / 暂停 / 清零）里，**不依赖连接**
- 断电/断连后仍是上次的样子（预览器能看到）
- 断开期间改的计时器，`pendingTimerResend` 会在回 FULL 时补发
- **车机会在失焦时终止正在跑的时钟** → 所以回 FULL 必须补发（`resendTimerIfNeeded`）

---

## 5. 我们偏离官方的点（全量清单）

> 按 `AGENTS.md` §1.3 的要求：给清单，不用「全部」这种词。

| # | 偏离 | 为什么 | 是否有实车证据 |
|---|---|---|---|
| 1 | `queryForConnectedService` 在 **`SyncGlanceApp.onCreate`** 里也调一次 | 官方只在 Activity 里调；但应用可能因为广播被拉起、界面上没开，需要补探测 | 🟢 多次实车成功 |
| 2 | 保留 `BootReceiver`（官方没有） | 记录「启动来源」+ 蓝牙白名单 | 🟢 日志有用 |
| 3 | `onSdlEnabled` **双路启动**（`startSelf` + PendingIntent） | 官方二选一。踩过 PendingIntent 假成功：`send()` 返回成功但服务没起来 | 🟢 见 `SdlReceiver` 注释里的 12:49 日志 |
| 4 | `RouterAlive` 跨进程广播（`ROUTER_ALIVE/DEAD`） | 5.8 没有 `isRouterServiceRunning()`，只能自己上报 | 🟢 通知文案依赖它 |
| 5 | `MainActivity` 的「唤醒车机发现」按钮 | 本车不会自动发现 App，需要人工重开 Router 前台窗口 | 🟢 `wakeRouter` |
| 6 | `closedOnce` + `ConnectionStatus` 六种文案 | 官方只要求前台通知，没规定文案。我们为排查方便加的 | — |
| 7 | `handleConnectionClosed` 里 `stopSelf()` **之前**先 `updateNotification()` | 让"连接关闭"状态在通知消失前可见 | — |
| 8 | 崩溃兜底 `UncaughtExceptionHandler` | 只写日志，不吞异常 | — |

**结论**：偏离项**都不改变官方时序**，属于"（1）观测层"和"（2）本车实测必需的变通"。
唯一踩过的雷是把「启动服务」这件事也偏离到广播里 —— **已撤回**。

---

## 6. 待官方原文确认（我拿不到 JS 正文）

| # | 问题 | 现在怎么做的 | 要去哪查 |
|---|---|---|---|
| 1 | `queryForConnectedService` 官方推荐在**哪些位置**调？重复调有副作用吗？ | Application + MainActivity | Integration Basics - Java |
| 2 | 官方对 `systemContext = MENU / VRSESSION / HMI_OBSCURED` 有什么要求？ | 一律当"不能发 RPC" | HMI 等级相关文档 |
| 3 | `stopForeground` 用 `REMOVE` 还是 `DETACH`？官方示例是哪种？ | `REMOVE`（通知跟着消失） | Integration Basics / SDL Notifications |
| 4 | 应用崩溃/被杀后，官方是否要求 `Router` 做清理？ | 不处理，靠 socket 断 | Multiple Processes / Trusted Router Service |
| 5 | 软键在 HMI 降级时是否应该用 `Show` 主动清空？ | 不主动，等车机自己清 | Template Custom Buttons |
| 6 | `START_STICKY` 在 5.x 官方示例里是否仍然保留？ | 保留 | Integration Basics - Java |

**这些问题的答案我拿不到原文，请用户提供**（见对话里的求助清单）。

---

## 7. 验证清单（按场景）

| 场景 | 应该看到 | 不应该看到 |
|---|---|---|
| 首次连接 | `onSdlEnabled` → 服务创建 → FULL → `Show` 成功 | 服务创建后 < 1 秒就 `连接结束` |
| 恢复连接 | 同上；车机可能需人工「查找移动应用」 | `[Crash]`、`ReceiverCallNotAllowedException` |
| 车机断开 | `连接结束` + 诊断三值 + `服务销毁` | 服务还活着、通知残留 |
| 车机退出应用 | `失去焦点（NONE）`，**服务仍在** | 服务被 `stopSelf` |
| 手机崩溃 | `[Crash]` + 完整堆栈 | 只有一行 null |
| 手机正常退出 | `服务销毁` + `dispose` 无异常 | `onDestroy` 里抛异常 |
| 蓝牙关闭 | 同"车机断开" | — |

---

## 8. 修改这份文件的规矩

- 任何生命周期相关改动，改完**必须**同步更新第 3 节对应场景
- 新增偏离项 → 加进第 5 节表格，**写清实车证据**
- 拿到官方原文 → 把第 6 节的问题逐条落实，并把 🔴 改成 🟢
