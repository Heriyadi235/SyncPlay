# AGENTS.md

> 给在本仓库里工作的 AI / 协作者的行为规则。
> **以下三条是硬性要求，不因任务紧急或「顺手」而跳过。**

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

## 四、项目速览

把 Android 手机变成福特 SYNC 1.1 车机信息面板的 AppLink 应用
（歌名 / 歌词 / 导航指令 / 计时器）。包名 `com.xigaigai.syncglance`，车机上显示为 SyncPlay。

**动手前先读**：[README.md](README.md)（含构建/安装/硬约束）与
[docs/design.md](docs/design.md)、[docs/development.md](docs/development.md)（含 20+ 条实车踩坑记录）。

几条最容易踩的硬约束（详见 README「改代码前必须知道的硬约束」）：

- `targetSdk` 只能是 **32**，更高会让 SDL 库内部崩
- 只有 `HMI_FULL` 才能发 RPC
- `appName` 一旦定下**永不改**（车机按它查策略表）
- 一行只有 **7 个汉字 = 14 半角**；时钟格没有小时位
- 车机字库不认 ⧗ ▶ ⏸ 与箭头

---

## 五、车机能力（**权威来源，优先级高于任何推测/固件逆向笔记**）

本车机（2012 福克斯 / SYNC 1.1 / MFD4）的实际能力以这两份**实车报文原文**为准：

| 文件 | 内容 |
|---|---|
| [docs/车机能力说明报文.txt](docs/车机能力说明报文.txt) | `RegisterAppInterface` Response 原文（车机自报能力） |
| [docs/车机权限报文.txt](docs/车机权限报文.txt) | `OnPermissionsChange` 原文（可用 RPC + 各 RPC 允许的最低 HMI 等级） |

> **规则：凡是与这两份报文冲突的说法，一律以报文为准。**
> 历史上 `SYNC1.1-AppLink开发总纲.md` / 固件逆向笔记里有多处结论已被实车推翻
> （如"语言是 EN-GB"、"软键必须用 AddCommand"、"PLAY_PAUSE 可订阅"、"ScrollableMessage 没有 timeout 字段"）。

### 5.1 基本身份

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

### 5.2 文本字段（16 个，`width` 是假的）

全部 `rows=1`、`characterSet=CID1SET`。`width=40` **不代表能显示 40 个字符**——
实车实测一行只有 **7 个汉字 = 14 半角**（见 README 硬约束）。

```
mainField1  mainField2  mainField3  mainField4
statusBar   mediaClock  mediaTrack
alertText1  alertText2  alertText3
scrollableMessageBody  (width=200，唯一例外，可塞长文本)
initialInteractionText
audioPassThruDisplayText1  audioPassThruDisplayText2
sliderHeader  sliderFooter
```

### 5.3 物理按键：**13 个，没有 PLAY_PAUSE**

```
OK, SEEKLEFT, SEEKRIGHT, PRESET_0 … PRESET_9
```

- 全部 `shortPressAvailable = longPressAvailable = upDownAvailable = true`
- **`PLAY_PAUSE` 不在列表里**（旧文档说车机声明了两次，是错的）。
  所以 `subscribeAllKeys()` 跳过它是**正确行为**，不要"修"。
- OK 与 PLAY_PAUSE 是同一物理键的说法来自旧笔记，报文里只有 OK；
  现有的别名去重逻辑保留无害，但不要指望收到 `PLAY_PAUSE` 事件。

### 5.4 软键能力

```
softButtonCapabilities: 1 条
  shortPressAvailable = true
  longPressAvailable  = false
  upDownAvailable     = false
  imageSupported      = true（但 graphicSupported=false，图片实际用不了）
```

→ 软键只能是**纯文本**、只支持**短按**；`Show.softButtons` 的 `softButtonID` 靠
`OnButtonPress{CUSTOM_BUTTON, customButtonID}` 回调（实车已验证 id=9001）。

### 5.5 可用 RPC：43 个，其余一律不可用

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

### 5.6 参数坑（都踩过）

| RPC | 坑 |
|---|---|
| `ScrollableMessage` | `timeout` 单位是**毫秒**（用 `20000`）。填 `20` 会被判 `INVALID_DATA: .timeout out of range` |
| `ScrollableMessage` / `Slider` | **仅 FULL** 可用 |
| `SetMediaClockTimer` | `startTime.hours` 必须为 **0**（时钟格只有 mm:ss），否则 `REJECTED`；分钟/秒 ≤ 59 |
| `AddCommand` | `vrCommands` 不能是空串，也不能全是空串 |
| `SetGlobalProperties` | `TTSChunk` 必须带 `name` 字段 |
| `Show` | 覆盖式：不带 `softButtons` 上一次的按钮就消失；`setMediaTrack("")` 才能真正清空 |


