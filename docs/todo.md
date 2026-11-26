# SyncPlay 待办事项

> 最后更新：2026-11-20
> 状态：🔴 未开始 / 🟡 进行中 / 🟢 已完成 / ⏸ 搁置

---

## 一、已完成

| # | 事项 | 完成日期 | 说明 |
|---|---|---|---|
| ✅ | 按键 300ms 去抖 | 11-19 | 修 OK 与 PLAY_PAUSE 同键双触发 |
| ✅ | `MY_PACKAGE_REPLACED` 重连 | 11-19 | 装包/升级后自动重连 |
| ✅ | 菜单名 / 标题栏名分离 | 11-19 | 列表「西改改的Sync」/ 标题「SyncPlay」 |
| ✅ | `Show.softButtons` 验证 | 11-19 | 实车确认可用，回调 `CUSTOM_BUTTON` |
| ⛔ | ~~通知栏实时状态~~ | 11-19 → 11-22 移除 → 11-22 晚恢复 | 跟随 `SyncLifecycle` 刷新；恢复后文案改成 `Connected through SDL (HMI: 内容)`，见 `docs/通知移除与存活权衡.md` §12 |
| ✅ | 播放位置心跳 | 11-19 | 修「只有开预览页歌词才滚动」 |
| ✅ | 歌词半角宽度分行 | 11-19 | 修纯 ASCII 歌词永不换行 |
| ✅ | **autoActivateID 持久化** | 11-19 | 首次生成 UUID，重连回传 |
| ✅ | **`languageDesired` 传 null** | 11-19 | 跟随车机语言，避免 WRONG_LANGUAGE |
| ✅ | **按键别名去重（重写）** | 11-19 | 按 OK/PLAY_PAUSE 硬件别名合并，不再误伤快速连按 |
| ✅ | **启动时按 lifecycle 判断重建** | 11-19 | 修「蓝牙断开后手动开 App 监听死掉」 |
| ✅ | **蓝牙 MAC 白名单（集合）** | 11-19 | 不限数量，车机+测试电脑都记住 |
| ✅ | **转向码 21/23/24/25 映射纠错** | 11-20 | 原来写成「进入辅路/靠左/靠右/进入主路」全错，按高德 v11.1 重写 |
| ✅ | **补 65/66 靠左靠右** | 11-20 | 实车 66 出现在辅路分岔，之前显示成「未知(66)」 |
| ✅ | **type=5 抢跑导致箭头闪跳** | 11-20 | type 5 比 4 早 200ms 且编号不同，改为仅兜底 |
| ✅ | **ETA 借用 mm:ss 位** | 11-20 | `StartTime(16,21,0)`→`(0,16,21)`，修 REJECTED |
| ✅ | **ETA 只在换歌时刷新** | 11-20 | `setNavigation()` 补 `pushClock()`，导航推送即更新 |
| ⛔ | ~~倒计时上限 59:59~~ | 11-20 建 / 11-27 废除 | 车机时钟格有小时位，上限取消（现可设到 `59:59:59`），见 §5.3 |
| ✅ | **软键 6 个实测可用** | 11-20 | 车机底部 3 格 + `>` 翻页，送 2~6 个均 SUCCESS |
| ✅ | **指示符全部关闭** | 11-20 | 车机字库不认 ⧗▶⏸箭头，统一收进 `Glyphs` 开关 |
| ⛔ | ~~字符测试按钮~~ | 11-20 建 / 11-22 移除 | 16 组字符用 ScrollableMessage 逐组探测；调试功能清理时随 `CharProbe` 一起删（`130d078`） |
| ✅ | **车机名统一 SyncPlay** | 11-20 | `APP_NAME` 改 SyncPlay；手机桌面仍是「西改改的Sync」 |
| ✅ | **软键上限提到 12** | 11-20 | `SOFT_KEY_MAX = 12`，测试车机最多认几个 |
| ✅ | **SDL 官方生命周期** | 11-20 | 加 `SyncRouterService` + `SdlReceiver` + `queryForConnectedService`，废弃看门狗 |
| ✅ | **应用分身抢启动** | 11-20 | 本机特有问题；`install` 之后再 `pm uninstall --user 900` |
| ✅ | **串烧歌词误配** | 11-21 | 《艳舞台+烈焰红唇》显示成《烈焰红唇》的词；改 MEDIA_ID 直取 + 档位硬门槛 |
| ✅ | **仅歌曲两屏对调** | 11-21 | 车机会自己跳回第 1 屏；有歌词时第 1 屏给歌词、第 2 屏给歌名歌手 |
| ✅ | **清空歌词缓存按钮** | 11-21 | 删 `.lrc`/`.sync.lrc`/`.none` 并重查；替代掉「始终解析导航包」开关 |
| ✅ | **导航包按需解析** | 11-21 | 非 FULL 时只有信息页可见才解密，关掉页面就只读 fd |
| ✅ | **AmapLink 日志降噪** | 11-21 | 重试过程静默，只留「连接成功」「重试用尽」两条结果日志 |
| ✅ | **连接状态通知** | 11-21 建 / 11-22 移除 / 11-22 晚恢复 | 正文只报状态枚举；现格式 `Connected through SDL (FULL: PLY)`，见 design.md §5.7 |
| ✅ | **Router 存活上报** | 11-21 | SDL 5.8 无 `isRouterServiceRunning()`；由 `SyncRouterService` 发应用内广播自报生死 |

---

## 二、待办

### A · 生命周期

#### A1 · 按官方 Must 用 PendingIntent 启动 `SdlService` —— ⛔ 已放弃并删除代码（2026-11-24）

> 用户要求「强迫症般对齐官方实现」，前后试了三次，实车证明**这条路在本库版本上
> 结构性走不通**。相关代码（`startViaPendingIntent()` + 为它加的 intent-filter）
> **已全部删除**，只保留 `startForegroundService` 一条路。

**曾经做了什么**（现已全部撤销）：

1. `AndroidManifest.xml`：给 `SdlService` 加 `<intent-filter>`
   声明 `sdl.router.startservice`
2. `SdlReceiver.onSdlEnabled`：`PendingIntent` + `startSelf` 双路

**为什么最终走不通（2026-11-24 23:17 实车日志）**：

```
23:17:45.554  已通过 PendingIntent 启动 SdlService（官方 Android 12+ 路径）   ← send() 返回成功
23:17:45.570  已直接启动 SdlService（startForegroundService）
23:17:45.578  SyncPlay 服务创建
23:17:45.597  服务启动来源: startSelf（兜底路径）      ← 只有一条！说明 PendingIntent 没到服务
```

`onStartCommand` **只被调用了一次**，来源是 `startSelf`。即：
PendingIntent 那条 `send()` 之后**什么都没启动**。

根因（推翻了原先"fillIn 会补 action"的假设）：

- 库建的 PendingIntent 目标 Intent 只有 `setPackage(包名)`，**无 component、无 action**
- `PendingIntent.send(ctx, 0, fillInIntent)` 的 fillIn **不会**把广播的 action 补进去
  （本机再验证：加完 intent-filter 后，「只有 package」的 `am start-foreground-service`
  依然无效）
- 于是 system_server 解析不到任何 service → `not found`，且该异常**不回传**
  → 发送方拿不到失败信号，只能靠兜底

**结论**：这条官方 **Must** 在 `sdl_android 5.8.0` 上对 `SdlService` **无法落实**，
不是我们不做。2026-11-24 已把 `startViaPendingIntent()` 与那个 intent-filter
**全部删除**，只保留 `startForegroundService`。

**如果哪天又想要这条保险**：唯一现实路径是**自建**
`PendingIntent.getForegroundService(ctx, 0, Intent(ctx, SdlService.class), flags)`
—— 但失去官方豁免语义（豁免来自「由 Router 这个前台服务创建它」），
等于把 `startForegroundService` 包了一层，收益 ≈ 0。

**为什么之前走不通 → 怎么查明的**：

- 反编译 `SdlRouterService`：它建的 PendingIntent 目标 Intent 是
  `new Intent().setPackage(包名)` —— **没有 component、没有 action**
- 而 `SdlService` 当时**没有任何 intent-filter** → `send()` 之后 system_server 报
  `Unable to start service ... : not found`，且该异常**不回传给发送方**
  （所以 2026-11-21 12:49 日志里只有「已通过 PendingIntent 启动」却什么都没起）
- 本机用 `am start-foreground-service` 做三组对照实验，定位到真正原因：

  | 实验 | Intent 形态 | 结果 |
  |---|---|---|
  | 1 | `act=sdl.router.startservice` + `pkg` | ❌ `Unable to start service ... not found` |
  | 2 | `act=com.smartdevicelink.router.service` + `pkg`（Router 真声明的） | ✅ 解析到 `SdlRouterService` 并拉起进程 |
  | 3 | 只有 `pkg` | ❌ 什么都没起 |

  结论：**不是「隐式 Intent 不合法」**（`setPackage` 就够，实验 2 证明），
  而是**没有 service 声明那个 action**（它只挂在 `SdlReceiver` 这个 receiver 上）。

**当时为什么会误判"已打通"**：实验 1 是**人为带上 action** 的形态
（`-a sdl.router.startservice`），加了 intent-filter 后确实能拉起服务；
但库真实发出的是**实验 3**（只有 package）→ 加 filter 也救不了。

**最终判定（2026-11-24 实车两轮，定向日志证死）**：

```
21:33:51.580  已通过 PendingIntent 启动 SdlService（…）   ← 只是 send() 没抛异常
21:33:51.588  SyncPlay 服务创建
21:33:51.599  服务启动来源: startSelf（兜底路径）          ← 唯一一次 onStartCommand
```

→ **代码已删除**，含 `startViaPendingIntent()`、那个 intent-filter、
以及只为区分的 `EXTRA_START_FROM_SELF` 标记。
启动来源日志简化成两种：`startSelf（SdlReceiver 拉起）` / `系统重启（intent=null）`。

---

### B · Ford 官方验证项

#### B1 · 锁屏界面 🔴

**优先级**：高（Ford 强制项）

**做什么**
- 用前台服务的 `fullScreenIntent` 起锁屏 Activity（前台服务已恢复，这条路可用）
- 显示 `SYNC AppLink` 文案 + 退出按钮
- HMI FULL 时显示，BACKGROUND / NONE 时关闭

**影响用例**：4.6.7 / 4.6.8 / 4.6.10 / 4.6.11 —— **目前这四条必失败**

**工作量**：中（新增 Activity + 布局 + 与 `SyncLifecycle` 联动）

**风险**：中。锁屏会干扰日常使用，需确认显示时机

**待决策**
- [ ] 锁屏上放什么？只放 logo，还是加「退出 SyncPlay」按钮
- [ ] 锁屏是否要在每次 FULL 都弹，还是只在车机要求时弹
  （车机通过 `OnLockScreenStatus.showLockScreen` 给了 `OFF / OPTIONAL / REQUIRED` 三级信号，可以据此决定）

**相关日志**（已能收到，只是没处理）
```
OnLockScreenStatus { showLockScreen: REQUIRED, driverDistraction: true, hmiLevel: FULL }
```

---

#### B2 · VR Help 文本 🔴

**优先级**：中

**做什么**
- FULL 后发 `SetGlobalProperties`
- `helpPrompt` 填中文提示语

**影响用例**：4.6.6（原文要求「不是只听到 Help, cancel, and exit」）

**工作量**：小

**待决策**
- [ ] 提示语文案。建议：「支持播放、暂停、下一首、上一首、回家、去公司」

---

### C · 交互完整性

#### C1 · 长按支持 🔴

**优先级**：低

**做什么**
- `CommandRouter.handleButton()` 目前 `if (mode != SHORT) return`，长按完全忽略
- 加 SEEK 长按 = 快进 / 快退 10 秒

**影响用例**：6.1 硬按键清单里的 SEEK 长按

**工作量**：小

**注意**：长按需配合 `OnButtonEvent` 的 `BUTTONDOWN` / `BUTTONUP`，
判断按住时长或直接依赖 `OnButtonPress { LONG }`。本车
`buttonCapabilities` 里 SEEK 是 `longPressAvailable: true`。

---

#### C2 · PRESET 0-9 绑定 🔴

**优先级**：低

**做什么**
- 现在订阅了 10 个 PRESET 键但没绑任何功能，按了只记「未绑定」
- 给它们分配常用动作

**影响用例**：6.1 的 preset 项

**工作量**：小

**待决策**
- [ ] 映射表。建议：
  - PRESET_0 → 回家
  - PRESET_1 → 去公司
  - PRESET_2 → 正计时
  - PRESET_3 → 清零
  - PRESET_4 → 显示/隐藏歌词
  - PRESET_5~9 → 留空或自定义

---

### D · 健壮性

#### D1 · `OnPermissionsChange` 缓存 ⏸ 搁置

**优先级**：等真要用 `ScrollableMessage` 时再做

**做什么**
- 现在 `onOnPermissionsChange` 只记日志
- 缓存成 `Map<rpcName, Set<HMILevel>>`
- `sendRpc` 时按 RPC 名判断，避免在 LIMITED 发 FULL-only 的 RPC

**为什么搁置**：目前只在 FULL 发 RPC，暂未暴露。
如果以后用 `ScrollableMessage` 做「更多」动作表，这个就必须有
（`ScrollableMessage` 和 `Slider` 本车都只允许 FULL）。

**工作量**：中

---

#### D2 · 电池优化白名单引导 🔴

**优先级**：低（一次性体力活）

**做什么**
- 「权限检查」里加一项，检测是否在电池优化白名单
- 不在就跳系统设置

**影响**：4.2.16 / 4.2.17（手机休眠场景）的实际表现

**工作量**：小

**说明**：国产 ROM（小米/华为/OPPO/vivo）的自启动管理没有标准 API，
只能引导用户手动开，无法代码绕过。

---

### E · 长句翻页优先按空格分页 ⏸ 搁置（折行部分已完成）

**优先级**：低（纯观感优化，不做也能正常用）

**想法**

超过一屏（>28 半角）的歌词按宽度**硬切**分页，翻页点可能落在词中间
（`kimi ga ire|ba`）。希望分页点优先落在空格上：中文歌词本身带空格的、
日文转写后的词间空格、英文/德文，都断在词边界。

**已完成（2026-11）**

- **折行**改成「优先在空格处断」：`SyncLyric.splitTwo()` 现在走
  「对半但只在空格断 → 填满第一行（仍在空格断）→ 整屏无空格才硬切」。
  实测 `NO KONPYUUTAA NO` 以前会折成 `NO KONPY` / `UUTAA NO`，
  现在是 `NO KONPYUUTAA` / `NO`。
- **每屏时长**改成按字数比例分配（`Lrc.planPages()`），不再是平均分。
- 成品缓存签名升到 **v2**，旧 `v1-14x2.sync.lrc` 自动失效（文件会残留，见下）。

**仍搁置：翻页切片本身的词边界**

翻页时仍是「每屏 28 宽硬切」，所以**长句的翻页点仍可能落在词中间**。
彻底修法是让翻页切片也回退到空格，但代价是页数可能 +1、每页停留时间变短，
极窄页可能不到 1 秒（车机最快 1 秒刷一次）。要实车看过观感再决定。

**若恢复，先拍板**

- [ ] 回退门槛：空格离片尾多远就不回退（例如页宽 ≥22 才回退）
- [ ] 是否接受页数增加

**影响面**：车机可见（翻页点、页数）；恢复时需上车重验。
无空格的中文歌词行为不变。

---

## 三、暂时不做的

| 功能 | 原因 |
|---|---|
| 读取车辆数据（转速/车速/油量） | 策略表封锁，`DISALLOWED` |
| 诊断接口（ReadDID / GetDTCs） | 同上 |
| 过滤歌词元信息行（作词/作曲等） | 按需求保留显示 |
| 高德广播协议 | 手机版高德不发 |
| 自动拉起高德/网易云 | 手动控制更可控 |

---

## 四、已知但无法解决

| 问题 | 原因 |
|---|---|
| force-stop 后蓝牙自动拉起失效 | Android 的 stopped state 机制，广播不送达，需手动开一次 App |
| `⧗` / `▶` / `⏸` 字形未验证 | 车机点阵字体可能缺这些字，上车确认 |

---

## 五、上车测试待确认项

### 5.1 本轮（2026-11-22 晚）改动专项 —— 实车结果（23:36~23:45，3 轮连接）

> 本轮改了：PendingIntent 双路启动、通知恢复 + 新文案/图标、BootReceiver 日志措辞。

**T1 · PendingIntent 路径 —— 🟡 无报错，但无法"证死"是哪条路拉起的**
- [x] 三轮都出现 `已通过 PendingIntent 启动 SdlService（官方 Android 12+ 路径）`
- [x] 紧随其后有 `已直接启动 SdlService（startForegroundService）`（兜底，正常）
- [x] **没有** `Unable to start service ... : not found`
- [x] **没有** `ForegroundServiceStartNotAllowedException` / `★ 启动 SdlService 失败`
- [x] 三轮都 `★ 进入 FULL`
- [x] ⚠ 双路都在 5ms 内触发，日志里 `服务创建` 出现在 `startSelf` 之后 4~6ms，
      **已用诊断日志证死**（2026-11-24 23:17）：`onStartCommand` 只被调一次，
      来源是 `startSelf（兜底路径）` → **PendingIntent 那条实际没拉起服务**。
      详细分析见 A1。诊断日志保留在代码里（`服务启动来源: …`），以后换库版本可复查。

**T2 · 通知外观 —— ✅ 用户确认「通知没有问题」**

**T3 · 文案随状态 —— ✅**（用户确认；日志里状态流转正常，全程无异常）

**T4 · 秒失败与重试 —— ✅ 2 次，均第 1 次重试即成功**
- `23:38:58.441` 建连 5ms、`RouterAlive=false` → 2s 后重试 → 车机回话 +2.40s
- `23:43:26.092` 建连 1ms、`RouterAlive=false` → 2s 后重试 → 车机回话 +2.06s

**T5 · 日志措辞 —— 🟡 判定正确，但没覆盖冷启动**
- 三次都是 `【进程已运行】收到 蓝牙 ACL_CONNECTED`，且**判定是对的** ——
  该进程自 21 点多起就没死过（这段日志里完全没有 `应用启动`）
- [ ] **`【进程被拉起】` 这个分支还没被触发过**，要等进程真被系统杀掉再上车才能验

**T6 · 回归 —— ✅**
- 三轮全部进入 FULL，会话时长 92s / 230s / 18s
- 中间的正常事件：`失去焦点（LIMITED）` 后重新 FULL、`导航数据已过期 8 秒` 降级
- **无任何异常 / 崩溃**

**本轮耗时数据**（与前几轮一致，没有退化）

| 指标 | 第 1 轮 | 第 2 轮 | 第 3 轮 |
|---|---|---|---|
| onSdlEnabled → 服务创建 | 22ms | 17ms | 19ms |
| SdlManager 启动 → 车机回话 | 2.00s | 2.40s（重试后） | 2.06s（重试后） |
| 车机回话 → 进入 FULL | 24.1s（人工选应用） | 1.25s | 1.34s |

### 5.2 早前遗留项（有空再看）

按 14 章清单执行，重点这几条：

- [ ] 点「播放网易云」会不会跳转网易云界面
- [ ] 纯英文 / 德语歌词能否折成两行
- [ ] 不打开信息页时歌词是否自动滚动
- [ ] 计时器暂停时数值是否留在屏上（现在是两步法定格，见 5.3）
- [ ] 按 OK 键只触发一次
- [ ] **autoActivateID 生效后：杀掉 App，车机能否自动重新激活**
- [ ] **ETA 用 mm:ss 位承载后，那一格是否显示成 `16:21` 而不是别的**
- [x] ~~倒计时设 59:59，车机是否接受并正常倒数~~ → 上限已取消，改测 5.3 的小时位项
- [ ] **车机标题栏是否变成 `SyncPlay`**（appName 已改）
- [ ] **软键测试一路点到 12 个**：车机从第几个开始不认？翻页键长什么样？
- [ ] ~~**字符测试 16 组逐组看**~~：工具已在 `130d078` 移除；要复测需先加回 `CharProbe`。
      当时的结论已记在 `docs/development.md`
- [ ] **上车时先别开 App**，等车机显示「连接中」，再打开 App 看能否补连
- [ ] **仅歌曲模式下第 1 屏的歌词能不能一直看下去**（车机不再跳回歌名歌手）
- [ ] **通知栏文案是否跟着状态走**：`Waiting for connection (…)` → `Connecting through SDL (…)`
      → `Connected through SDL (FULL: PLY)`；格式见 `docs/通知移除与存活权衡.md` §12.3
- [ ] **断开后通知是否随服务一起消失**（官方语义：通知寿命 = 服务寿命）
- [ ] **两条通知图标是否一致**：我们那条用 `sdl_tray_icon`、无大图标；
      库那条 `SDL: 包名` 带绿色大图标（预期不一致，符合官方设计）
- ✅ **通知前后的短时稳定性**（11-22 已验）：清后台 / 不清后台各测，5 次连接全成功，
  响应时间与改动前持平；重试 3 次全部第 1 次就成功。数据见
  `docs/连接可靠性排查.md` §11。注意当晚通知已恢复，这组数据的价值在于
  「连接链路本身与通知无关」。
- [ ] **长时段稳定性**（11-22 新增，唯一剩余项）：熄火 30~60 分钟后再上车
  能否连上、手机后台放置超过 10 分钟服务是否还活着 —— 详见
  `docs/通知移除与存活权衡.md` §6.1 与 §7.4

### 5.3 2026-11-27「时钟格有小时位」改动 —— 待上车确认

**背景**：实车确认时钟格**有小时位**（`startTime.hours` 生效，能渲染 `1:23:45`）。
旧结论「只有 `mm:ss`、`hours` 必须为 0、倒计时上限 59:59」全部作废，
代码已按新结论改造（`feat(clock)` / `refactor(clock)` / `feat(timer)` 三个提交）。

#### 1. 计时器 / 倒计时（小时位本身）

- [ ] 倒计时设 `1:00:00` 是否被接受、是否正常从 1 小时往下倒数
- [ ] 倒计时设 `59:59:59`（界面小时上限 59）是否被接受 —— 车机真实小时上限待摸
- [ ] 正计时走过 `59:59` 后是否继续走到 `1:00:00`（不再停格）
- [ ] 倒计时到 `00:00` 是否**停住且不清屏**，底栏「计时」键保持高亮
- [ ] 此时点底栏「计时」键 → 是否发 `CLEAR` 并清掉（一次生效）
- [ ] 暂停中的计时器再点车机菜单「计时 → 继续」是否能接着走

#### 2. 歌曲播放时间

- [ ] 播放正计时是否带 `endTime`（歌曲总时长）
- [ ] 若被拒：日志应出现 `车机不接受 endTime（…），关闭「歌曲总时长」并重推时钟`，
      且随后自动降级、播放时间正常显示
- [ ] 播放暂停时那一格是否**定格在最后读数**（以前直接清空，与预览不一致）

#### 3. 导航 ETA

- [ ] 导航中那一格是否仍显示 `16:21`（借位策略**未改**，这条只是回归）

#### 4. 时钟串行队列

- [ ] 导航中同时操作计时器（开始 / 暂停 / 清零），定格读数是否还正确
      （以前两条两步法可能交错：`COUNTUP(ETA) → COUNTUP(timer) → PAUSE → PAUSE`）
- [ ] 日志里应能看到成对的 `开始发送时钟批次: … （2 条，严格按序）`
