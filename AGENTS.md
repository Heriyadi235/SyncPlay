# AGENTS.md

> 给在本仓库里工作的 AI / 协作者的行为规则。
> **以下四条是硬性要求，不因任务紧急或「顺手」而跳过。**

---

## 一、改代码前必须先出方案，等批准

**任何**改动之前，先把方案讲清楚，得到用户明确批准后才动手：

- 改哪些文件、具体改哪里
- 为什么要这么改、有没有别的选法
- 影响面：会不会改变车机上的显示/行为、要不要上车重验
- 涉及多个文件时，分步说明顺序

```text
❌ 直接开始写代码 / 一边改一边解释
✅ 先输出方案 → 用户批准 → 再改
```

判定范围包括：`.java`、`.xml`、`.gradle.kts`、`.gitignore`、构建脚本。
**纯文档改动**（README/docs）也建议先说明，除非用户已明确要求写某份文件。

---

## 二、提交 / 发布前做隐私检查

**本仓库是公开仓库**，任何 commit 之前都要扫一遍，确认没有把本机信息带上去：

| 类别 | 不能进仓库的内容 |
|---|---|
| 设备标识 | 手机序列号、机型、产品代号、蓝牙 MAC、设备名 |
| 本机信息 | Windows 用户名、电脑机器名、绝对路径（`C:\Users\...`、`D:\...`、`E:\...`） |
| 个人身份 | 真实姓名、邮箱、手机号 |
| 凭据 | token、密码、keystore / jks、`local.properties`、`.idea` |
| 运行产物 | 日志、APK、`build/`、`.gradle/`、截图 |

发现即替换成占位符，例如 `<设备序列号>`、`<用户名>`、`<工程目录>`、`<工作目录>`，
**不要把真实值提交进去**（包括写进提交说明、代码注释）。

> 历史教训：仓库曾把手机序列号、Windows 用户名、笔记本机器名、真实邮箱提交上去过，
> 靠重写 git 历史才清掉。新内容不要再犯。

---

## 三、不自动推送到远端

| 操作 | 谁做 |
|---|---|
| 本地 `git commit` | AI 可以做 |
| `git push` | **用户审核后手动执行** |
| `git push --force` | **用户审核后手动执行** |
| 删除/重建远端仓库、改仓库可见性 | **用户决定** |

默认工作流：**AI 只提交到本地，由用户 review 之后自己 push**。
唯一例外：用户在本轮明确要求「推到 GitHub」，此时才推。

---

## 四、全程用中文思考（**包括内部推理，不只是输出**）

这条说的是**思考语言**，不是「最后用中文回复」。推理、分析、权衡、写代码前的
构思过程，一律用中文进行；**不要英文想完再翻译成中文**。

| 场景 | 要求 |
|---|---|
| 内部推理 / 分析 / 方案构思 | **中文** |
| 回复用户 | 中文 |
| 代码注释、提交说明、文档 | 中文 |
| 变量名、类名、API 名、RPC 字段名 | 保持英文原文（如 `mainField1`、`SetMediaClockTimer`、`OnHMIStatus`） |
| 引用的报文字段、日志原文 | 原样引用，不要翻译 |

理由：

- 这个项目的所有文档、日志、踩坑记录都是中文的，术语是中文的
  （"恢复连接""软键""车机""串烧""预览器"）。用英文思考会在这些词上产生偏差，
  进而写出与既有文档对不上的注释和结论。
- 很多判断依赖中文语义细节（例如歌名里的「Live」「串烧」「+」、「艳舞台」vs「烈焰红唇」），
  用英文推理容易漏掉全角括号、`+` 分隔符这类中文语料特有的坑。
- 用户全程用中文提需求，思考语言一致才能准确对齐意图，而不是先译成英文再转回来。

⚠️ 与「一、改代码前必须先出方案」配合：方案也要用中文讲，不要中英混杂。

---

## 五、项目速览

把 Android 手机变成福特 SYNC 1.1 车机信息面板的 AppLink 应用
（歌名 / 歌词 / 导航指令 / 计时器）。包名 `com.xigaigai.syncglance`，车机上显示为 SyncPlay。

**动手前先读**：[README.md](README.md)（含构建/安装/硬约束）与
[docs/design.md](docs/design.md)、[docs/development.md](docs/development.md)（含 20+ 条实车踩坑记录）。

几条最容易踩的硬约束（详见 README「改代码前必须知道的硬约束」）：

- `targetSdk = 35`（SDL 5.8.0 底座）。**4.12.2 时代只能 32** —— 库内部老式两参
  `registerReceiver` 会抛 `SecurityException`；5.7.0 修好后才允许提高。
  `legacy/sdl4` 分支仍保持 32，别把两边的结论混用。
- 只有 `HMI_FULL` 才能发 RPC
- `appName` 一旦定下**永不改**（车机按它查策略表）
- 一行只有 **7 个汉字 = 14 半角**（屏幕容量；协议 width=40 只是上限，见 6.2）；时钟格没有小时位
- 车机字库不认 ⧗ ▶ ⏸ 与箭头

---

## 六、车机能力（**权威来源，优先级高于任何推测/固件逆向笔记**）

本车机（2012 福克斯 / SYNC 1.1 / MFD4）的实际能力以这两份**实车报文原文**为准：

| 文件 | 内容 |
|---|---|
| [docs/车机能力说明报文.txt](docs/车机能力说明报文.txt) | `RegisterAppInterface` Response 原文（车机自报能力） |
| [docs/车机权限报文.txt](docs/车机权限报文.txt) | `OnPermissionsChange` 原文（可用 RPC + 各 RPC 允许的最低 HMI 等级） |

> **规则：凡是与这两份报文冲突的说法，一律以报文为准。**
> 历史上 `SYNC1.1-AppLink开发总纲.md` / 固件逆向笔记里有多处结论已被实车推翻
> （如"语言是 EN-GB"、"软键必须用 AddCommand"、"ScrollableMessage 没有 timeout 字段"）。
>
> **唯一的例外**：报文里 `buttonCapabilities` 少了 `PLAY_PAUSE`（见 6.3），
> 那是那次 dump 的缺项，以能力清单/实测按键事件为准。

### 6.1 基本身份

| 项 | 值 |
|---|---|
| `language` / `hmiDisplayLanguage` | **ZH-CN** |
| `sdlVersion` / `systemSoftwareVersion` | **2.1** / **5.11** |
| `syncMsgVersion` | **2.0.0** |
| `vehicleType` | Ford / 2015 / C346A FOCUS CHINA |
| `displayType` / `graphicSupported` | **MFD4** / **false**（不能用任何图片） |
| `mediaClockFormats` | `CLOCK3`, `CLOCKTEXT4` |
| `hmiZoneCapabilities` | FRONT |
| `speechCapabilities` | TEXT, PRE_RECORDED, SILENCE, LHPLUS_PHONEMES |
| `vrCapabilities` | TEXT |
| `presetBankCapabilities.onScreenPresetsAvailable` | **false**（没有屏上预设库） |
| `iconResumed` | false |

### 6.2 文本字段（16 个；`width` 是**协议上限**，不是屏幕容量）

全部 `rows=1`、`characterSet=CID1SET`。

**`width=40` 的准确含义**：PRC 参数允许这个字段最长 40 个字符，
这是**协议层的上限**（写超了才会被拒），**不代表屏幕能显示 40 个字符**。
本车机一行实测只装得下 **7 个汉字 = 14 个半角**，超出的部分会被截断；
有的车机会用滚动的方式把长文本显示出来（本机没观察到滚动）。

两个约束要同时满足：

- **写多长不会被协议拒绝** → 看 `width`（40）
- **写多长才看得见** → 看屏幕（7 汉字 / 14 半角），排版一律按这个来

```
mainField1  mainField2  mainField3  mainField4
statusBar   mediaClock  mediaTrack
alertText1  alertText2  alertText3
scrollableMessageBody  (width=200，唯一例外，可塞长文本)
initialInteractionText
audioPassThruDisplayText1  audioPassThruDisplayText2
sliderHeader  sliderFooter
```

### 6.3 物理按键：**清单里有 PLAY_PAUSE，只是我们不用它**

能力清单里的按键：

```
OK  SEEKLEFT  SEEKRIGHT  PRESET_0 … PRESET_9  PLAY_PAUSE
```

（`PLAY_PAUSE` 出现过重复声明；全部 `shortPressAvailable = longPressAvailable = upDownAvailable = true`）

- **`PLAY_PAUSE` 是有的**。旧说法"车机没声明 PLAY_PAUSE"是错的，已纠正。
- **`OK` 与 `PLAY_PAUSE` 对应同一个物理键**：按一次这个键会**同时**收到两条
  `OnButtonPress`（`OK` 和 `PLAY_PAUSE`，间隔 5~11ms）。
- 因为这两个事件同时触发，**本项目主动不使用 `PLAY_PAUSE`** ——
  所以 `subscribeAllKeys()` 跳过它是**有意的取舍**，别当成 bug 去"修"。
- 若将来真要订阅它：必须做「同一物理键别名去重」（200ms 窗口内后到的丢弃），
  且**不能**按 cmdID 通用去抖 —— 那会把用户快速连按同一个键也吃掉。
- **不支持**：`TUNEUP` / `TUNEDOWN`（返回 `UNSUPPORTED_RESOURCE`）
- ⚠️ 材料不一致的实情：`docs/车机能力说明报文.txt`（某次 `RegisterAppInterface`
  Response 原文）的 `buttonCapabilities` 只有 13 条、缺 `PLAY_PAUSE`。
  那份是 dump 时的缺项，**以能力清单和实测按键事件为准**。

### 6.4 软键能力：最多 8 个，每屏 4 个槽

```
softButtonCapabilities: 1 条
  shortPressAvailable = true
  longPressAvailable  = false
  upDownAvailable     = false
  imageSupported      = true（但 graphicSupported=false，图片实际用不了）
```

→ 软键只能是**纯文本**、只支持**短按**。

**底栏排版（实车结论）**：每屏 4 个槽，**第 1~3 槽放软键，第 4 槽不是「更多」就是翻页键**。

不翻页（**1~3 个**软键）—— 第 4 槽是「更多」：

```
3 个键：  键1   键2   键3   更多
```

需要翻页（**4~8 个**软键）—— 每页第 4 槽都是翻页键，**最后一页在剩余键位后面补「更多」**：

```
4 个键：  第 1 页  键1   键2   键3   翻页
          第 2 页  键4   更多   -     翻页

8 个键：  第 1 页  键1   键2   键3   翻页
（上限）  第 2 页  键4   键5   键6   翻页
          第 3 页  键7   键8   更多  翻页
```

归纳成三条：

1. 第 1~3 槽永远放软键，**软键数 ≤ 3 时槽 4 是「更多」，> 3 时槽 4 是翻页键**
2. 翻页时**最后一页**的剩余键位后面补「更多」（所以 4 个键时它在第 2 槽，8 个键时在第 3 槽）
3. **最多 8 个软键**；一次性不翻页最多 **3 个**

> 1~2 个软键的画面没实测，按上面第 1 条应当是左对齐、槽 4 仍是「更多」。

回调：`OnButtonPress { buttonName: CUSTOM_BUTTON, customButtonID }`（实车已验证 id=9001）。



### 6.5 可用 RPC：43 个，其余一律不可用

**只有 FULL 能用**（掉到 LIMITED 就会失败）：

```
ScrollableMessage    Slider
```

**LIMITED / FULL**：

```
Alert  Speak  PerformInteraction  PerformAudioPassThru  EndAudioPassThru  OnAudioPassThru
```

**BACKGROUND / LIMITED / FULL**（常用区）：

```
Show  SetMediaClockTimer  AddCommand  AddSubMenu  DeleteCommand  DeleteSubMenu
CreateInteractionChoiceSet  DeleteInteractionChoiceSet
SubscribeButton  UnsubscribeButton  GenericResponse  GetSystemCapability
OnHMIStatus(含 NONE)  OnCommand  OnButtonPress  OnButtonEvent  OnDriverDistraction
```

**含 NONE**（注册类/文件类/云通道）：

```
RegisterAppInterface  UnregisterAppInterface  ChangeRegistration
SetGlobalProperties  ResetGlobalProperties  SetDisplayLayout  SetAppIcon
ListFiles  PutFile  DeleteFile
EncodedSyncPData  OnEncodedSyncPData  SystemRequest  OnSystemRequest
OnHashChange  OnLanguageChange  OnPermissionsChange  OnAppInterfaceUnregistered
```

**完全没有**（别写，写了必然被拒）：

```
GetVehicleData  SubscribeVehicleData  UnsubscribeVehicleData
DiagnosticMessage  ReadDID  GetDTCs
ShowConstantTbt  OnTBTClientState  OnVehicleData
SendLocation  GetWayPoints  SubscribeWayPoints
OnKeyboardInput  OnTouchEvent  OnWayPointChange
```

### 6.6 参数坑（都踩过）

| RPC | 坑 |
|---|---|
| `ScrollableMessage` | `timeout` 单位是**毫秒**（用 `20000`）。填 `20` 会被判 `INVALID_DATA: .timeout out of range` |
| `ScrollableMessage` / `Slider` | **仅 FULL** 可用 |
| `SetMediaClockTimer` | `startTime.hours` 必须为 **0**（时钟格只有 mm:ss），否则 `REJECTED`；分钟/秒 ≤ 59 |
| `AddCommand` | `vrCommands` 不能是空串，也不能全是空串 |
| `SetGlobalProperties` | `TTSChunk` 必须带 `name` 字段 |
| `Show` | 覆盖式：不带 `softButtons` 上一次的按钮就消失；`setMediaTrack("")` 才能真正清空 |
| `Show.softButtons` | 最多 **8 个**；≤3 个时不翻页（槽 4 是「更多」），>3 个时每页槽 4 变翻页键，见 6.4 的排版图 |

---

## 七、调试与装机（adb）—— 每次改代码都要用

> 本节从本机那份 `DEVICE-CONNECTION.md` 提炼（那份文档含序列号/用户名，**不进仓库**）。
> ⚠️ 那份文档里「`sdl_android:4.12.2` / `targetSdk 32`」的段落**已过时**，
> 本项目现在是 `5.8.0` / `targetSdk 35`，别照抄。

### 7.1 环境

```powershell
# adb：用环境变量拼，避免把用户名写进仓库
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

# 构建用 JDK（Gradle 必须要，否则报 JAVA_HOME is not set）
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"

# 只有一台设备时不要加 -s <序列号>，adb 会自动选中；
# 多设备时先 `& $adb devices` 看第一列。
```

### 7.2 装机顺序（★ 顺序不能反）

```powershell
$pkg = "com.xigaigai.syncglance"
$apk = "app\build\outputs\apk\debug\app-debug.apk"

& $adb install -r $apk
& $adb shell pm uninstall --user 900 $pkg        # ★ 必须在 install 之后
& $adb shell pm grant $pkg android.permission.BLUETOOTH_CONNECT
& $adb shell pm grant $pkg android.permission.BLUETOOTH_SCAN
& $adb shell am start -n $pkg/.activity.MainActivity
```

**为什么必须清 user 900**：这台机器会自动把通过 adb 安装的应用复制到
「应用分身」用户（user 900），桌面出现两个同名图标，启动时弹「选择要使用的应用程序」，
`am start` 落到 `ResolverActivity`，表现完全像"闪退 / 点了没反应"。

- **反着做（先清后装）无效** —— install 时系统会立刻再复制一份。
- 每次重新安装后都要再清一次，直到查不到：
  ```powershell
  & $adb shell pm list packages --user 900 | Select-String 'syncglance'   # 应为空
  ```
- 主用户安装记录损坏（`dumpsys package` 里 `User 0: installed=false`）时：
  `& $adb shell pm install-existing --user 0 $pkg`

### 7.3 截图（★ 不要用 `exec-out` 重定向）

```powershell
& $adb shell screencap -p /sdcard/s.png
& $adb pull /sdcard/s.png build\shot.png
& $adb shell rm /sdcard/s.png
```

**踩过的坑**：`& $adb exec-out screencap -p > shot.png` 在 PowerShell 里会把二进制当文本重定向，
写出来的 PNG 是坏的，`read_image` 直接报 `Unsupported or malformed image data`。
**一律走「设备上存文件 → pull」。**

### 7.4 点按钮不要猜坐标

```powershell
& $adb shell uiautomator dump /sdcard/ui.xml
& $adb pull /sdcard/ui.xml build\ui.xml
# 从 xml 里取 text / content-desc 对应的 bounds="[x1,y1][x2,y2]"，算中点再
& $adb shell input tap <x> <y>
```

### 7.5 常用排查

```powershell
& $adb logcat -c
& $adb shell am force-stop $pkg
& $adb shell am start -n $pkg/.activity.LogActivity
& $adb logcat -d -v brief | Select-String 'SyncGlance|Lyric|AmapLink'
& $adb logcat -d -v brief | Select-String 'FATAL EXCEPTION' -Context 0,12
& $adb shell pidof $pkg                        # 进程活着吗
& $adb shell dumpsys window | Select-String 'mCurrentFocus'
```

连上网易云这类接口时，也可以在电脑上直接复现 App 的请求（排查歌词匹配很有效）：
`Invoke-WebRequest -UseBasicParsing -Headers @{'User-Agent'=...;'Referer'='https://music.163.com/'} -Uri <url>`。

### 7.6 这台机器的 PowerShell 是 5.1，有几个坑

| 坑 | 表现 | 做法 |
|---|---|---|
| 沙箱后端 | 默认 `workspace-write` 报 `--temp is not an existing directory` | 所有 pwsh 调用带 `sandbox_permissions: danger-full-access` |
| `2>&1 \|` | Gradle 的 stderr 被当成 `NativeCommandError`，**exit code 1**，但构建其实成功 | 认 `BUILD SUCCESSFUL`，别认 exit code |
| `Invoke-WebRequest` | 非交互模式报「朗读和提示功能不可用」 | 必须加 `-UseBasicParsing` |
| `[System.Web.HttpUtility]` | `TypeNotFoundException` | 用 `[uri]::EscapeDataString(...)` |
| 中文编码 | 中文变乱码 | 写文件用 UTF-8；`Select-String`/`Get-Content` 带 `-Encoding UTF8` |

### 7.7 构建

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug --console=plain
# 产物：app\build\outputs\apk\debug\app-debug.apk
```


