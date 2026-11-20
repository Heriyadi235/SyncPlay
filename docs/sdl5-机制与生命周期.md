# SDL 5.8 机制与生命周期

> 面向「从 4.x 迁移过来、想系统理解 5.x」的开发者。
> 结论来自三处：**5.8.0 的 jar 反查**（`javap -public` 出的真实签名）、
> 官方文档、以及本项目（SyncPlay）在 2012 款福特 SYNC 1.1 上的实车踩坑。
>
> 文中标注「**实测**」的段落是本仓库在真车上验证过的，官方文档里没有。

---

## 0. 版本现状

| 项 | 值 |
|---|---|
| 当前最新 | **`5.8.0`**（`latest` = `release`，Maven 元数据 2025-11-18 更新） |
| 4.x 最后一版 | **`4.12.2`** |
| 断代点 | `4.12.2` → `5.0.0`，**中间没有任何过渡版本** |
| 5.x 版本序列 | 5.0.0 / 5.1.x / 5.2 / 5.3.x / 5.4 / 5.5 / 5.6.x / 5.7 / **5.8.0** |

坐标：

```kotlin
implementation("com.smartdevicelink:sdl_android:5.8.0")
```

> ⚠️ `targetSdk` 与版本强相关：**4.12.2 时代只能 32**（库内部老式两参
> `registerReceiver` 会抛 `SecurityException`），**5.7.0 修好之后才允许提到 35**。
> 两个时代的结论不要混用。

---

## 1. 架构：从「一个大 proxy」到「门面 + 子管理器」

### 1.1 4.x 的样子

核心是 `SdlProxyALM` 一个巨型对象：

- 构造即连接，`SdlProxyALMBuilder` 传一堆 callback
- 所有事件都是这个对象的回调：`onOnHMIStatus`、`onOnButtonPress`、
  `onOnCommand`、`onProxyClosed`、`onRegisterAppInterfaceResponse`……
- 业务代码必须继承 `SdlProxyALM` 的监听接口，等于**和连接层强耦合**

### 1.2 5.x 的样子

拆成三层：

```
SdlManager（门面，业务唯一入口）
   ├── LifecycleManager（连接状态机：RAI / HMI / 能力 / 加密）
   └── 一堆 SubManager（ScreenManager / FileManager / PermissionManager / ...）
```

关键差别：

| | 4.x | 5.x |
|---|---|---|
| 业务入口 | `SdlProxyALM` | `SdlManager` |
| 发 RPC | `proxy.sendRPCRequest(...)` | `sdlManager.sendRPC(rpc)` |
| 收通知 | 覆写 `onOnXxx` | `addOnRPCNotificationListener(FunctionID.X, listener)` |
| 界面 | 手写 `Show` / `AddCommand` | `getScreenManager()` 帮你攒 |
| 菜单 | `AddSubMenu` + `AddCommand` 手动 | `ScreenManager` / `ChoiceSetManager` |
| 文件 | `PutFile` 手动 | `FileManager`（带队列、重试、去重） |
| 权限 | 自己看 RAI response | `PermissionManager`（`OnPermissionsChange` 增量） |
| 生命周期 | `onProxyClosed` 后对象还在但失效 | `SdlManagerListener.onDestroy` + `dispose()` |

### 1.3 对象图（5.8.0 实测签名）

```java
// 构建
SdlManager.Builder(ctx, appName, appId, SdlManagerListener)
    .setLockScreenConfig(...)
    .setContext(...)
    .build()          // → SdlManager
sdlManager.start();   // 真正开始连接
sdlManager.dispose(); // 收尾

// 业务接口
sdlManager.sendRPC(RPCMessage)
sdlManager.sendRPCs(List, OnMultipleRequestListener)          // 并行
sdlManager.sendSequentialRPCs(List, OnMultipleRequestListener) // 串行
sdlManager.addOnRPCNotificationListener(FunctionID, listener)
sdlManager.addOnRPCRequestListener(FunctionID, listener)       // 车机 → 我们
sdlManager.getScreenManager()
sdlManager.getFileManager()
sdlManager.getPermissionManager()
sdlManager.getSystemCapabilityManager()
sdlManager.getVideoStreamManager() / getAudioStreamManager() / getLockScreenManager()
sdlManager.getAuthToken()
sdlManager.getCurrentHMIStatus()
sdlManager.getRegisterAppInterfaceResponse()   // ← 车机能力清单在这
sdlManager.getState()
sdlManager.startRPCEncryption()
```

### 1.4 子管理器生命周期

所有子管理器都继承 `BaseSubManager`：

```java
public static final int SETTING_UP;  // 正在准备
public static final int READY;       // 可用
public static final int LIMITED;     // 部分可用
public static final int SHUTDOWN;    // 已关闭
public static final int ERROR;

public void start(CompletionListener);   // 异步启动，完成回调里才能用
public void dispose();
public int getState();
```

> **踩点**：`start(CompletionListener)` 是**异步**的。子管理器没 `READY`
> 就开始发对应 RPC，会静默失败或抛错。

---

## 2. 启动与连接时序

```
new SdlManager.Builder(ctx, appName, appId, listener)
        │  只是攒参数，什么都没发生
        ▼
    build()                     → SdlManager 对象（仍未连接）
        │
    start()                     → 创建 LifecycleManager，开始找 RouterService
        │
        ├─ RouterService 在跑 ──→ 建立传输 → 发 RegisterAppInterface
        │
        └─ RouterService 没跑 ──→ LifecycleManager 立刻 onClose
                                   → SdlManagerListener.onDestroy
                                   → SdlManager 自毁（★ 5.x 的重要行为）
```

**这个「找不到 Router 就立刻自毁」是 5.x 最容易被误认为 bug 的行为**：

```
W/Sdl Broadcast Receiver: 5.8.0: Router service isn't running, returning false.
→ onClose
→ Proxy is closed.
```

官方 `hello_sdl` 的处理是：**收到结束回调就 `stopSelf()` 退出服务，等广播重新拉起**。
官方**没有应用层重试**。详见 §3.5。

---

## 3. 生命周期详解

### 3.1 关键回调（`SdlManagerListener`）

5.8.0 实测签名：

```java
public interface SdlManagerListener extends BaseSdlManagerListener {
    void onStart();                                     // 连接建立、RAI 完成
    void onDestroy();                                   // 连接结束 / dispose / 出错退出
    void onError(String info, Exception e);
    LifecycleConfigurationUpdate managerShouldUpdateLifecycle(
            Language language, Language hmiLanguage);   // 车机语言变化时让我们调整
    boolean onSystemInfoReceived(SystemInfo info);      // 系统信息到达（可拦截）
}
```

`BaseSdlManagerListener` 本身是**空接口**（纯标记）。

> 4.x 的 `onProxyClosed` 对应 5.x 的 **`onDestroy()`**。

### 3.2 HMI 等级状态机

等级来自 `OnHMIStatus`，5.8 里可以随时用 `sdlManager.getCurrentHMIStatus()` 拿最新值：

```
NONE ──车机选中──> BACKGROUND ──用户切到本 App──> FULL
  ▲                    │                            │
  └────────────────────┴────────────────────────────┘
                    （失去焦点会退回去）
```

| 等级 | 含义 | 能干什么 |
|---|---|---|
| `NONE` | 注册了但没被选中 | 几乎什么都不能做 |
| `BACKGROUND` | 在后台 | 不能交互，`Show` 不可见 |
| `LIMITED` | 部分可用 | 只有少数 RPC（导航型车机） |
| `FULL` | 前台 | **唯一能自由发 RPC 的等级** |

`OnHMIStatus` 还带两个重要字段：

- `firstRun`：这次连接是否首次进入该等级（→ 要做完整初始化）
- `systemContext`：`MAIN` / `MENU` / `VRSESSION` / `HMI_OBSCURED` / `ALERT`
  （菜单/语音会话中不要打断）

> **实测**：本车每个 RPC 都有「允许的最低 HMI 等级」，见
> `docs/车机权限报文.txt`。`ScrollableMessage` / `Slider` 只有 FULL 能用。

### 3.3 `dispose()` 与重连

`dispose()` 是从 4.x 的 `dispose()` 延续下来的，5.x 里语义更明确：

- 关闭子管理器、注销监听、断开传输、释放 `LifecycleManager`
- 之后**这个 `SdlManager` 实例就废了**，想再连必须 `new Builder(...).build().start()`

> **实测**：断开后不要复用旧对象。`getState()` 已经是 `SHUTDOWN`，
> 再 `sendRPC` 只会静默失败。正确做法是重建。

### 3.4 `onDestroy` 之后必须做的事

4.x 时代 `onProxyClosed` 的清单，5.x 一样适用：

- 车机侧所有菜单 / 命令 / 订阅**都已删除** → 重连必须**全量重建**
- 进行中的交互（计时器、`ScrollableMessage`）已被终止
- 把本地缓存的"已初始化"标记清掉（否则重连后跳过初始化）

### 3.5 官方对「断开」的语义（★ 最容易自作聪明的地方）

官方 `hello_sdl` 的做法：

```java
@Override public void onDestroy() {
    // 收尾：注销监听、清理
    stopSelf();   // ← 关键：服务自己退出
}
```

重连**完全靠官方链路**：

```
车机重连 / 蓝牙建立
  → ACL_CONNECTED 广播
  → SdlBroadcastReceiver.onReceive
  → 启动 SdlRouterService
  → 车机连上 Router
  → sdl.router.startservice / onSdlEnabled
  → 系统重新拉起我们的 Service
  → 重新 new SdlManager(...).start()
```

**官方没有应用层重试**，因为这会让车机侧反复收到同一个 `appName` 的注册请求，
表现为**车机 UI 卡在「恢复连接」**。

> **实测教训**：本项目在 4.12.2 时代写过「每 5 秒重建 SdlManager、最多 12 次」，
> 就是这个症状。改回「断开就退出 + 等广播」后立刻正常。
>
> 结论写进 `AGENTS.md` §1.1：**连接生命周期控制始终与 SDL 官方一致，
> 不自己发明重试 / 保活 / 看门狗**；唯一例外是官方做法在该车机上实测跑不通，
> 且必须写清「改了什么、为什么、实车证据」。

---

## 4. 传输层：RouterService

### 4.1 三个角色

| 组件 | 职责 | 进程 |
|---|---|---|
| `SdlBroadcastReceiver`（子类） | 收广播、决定何时拉起 Router / 应用服务 | 主进程 |
| `SdlRouterService`（子类） | 注册 SDP 记录、监听 RFCOMM、**多路复用**给多个 SDL 应用 | **独立进程**（推荐） |
| `SdlManager` | 单个应用与车机对话 | 主进程 |

Manifest 三件套缺一不可（否则车机发现不了应用）：

```xml
<service android:name=".receiver.SyncRouterService"
    android:process="com.smartdevicelink.router"
    android:foregroundServiceType="connectedDevice"
    android:exported="true">
    <intent-filter>
        <action android:name="com.smartdevicelink.router.service" />
    </intent-filter>
    <meta-data android:name="sdl_router_service_version_name"
               android:resource="@string/sdl_router_service_version_name" />
    <meta-data android:name="sdl_router_service_version_value"
               android:resource="@integer/sdl_router_service_version_value" />
</service>
```

`SdlBroadcastReceiver` 子类里要覆写 `defineLocalSdlRouterClass()` 返回自己的 Router，
以及 `onSdlEnabled()`（车机已连上 Router 时被调用）。

### 4.2 车机是主动方（★ 最容易理解错的一点）

手机端**从不发起** SDL 连接：

```
① 手机与车机建立蓝牙音频连接（A2DP/HFP，跟 SDL 无关）
② Android 发 ACL_CONNECTED 广播
③ SdlBroadcastReceiver 收到 → 启动 RouterService
④ RouterService 注册 SDP 记录 + 监听 RFCOMM
⑤ 车机做 SDP 服务发现 → 发起 RFCOMM → 连上
⑥ RouterService 多路复用 → 各应用的 SdlManager → RAI
```

推论：

- **RouterService 不需要常驻**，它只需要"在车机要连的那一刻活着"
- 车机发现的是 **SDP 记录**，不是某个一直在跑的服务
- Router 没跑时，车机能"发现"记录但没人 `accept` → 车机 UI 卡「连接中」
- 只要 `ACL_CONNECTED` 送达并拉起 Router，车机重试就能连上
- **唯一会彻底连不上的情况**：应用被 force-stop 或系统禁止自启
  → 收不到广播 → Router 永远起不来 → 只能手动打开一次 App

> **实测**：手动 `am startservice` 起 Router 后，无车机连接时它**并没有**主动关闭，
> 至少存活 6 分钟（退到后台等系统回收）。所以"没连车机就立刻关 Router"是误解。

### 4.3 判断「Router 是否在跑」

5.8 的 `SdlBroadcastReceiver` **没有** `isRouterServiceRunning()` 这类 API。
可用的只有：

```java
public static Vector<ComponentName> runningBluetoothServicePackage;
public static void queryForConnectedService(Context);
public static ComponentName consumeQueuedRouterService();
protected static void pingRouterService(Context, String, String);
```

这些要么库内部维护、时机不可靠，要么只回答"有没有连上车机"。

**本项目做法**（`SyncRouterService` 是普通子类，加两行就行）：

```java
@Override public void onCreate()     { super.onCreate();     report(true);  }
@Override public int  onStartCommand(Intent i, int f, int id) {
    int r = super.onStartCommand(i, f, id); report(true); return r;   // 库会反复 start 它
}
@Override public void onDestroy()    { report(false); super.onDestroy(); }
```

用**应用内广播**（`intent.setPackage(getPackageName())`）告诉主进程。
已知盲区：进程被系统直接杀掉时 `onDestroy` 不回调，状态会停在"运行中"，
下次 Router 起来会自愈。

### 4.4 安全等级与多进程

- `setSdlSecurity(List<Class>, ServiceEncryptionListener)` 可开启 RPC 加密
  （5.x 的 `LifecycleManager.startRPCEncryption()`）
- Router 推荐跑在**独立进程**：隔离内存、避免主进程被杀连带断线
- 多应用共用同一个 Router 是设计意图（多路复用），所以**不是**"一个应用一个 Router"

---

## 5. 线程模型

| 位置 | 线程 |
|---|---|
| `SdlManagerListener` 的 `onStart` / `onDestroy` / `onError` | 主线程 |
| `OnRPCResponseListener.onResponse` | **主线程**（5.x 内部 post 回主线程） |
| `OnRPCNotificationListener.onNotified` | 主线程 |
| 子管理器 `start(CompletionListener)` 的回调 | 主线程 |
| `sendRPC` 调用本身 | 任意线程（内部排队） |

所以业务侧不需要自己切线程**接收**；但要自己开线程做**耗时**工作
（网络、解密、文件 IO），否则会卡 UI 和 RPC 处理。

> **实测**：本项目把所有耗时活儿（查歌词、AES-GCM 解密导航包、生成成品歌词）
> 全放在自己的 `ExecutorService`，主线程只做"组装一帧 + 发 RPC"。

---

## 6. 4.x → 5.x 对照表

| 4.x | 5.x（5.8） |
|---|---|
| `SdlProxyALM` | `SdlManager` |
| `SdlProxyALMBuilder` | `SdlManager.Builder` |
| `proxy.start()` | `new Builder(...).build().start()` |
| `proxy.sendRPCRequest(rpc)` | `sdlManager.sendRPC(rpc)` |
| `proxy.dispose()` | `sdlManager.dispose()` |
| `onProxyOpened` | `SdlManagerListener.onStart` |
| `onProxyClosed` | `SdlManagerListener.onDestroy` |
| `onOnHMIStatus` | `addOnRPCNotificationListener(FunctionID.ON_HMI_STATUS, ...)` 或 `getCurrentHMIStatus()` |
| `onOnButtonPress` | `addOnRPCNotificationListener(FunctionID.ON_BUTTON_PRESS, ...)` |
| `onRegisterAppInterfaceResponse` | `getRegisterAppInterfaceResponse()`（**同步可取**） |
| 手动 `Show` / `AddCommand` | `getScreenManager()` |
| 手动 `PutFile` | `getFileManager()` |
| 自己解析 `OnPermissionsChange` | `getPermissionManager()` |
| 自己解析 `GetSystemCapability` | `getSystemCapabilityManager()` |
| 大回调接口（必须继承） | 按 `FunctionID` 注册的细粒度监听器 |

---

## 7. 本项目的对照（实战要点）

| 主题 | 结论 |
|---|---|
| 入口 | `SdlService.onCreate` 里 `new SdlManager.Builder(...).build().start()` |
| 收通知 | 按 `FunctionID` 注册：`ON_HMI_STATUS` / `ON_BUTTON_PRESS` / `ON_BUTTON_EVENT` / `ON_PERMISSIONS_CHANGE` |
| 发 RPC | 全部经 `sendRPC`，每条挂 `OnRPCResponseListener` 记结果码 |
| 断开 | `onDestroy` → 清 sender、`AmapLinkClient.setHeadUnitConnected(false)`、重置"已初始化"标记 → **`stopSelf()`（官方语义）** |
| 重连 | 只靠 `ACL_CONNECTED` → `SdlReceiver` → 重新 `onStartCommand` → 重建 `SdlManager` |
| 重试 | **没有**应用层定时重试（踩过坑，见 §3.5） |
| 通知 | 前台服务通知，寿命 = 服务寿命；正文只报状态枚举，不拼歌名 |
| 数据与连接解耦 | 采集/渲染在应用级单例 `SyncPlayCore`，连接层退出后预览仍可工作 |

---

## 8. 参考链接

**官方指南**

- [Migrating to 5.0（断代改动总览）](https://smartdevicelink.com/en/guides/android/migrating-to-newer-sdl-java-suite-versions/updating-to-v50/)
- [Updating to v5.1](https://smartdevicelink.com/zh-hans/guides/android/migrating-to-newer-sdl-java-suite-versions/updating-to-v51/)
- [Updating to v5.4](https://smartdevicelink.com/en/guides/android/migrating-to-newer-sdl-java-suite-versions/updating-to-v54/)
- [Updating to v5.6](https://smartdevicelink.com/en/guides/android/migrating-to-newer-sdl-java-suite-versions/updating-to-v56/)
- [Updating to v5.7（修复 `registerReceiver`，允许提高 targetSdk）](https://smartdevicelink.com/en/guides/android/migrating-to-newer-sdl-java-suite-versions/updating-to-v57/)
- [Connecting to an Infotainment System（连接时序权威说明）](https://smartdevicelink.com/en/guides/android/getting-started/connecting-to-an-infotainment-system/)
- [Multiple Transports](https://smartdevicelink.com/en/guides/android/getting-started/multiple-transports/)
- [Understanding Permissions](https://smartdevicelink.com/en/guides/android/getting-started/understanding-permissions/)
- [Checking Supported Features](https://smartdevicelink.com/en/guides/android/getting-started/checking-supported-features/)

**常见问题（跟生命周期直接相关）**

- [SDL Notifications](https://smartdevicelink.com/en/guides/android/frequently-asked-questions/sdl-notifications/)
- [Trusted Router Service](https://smartdevicelink.com/en/guides/android/frequently-asked-questions/trusted-router-service/)
- [Multiple Processes](https://smartdevicelink.com/en/guides/android/frequently-asked-questions/multiple-processes/)

**API 文档**

- [`SdlManager`](https://smartdevicelink.com/en/docs/android/master/com/smartdevicelink/managers/SdlManager/)
- [`SdlManagerListener`](https://smartdevicelink.com/en/docs/android/master/com/smartdevicelink/managers/SdlManagerListener/)
- [`LifecycleManager`](https://smartdevicelink.com/en/docs/android/master/com/smartdevicelink/managers/lifecycle/LifecycleManager/)
- [`BaseSubManager`](https://smartdevicelink.com/en/docs/android/master/com/smartdevicelink/managers/BaseSubManager/)
- [`SdlRouterService`](https://smartdevicelink.com/en/docs/android/master/com/smartdevicelink/transport/SdlRouterService/)
- [`FunctionID`（所有可监听的 RPC）](https://smartdevicelink.com/en/docs/android/master/com/smartdevicelink/protocol/enums/FunctionID/)

**源码 / 版本**

- [sdl_java_suite（GitHub）](https://github.com/smartdevicelink/sdl_java_suite)
- [CHANGELOG.md](https://github.com/smartdevicelink/sdl_java_suite/blob/master/CHANGELOG.md)
- [5.8.0 版本 PR](https://github.com/smartdevicelink/sdl_java_suite/pull/1890)
- [Maven Central 版本列表](https://central.sonatype.com/artifact/com.smartdevicelink/sdl_android)

---

## 附：怎么自己验证 API

这个仓库里有 5.8.0 的 aar，可以直接反查签名，比翻文档准：

```powershell
$aar = "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\com.smartdevicelink\sdl_android\5.8.0\*\sdl_android-5.8.0.aar"
$work = "$PWD\build\sdl580"
New-Item -ItemType Directory -Force -Path $work | Out-Null
Copy-Item $aar "$work\sdl.zip"
Expand-Archive "$work\sdl.zip" -DestinationPath $work -Force

$javap = "C:\Program Files\Android\Android Studio\jbr\bin\javap.exe"
& $javap -public -classpath "$work\classes.jar" com.smartdevicelink.managers.SdlManager
```
