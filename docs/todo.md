# SyncPlay 待办事项

> 最后更新：2026-11-19
> 状态：🔴 未开始 / 🟡 进行中 / 🟢 已完成 / ⏸ 搁置

---

## 一、已完成

| # | 事项 | 完成日期 | 说明 |
|---|---|---|---|
| ✅ | 按键 300ms 去抖 | 11-19 | 修 OK 与 PLAY_PAUSE 同键双触发 |
| ✅ | `MY_PACKAGE_REPLACED` 重连 | 11-19 | 装包/升级后自动重连 |
| ✅ | 菜单名 / 标题栏名分离 | 11-19 | 列表「西改改的Sync」/ 标题「SyncPlay」 |
| ✅ | `Show.softButtons` 验证 | 11-19 | 实车确认可用，回调 `CUSTOM_BUTTON` |
| ✅ | 通知栏实时状态 | 11-19 | 跟随 `SyncLifecycle` 刷新 |
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
| ✅ | **倒计时上限 59:59** | 11-20 | 分钟滚轮 99→59，配 `clockTime()` 统一夹紧 |
| ✅ | **软键 6 个实测可用** | 11-20 | 车机底部 3 格 + `>` 翻页，送 2~6 个均 SUCCESS |
| ✅ | **指示符全部关闭** | 11-20 | 车机字库不认 ⧗▶⏸箭头，统一收进 `Glyphs` 开关 |
| ✅ | **字符测试按钮** | 11-20 | 16 组字符用 ScrollableMessage 逐组探测，含假名/半角假名/兼容汉字 |
| ✅ | **车机名统一 SyncPlay** | 11-20 | `APP_NAME` 改 SyncPlay；手机桌面仍是「西改改的Sync」 |
| ✅ | **软键上限提到 12** | 11-20 | `SOFT_KEY_MAX = 12`，测试车机最多认几个 |
| ✅ | **SDL 官方生命周期** | 11-20 | 加 `SyncRouterService` + `SdlReceiver` + `queryForConnectedService`，废弃看门狗 |
| ✅ | **应用分身抢启动** | 11-20 | 本机特有问题；`install` 之后再 `pm uninstall --user 900` |

---

## 二、待办

### A · 生命周期

*（autoActivateID 已完成，本组暂无遗留）*

---

### B · Ford 官方验证项

#### B1 · 锁屏界面 🔴

**优先级**：高（Ford 强制项）

**做什么**
- 前台服务起 `fullScreenIntent` 锁屏 Activity
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

按 14 章清单执行，重点这几条：

- [ ] 点「播放网易云」会不会跳转网易云界面
- [ ] 纯英文 / 德语歌词能否折成两行
- [ ] 不打开信息页时歌词是否自动滚动
- [ ] 计时器暂停时数值是否留在屏上
- [ ] 按 OK 键只触发一次
- [ ] **autoActivateID 生效后：杀掉 App，车机能否自动重新激活**
- [ ] **ETA 用 mm:ss 位承载后，那一格是否显示成 `16:21` 而不是别的**
- [ ] **倒计时设 59:59，车机是否接受并正常倒数**
- [ ] **车机标题栏是否变成 `SyncPlay`**（appName 已改）
- [ ] **软键测试一路点到 12 个**：车机从第几个开始不认？翻页键长什么样？
- [ ] **字符测试 16 组逐组看**：哪些有字形？重点看第 4~7 组假名、第 9 组兼容汉字
- [ ] **上车时先别开 App**，等车机显示「连接中」，再打开 App 看能否补连
