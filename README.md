# SyncPlay

把 Android 手机变成 **福特 SYNC 1.1 车机信息面板**的 AppLink 应用：
歌名 / 歌词 / 导航指令 / 计时器，全部推送到 4 寸非触摸屏上，开车时不用掏手机。

> **命名有点乱，先说明白**
>
> | 位置 | 名字 |
> |---|---|
> | GitHub 仓库 / 车机标题栏 / 车机应用列表 | **SyncPlay** |
> | Android 工程目录 / 包名 | `SyncGlance` / `com.xigaigai.syncglance` |
> | 手机桌面图标 | 西改改的Sync |
>
> 包名和仓库名不统一是历史原因，**不要**为了统一去改 `appName`——
> 车机按 `appName` 做策略查表和授权记录，改一次等于换了个 App。

## 目标环境

| 项 | 值 |
|---|---|
| 车型 | 2012 款福特福克斯（中国） |
| 车机 | SYNC 1.1，MFD4 四寸屏，`graphicSupported = false` |
| 协议 | SDL 2.1（`syncMsgVersion 2.0.0`），蓝牙 SPP 传输 |
| SDK | `sdl_android:5.8.0` + `MultiplexTransportConfig`（4.12.2 的旧实现见 `legacy/sdl4` 分支） |
| 构建 | AGP 9.5.1 / Gradle 9.6.0 / `compileSdk 37`、`minSdk 24`、**`targetSdk 35`**；Java 源/目标级别 11 |

## 能做什么

四种状态由「有没有导航数据」×「有没有歌曲数据」决定，两屏各两行文本 + 一行 `mediaTrack`：

| 状态 | 第 1 屏 | mediaTrack |
|---|---|---|
| A 空闲 | 问候语 / 西改改 | 空 |
| B 仅导航 | 转向+距离 / 路名 | 剩余距离 |
| C 仅歌曲 | 歌名 / 歌手，第 2 屏两行歌词 | 歌名 |
| D 导航+歌曲 | 见下面「导航优先」开关 | 剩余距离 |

- **导航优先开关**（仅 D 混合状态）：车机底栏第 2 格「导航」键（或手机「导航模式」按钮）切换
  · **开**（默认）：第 1 屏导航、第 2 屏歌词（无歌词时显示歌名 / 歌手）
  · **关**：第 1 屏歌词（无歌词时歌名 / 歌手）、第 2 屏导航
  · 非混合状态下该格退回占位 `-`，按压不响应
- **歌词**：带时间轴自动跟随，一行 7 汉字宽，长句折两行、超屏按时长翻页
- **导航**：高德 AmapLink（AIDL + AES-GCM）取转向/路名/剩余距离/ETA。
  高德是**按需推送**（车停着不动就不发数据），所以降级判定是
  **数据停 10 秒后先探活**：高德进程没了→立即降级；进程还在→保持画面，直到停满 3 分钟才兜底降级
- **计时器**：正计时 / 倒计时（时:分:秒，可设到 59:59:59）/ 暂停 / 清零，暂停时数字定格在屏上；
  倒计时走到 `00:00` 不自动清屏，点一下车机底栏「计时」键才清
- **时钟格**：`SetMediaClockTimer` 实测**有小时位**（能显示 `1:23:45`），
  导航 ETA 仍借分秒位显示到达时刻（`16:21`）
- **交互**：3 个父菜单 + 12 个子项、15 个物理键（OK 与 PLAY_PAUSE 是同一物理键，已做别名去重）、底栏软键
- **调试**：手机端车机屏幕预览、通信日志（含原始 JSON，可复制/分享）

## 数据流

```
┌─ SyncPlayCore（应用级单例，主进程一份）──────────────────────────┐
│                                                                  │
│  NotificationCatcher ─→ NowPlaying    （网易云等 MediaSession）   │
│  AmapLinkClient      ─→ Navigation    （高德 AIDL + AES-GCM）     │
│  LyricManager        ─→ 歌词           （music.163.com / lrclib）  │
│                          ↓                                       │
│  StateRenderer（四状态 + 内容去重，变了就上屏）                    │
│  SyncLifecycle（只有 HMI_FULL 才允许发 RPC）                      │
└───────────────────────────────┬──────────────────────────────────┘
                                │ attachSender() 挂上 / detachSender() 摘除
                                ▼
                           SdlService ──Show / SetMediaClockTimer──▶ 车机
```

**关键解耦**：数据采集与渲染都在 `SyncPlayCore`，`SdlService`（连接层）只是「借用」
一个出站通道。所以**车机断开、服务 `stopSelf` 之后，手机端的屏幕预览和歌词照常工作**
—— 这也是为什么 `MainActivity` 不依赖 `SdlService` 实例。

## 构建

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug
```

产物：`app\build\outputs\apk\debug\app-debug.apk`

## 安装（顺序不能错）

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$pkg = "com.xigaigai.syncglance"

& $adb install -r app\build\outputs\apk\debug\app-debug.apk
& $adb shell pm uninstall --user 900 $pkg    # ★ 必须在 install 之后：清掉「应用分身」副本
& $adb shell pm grant $pkg android.permission.BLUETOOTH_CONNECT
& $adb shell pm grant $pkg android.permission.BLUETOOTH_SCAN
& $adb shell am start -n $pkg/.activity.MainActivity
```

跳过第 2 行会出现「选择要使用的应用程序」弹窗（两个同名图标），表现得像闪退。

## 首次使用

1. 打开 App →「权限检查」授予蓝牙权限
2. 系统设置 → 通知使用权 → 打开「SyncGlance 通知读取」
3. 车机蓝牙连上 `SYNC`，App 会自动启动（也可手动开一次）
4. 手机打开高德并开始导航 → 车机自动显示导航；播放任意音乐 App → 显示歌名与歌词

## 改代码前必须知道的硬约束

| 约束 | 原因 |
|---|---|
| `targetSdk` 现在是 **35** | 4.12.2 时代只能锁 32：库内部老式两参 `registerReceiver` 会抛 `SecurityException`；sdl_android 5.7.0 修好后才允许提高（`legacy/sdl4` 分支仍是 32） |
| 主题必须 `Theme.AppCompat.*` | 否则 Activity 启动即崩 |
| 只有 `HMI_FULL` 能发 RPC | 其他等级发出去直接抛「没有有效连接」 |
| `mediaTrack` 两屏共用一个值 | SYNC 协议限制，无法分屏 |
| 一行只有 **7 个汉字 = 14 半角** | 实车照片实测（字段声明的 width=40 是假的） |
| `SetMediaClockTimer`：`PAUSE`/`RESUME`/`CLEAR` 不能带时间参数 | 带了必被拒；定格读数要两步法（`COUNTUP` → `PAUSE`），见 [AGENTS.md §6.6.1](AGENTS.md) |
| 时钟格**有**小时位 | `startTime.hours` 实测有效（`1:23:45`）；ETA 仍借 `mm:ss` 位显示 `16:21` |
| 车机不认 ⧗ ▶ ⏸ 和箭头 | 点阵字库缺字形，会渲染成「·」；统一由 `Glyphs.ENABLED` 控制 |
| 日文转写走 kuromoji 分词 | 不是逐字加空格；规则见 [design.md §2.5.1](docs/design.md)，别自己加空格 |
| 软键靠 `Show.softButtons` | **不是** `AddCommand`；回调走 `OnButtonPress{CUSTOM_BUTTON}` |
| 车机识别只看名字含 `SYNC` | 外加 MAC 白名单兜底，避免蓝牙耳机误触发 |
| `appName` 一旦定下永不改 | 它是车机策略表的查表键 |

## 目录结构

```
app/src/main/java/com/xigaigai/syncglance/
├── SyncGlanceApp 应用入口（进程名过滤、崩溃日志兜底、启动补连接探测）
├── activity/   MainActivity（控制面板）、LogActivity（日志 + 屏幕预览）
├── core/       SyncPlayCore（应用级单例：数据采集/渲染与连接层解耦）
├── service/    SdlService（连接/菜单/按键）、NotificationCatcher、CommandRouter、KeyMap
├── display/    StateRenderer（四状态渲染）、SyncLifecycle、ConnectionStatus、ScreenFrame、
│               ScreenPreviewView、ClockInstruction、Glyphs
├── lyric/      LyricManager（降级链 + 四级缓存）、SyncLyric（成品歌词）、Romaji（假名转写）、NeteaseLyric、TitleNorm、Lrc
├── nav/        AmapLinkClient（高德 AIDL）、LinkEnvelope（信封解密）
├── receiver/   SdlReceiver、BootReceiver、SdlRouterService（SDL 官方链路）
├── media/      NeteaseControl（系统媒体键）
├── model/      NowPlaying、Navigation
└── util/       Log2（日志中枢）、Kana（假名罗马音底层表 + 降级兜底）
```

## 文档

| 文件 | 内容 |
|---|---|
| [docs/design.md](docs/design.md) | **设计规格**：屏幕布局、四状态、歌词规则、数据来源、生命周期决策 |
| [docs/development.md](docs/development.md) | **实现与踩坑**：连接坑、按键去重、ETA 时刻、缓存策略、20+ 条实测教训 |
| [docs/todo.md](docs/todo.md) | 待办：锁屏界面（Ford 必测项）、VR Help、PRESET 绑定、权限缓存等 |
| [docs/device-connection.md](docs/device-connection.md) | 设备型号、adb 路径、安装/调试命令、启动失败对照表 |
| [docs/连接可靠性排查.md](docs/连接可靠性排查.md) | **连接问题必读**：秒退根因、受控重试、唤醒链路卡口、复测数据 |
| [docs/通知移除与存活权衡.md](docs/通知移除与存活权衡.md) | 通知「移除→恢复」全过程：两条通知的 SDL 固有设计依据、**文案状态矩阵**、图标规格、可回退步骤 |
| [docs/两分支维护.md](docs/两分支维护.md) | `main`（SDL 5.8）与 `legacy/sdl4`（4.12.2）的同步规则与有意差异 |
| [docs/sdl5-机制与生命周期.md](docs/sdl5-机制与生命周期.md) | 5.x 与 4.x 的 API 对照、按 FunctionID 注册回调等机制差异 |
| [docs/sdl生命周期-场景对照.md](docs/sdl生命周期-场景对照.md) | 官方要求逐条对照 + 实车场景时间线 |

> 外部资料（Ford 官方 1.6 文档、车机权限矩阵、固件逆向结论）不在本仓库内。
> 注意它们在个别结论上已过时：例如旧资料说车机语言是 `EN-GB`、软键必须用
> `AddCommand`、`customButtonID` 不支持 —— 实车日志已推翻，以 `docs/development.md` 为准。

## 当前状态

核心功能（R1–R17）均已实车验证可用。已知未完成项见 [docs/todo.md](docs/todo.md)，
其中 **锁屏界面**是福特官方验证项的强制要求，目前缺失会导致 4 条用例失败。

## 许可

本项目采用 **GNU General Public License v3.0**（GPL-3.0）发布，
完整条款见 [LICENSE](LICENSE)，版权与免责声明见 [NOTICE](NOTICE)。

- 允许：使用、修改、再分发，**包括商业性使用**
- 要求：分发（不管是修改版还是原版）必须**同样以 GPL-3.0 开放源代码**，
  并保留版权声明；不提供任何担保

第三方依赖的许可：

| 依赖 | 许可 |
|---|---|
| [smartdevicelink/sdl_java_suite](https://github.com/smartdevicelink/sdl_java_suite)（`sdl_android:5.8.0`） | BSD-3-Clause |
| [atilika/kuromoji](https://github.com/atilika/kuromoji)（日文分词，间接依赖） | Apache-2.0 |

GPL-3.0 与上述两个许可兼容（两者都是 GPL 兼容的宽松许可）。
