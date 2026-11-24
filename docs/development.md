# SyncPlay 开发文档

> 一个把手机变成 SYNC 1.1 车机信息面板的 Android 应用。
> 歌曲信息 · 导航显示 · 计时器
>
> 最后更新：2026-11-19

---

## 一、项目概述

### 1.1 这是什么

**SyncPlay** 是一个 AppLink 应用，运行在 Android 手机上，通过蓝牙把信息推送到福特 SYNC 1.1 车机的 4 寸屏幕上。

车主想要的场景：

- 开车时在车机上看到**正在播放的歌**和**歌词**
- 导航时看到**转向指令**、**剩余距离**、**预计到达时间**
- 一个随时可用的**计时器**（正计时 / 倒计时）
- 所有这些**不用掏手机**

### 1.2 目标车型

| 项 | 值 |
|---|---|
| 车型 | 2012 款福特福克斯（中国） |
| 车机 | SYNC 1.1 with MyFord Touch（MFD4 屏） |
| 车机固件 | 5.11 |
| SDL 协议版本 | 2.1 |
| 连接方式 | 蓝牙 SPP |

### 1.3 技术栈

```
Android (Java)          应用主体
  ├─ AppCompat 1.6.1    UI 框架
  ├─ sdl_android 4.12.2 AppLink 官方 SDK（关键依赖）
  ├─ NotificationListenerService  读通知/媒体信息
  ├─ AIDL （手写 Parcel）  连高德地图
  └─ 网易云音乐 API    《= 歌词主源
```

外部数据源
  ├─ 网易云/QQ音乐 《= MediaSession（经通知获取）
  ├─ 高德地图      《= AmapLinkService（穿戴设备通道）
  ├─ music.163.com 《= 歌词主源（搜索 + 拉 lrc）
  └─ lrclib.net    《= 歌词备源

构建
  ├─ AGP 9.5.1
  ├─ Gradle 9.6.0
  └─ Java 11
```

---

## 二、需求清单

### 2.1 核心需求

| # | 需求 | 状态 |
|---|---|---|
| R1 | 车机能发现并连接 App | ✅ |
| R2 | 应用列表显示「西改改的Sync」，车机标题栏显示「SyncPlay」 | ✅ |
| R3 | 显示当前歌曲名/歌手 | ✅ |
| R4 | 显示歌词（带时间轴，自动跟随） | ✅ |
| R5 | 显示导航转向指令、路名、剩余距离、ETA | ✅ |
| R6 | 计时器（正计时/倒计时/暂停/清零），暂停时仍占屏 | ✅ |
| R7 | 三个父菜单（音乐/导航/计时）+ 子项 | ✅ |
| R8 | 蓝牙连上名为 SYNC 的设备自动启动 | ✅ |
| R9 | 断连后自动重连（无需手动操作） | ✅ |
| R10 | 详细通信日志（可导出） | ✅ |
| R11 | 车机屏幕预览（SYNC 主题色画布） | ✅ |
| R12 | 假名自动转罗马音（车机无日文字库） | ✅ |
| R13 | 导航结束/超时自动降级到空闲/歌曲模式 | ✅ |
| R14 | 空闲模式也显示计时器 | ✅ |
| R15 | mediaTrack 按模式分别显示（歌名/剩余距离） | ✅ |
| R16 | 无歌词时显示「无歌词信息」 | ✅ |
| R17 | ETA 显示为「几点到达」的固定时刻，时刻变化才更新 | ✅ |

### 2.2 显示规则

#### 屏幕布局

车机 MFD4 两屏，每屏三行文本 + 一行 mediaTrack：

| 区域 | 第 1 屏 | 第 2 屏 |
|---|---|---|
| 第 1 行 | `mainField1` | `mainField3` |
| 第 2 行 | `mainField2` | `mainField4` |
| 第 3 行 | `mediaTrack` | `mediaTrack`（共用，无法分屏） |
| 第 3 行右 | `SetMediaClockTimer` | 同左 |

#### 四种状态

| 状态 | 第 1 屏 | 第 2 屏 | mediaTrack |
|---|---|---|---|
| A 空闲 | 问候语 / 西改改 | （空） | （空） |
| B 仅导航 | 转向+距离 / 路名 | ETA 文字 / 剩余距离 | 剩余距离 |
| C 仅歌曲 | 歌名 / 歌手 | 歌词两行 | 歌名 |
| D 导航+歌词 | 转向+距离 / 路名 | 歌词两行 | 剩余距离 |

#### 歌词显示规则

- 一行 7 个汉字宽，一屏两行 = 14 汉字宽（实测）
- 一句放得下 → 只占第 1 行，第 2 行留空
- 一句超一行 → 对半折成两行，**优先从空格处断开**
- 一句超一屏 → 按时间轴切成多段轮流显示（翻页）
- 无歌词 → 第 2 屏第 1 行显示「无歌词信息」
- 歌词时间轴**提前 0.5 秒**取词（补偿车机 1 秒刷新延迟）

#### track 状态指示符

mediaTrack 最前面固定一个字符，**跟随该行内容类型**（不跟状态编号走）：

| 该行内容 | 指示符 |
|---|---|
| 空 | 无 |
| 歌曲信息 | `▶` 播放 / `⏸` 暂停 |
| 导航信息 | `↑` `↓` `←` `→` `↖` `↗` `↙` `↘` 八方向 |
| 计时器激活（含暂停） | `⧗` 沙漏，覆盖以上全部 |

#### 软键

- 底栏 4 格，第 4 格「更多…」由车机自带，点进去是 AddCommand/AddSubMenu 的菜单页
- 第 1 格固定为播放/暂停软键（`▶` / `⏸`）
- 本车 `softButtonCapabilities` 只声明 1 项，能画几个待实车验证

### 2.3 明确的非目标

这些功能研究过但**不做了**：

| 功能 | 原因 |
|---|---|
| 读取车辆数据（转速/车速/油量） | 策略表封锁，`DISALLOWED` |
| 诊断接口（ReadDID/DTC） | 同上 |
| 锁屏界面 | 未实现，Ford 验证项会失败 |
| 过滤歌词元信息行（作词/作曲等） | 按需求保留显示 |
| 高德广播协议 | 手机版高德不发 |
| 自动拉起高德/网易云 | 手动控制更可控 |

---

## 三、核心架构

### 3.1 数据流总览

```
┌─────────────────────────────────────────────────────────────┐
│                          手机端                              │
│                                                              │
│  网易云音乐         高德地图           lrclib.net            │
│      │                 │                    │                │
│      │ MediaSession    │ AIDL 加密          │ HTTP           │
│      ↓                 ↓                    ↓                │
│  NotificationCatcher  AmapLinkClient   LyricManager          │
│      │                 │                    │                │
│      └────────┬────────┴────────────────────┘                │
│               ↓                                              │
│         StateRenderer  ← 四状态渲染 + 4秒节流                │
│               │                                              │
│               │ SDL RPC (Show / SetMediaClockTimer)          │
│               ↓                                              │
│          SdlService  ← 生命周期管理                          │
└───────────────┼─────────────────────────────────────────────┘
                │ 蓝牙 SPP
                ↓
┌─────────────────────────────────────────────────────────────┐
│                       SYNC 1.1 车机                          │
│  第1屏: mainField1 / mainField2                             │
│  第2屏: mainField3 / mainField4                             │
│  第3行: mediaTrack（两屏共用） + SetMediaClockTimer（右侧）  │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 屏幕布局（实车验证）

```
┌────────────────────────────────────────┐
│ SyncPlay                       📶 21:31│ ← 标题栏（app名+系统状态）
├───┬────────────────────────────────────┤
│ ▲ │ mainField1                         │
│SYNC│ mainField2                        │  ← 第 1 屏
│ ▼ │                                    │
│   │ mediaTrack            [计时器]     │ ← 第 3 行
├───┴────────────────────────────────────┤
│ [软键1] [软键2] [软键3] [More...]      │
└────────────────────────────────────────┘
              ↕ 翻页（右侧滚动条）
┌────────────────────────────────────────┐
│ SyncPlay                       📶 21:31│
├───┬────────────────────────────────────┤
│ ▲ │ mainField3                         │
│SYNC│ mainField4                        │  ← 第 2 屏
│ ▼ │                                    │
│   │ mediaTrack            [计时器]     │
├───┴────────────────────────────────────┤
│ [软键1] [软键2] [软键3] [More...]      │
└────────────────────────────────────────┘
```

### 3.3 四状态模型（最终定稿）

状态由「有没有导航」×「有没有歌曲」决定：

| 状态 | mainField1/2 | mainField3/4 | **mediaTrack** | **计时器（右侧）** |
|---|---|---|---|---|
| **A 空闲** | 问候语 / 西改改 | — | 空 | **计时器时间** 或空 |
| **B 仅导航** | 转向+距离 / 路名 | ETA文字 / 剩余距离 | **剩余距离 `23.0 km`** | **计时器** 或 ETA 时刻 |
| **C 仅歌曲** | 歌名 / 歌手 | 歌词两行（无歌词→「无歌词信息」） | **歌名**（只歌名，不带歌手） | **计时器** 或 播放进度 |
| **D 导航+歌曲** | 转向+距离 / 路名 | 歌词两行（无歌词→「无歌词信息」） | **剩余距离 `23.0 km`** | **计时器** 或 ETA 时刻 |

**关键规则：**

1. **计时器优先级最高**：只要用户在计时，第 3 行右侧永远显示计时器时间，ETA 和播放进度都让位
2. **mediaTrack 按模式分**：
   - 有导航（B/D）→ 显示**剩余距离**
   - 纯歌曲（C）→ 显示**歌名**
   - 空闲（A）→ 空
3. **ETA 只在到达时刻变化时才重发**：高德按分钟推 ETA，同一分钟内反复推送是同一个值，所以无需额外门槛，时刻一变就更新一次
4. **无歌词时**：第 2 屏第一行显示「无歌词信息」，第二行空

### 3.3.1 状态切换时机

| 事件 | 触发动作 |
|---|---|
| 收到 type 4/5（导航信息） | → B 或 D |
| 收到 type 100/200（导航结束/到达） | → 立刻降级到 A 或 C |
| 8 秒无导航数据（超高德闪退） | → 自动降级到 A 或 C |
| 收到媒体通知 | → C 或 D |
| 歌曲停止/通知消失 | → A 或 B |

**降级必须立即生效**，不能等下一次事件（这是实测发现的关键 bug，见第八章）。

### 3.4 歌词显示规则

- 单句 **> 7 个汉字** → 拆成两行（`mainField3` + `mainField4`）
- 单句 **≤ 7 个汉字** → 当前句 + 下一句
- 每行超过 7 字**由车机自动截断**（字段宽度 40 字符，实际显示约 7 汉字）

### 3.5 假名转罗马音（2026-11 改为**词级**）

车机没有日语字库，假名会显示成方块。所有发往车机的字符串都经过 `Romaji.convert()`：

```
きみがいれば   → kimi ga ireba       ← 按词切分
私 は 学生 です → 私 wa 学生 desu
新しい         → 新shii              ← 送假名紧跟汉字，不空格
カラオケ       → KARAOKE             ← 片假名词段大写
```

- 词边界由 **kuromoji（IPADIC）** 给，规则 R1~R9 见 [design.md §2.5.1](design.md)
- 接续助词粘前词（`いれ` + `ば` → `ireba`），格助词（は・が・を）仍然空格
- 拼写用平文式；长音不用 macron（车机字库只认 ASCII），改为重复元音
- 中文 / 英文 / 德语**不含假名** → 原样返回，不会被分词加空格
- kuromoji 不可用（老设备 OOM、资源缺失）时自动降级 `Kana.romajiLegacy`
  （旧的逐字空格），显示链路不中断
- 首次初始化 1~2 秒（词典 12.7 MiB 打在 jar 内），只发生一次；
  初始化发生在 IO 线程，不阻塞首屏

---

## 四、关键技术实现

### 4.1 AppLink 连接（最容易踩坑的部分）

**正确写法：**

```java
SdlProxyBuilder.Builder builder = new SdlProxyBuilder.Builder(
        listener,       // 实现 IProxyListenerALM
        APP_ID,         // 10 位数字字符串
        APP_NAME,       // 车机上显示的名字
        true,           // isMediaApp
        context);

// ★ 没有这行车机搜不到 App
builder.setTransportType(
        new MultiplexTransportConfig(getApplicationContext(), APP_ID));

// ★ 标题栏用短名，菜单列表用全名
builder.setShortAppName("SyncPlay");

// ★ autoActivateID：持久化，每次重连回传
builder.setAutoActivateID(loadOrCreateAutoActivateId());

// ★ 语言不指定（即传 null），跟随车机当前语言
//   不要调用 setLangDesired / setHMILangDesired

proxy = builder.build();   // 同步返回，不是 SdlManager.start()
```

**必须遵守的约束：**

| 约束 | 原因 |
|---|---|
| `targetSdk = 32` | 33+ 时 SDK 内部 `registerReceiver` 会抛 SecurityException（4.12.2 限制；5.8.0 起已提到 35） |
| 主题必须是 `Theme.AppCompat.*` | 否则 Activity 启动崩溃 |
| 装完执行 `pm uninstall --user 900 <pkg>` | 否则应用分身导致"闪退"假象 |
| `languageDesired` 传 null | 硬编码会在车机语言被切换时注册失败（`WRONG_LANGUAGE`） |

#### autoActivateID

首次启动生成 32 位无横线 UUID 存 `SharedPreferences`，之后一直复用：

```java
private String loadOrCreateAutoActivateId() {
    SharedPreferences sp = getSharedPreferences("sdl", MODE_PRIVATE);
    String id = sp.getString("auto_activate_id", null);
    if (id != null && !id.isEmpty()) return id;
    id = UUID.randomUUID().toString().replace("-", "");
    sp.edit().putString("auto_activate_id", id).apply();
    return id;
}
```

**作用**：官方说明它可「重新获取 HMILevel = FULL」，且**没有有效期限制**。
App 进程被杀、或车机重新扫描应用时，车机能凭这个 ID 直接重新激活成全屏。

**必须永久稳定**，一变车机就认不出是同一个实例。只有卸载重装才换新的。

实测日志：

```
首次:  ★ 首次生成 autoActivateID = ddac44dc4ac04f128c1fcef2c63cd435
重启:  复用 autoActivateID = ddac44dc4ac04f128c1fcef2c63cd435
```

### 4.2 生命周期管理

Ford 文档 (AppLink Android Developers Guide 1.6.0) 第 3.3.1 / 3.4 节规定：

**HMI 状态流转：**
```
NONE(firstRun=true) → BACKGROUND → LIMITED → FULL
FULL(ctx=MENU) → FULL(ctx=MAIN)      ← 从菜单返回
FULL → LIMITED → BACKGROUND           ← 失焦
```

**核心规则（`SyncLifecycle` 类实现）：**

| 规则 | 实现 |
|---|---|
| **只有 HMI_FULL 能发 RPC** | `sendRpc()` 先检查，非 FULL 直接丢弃 |
| BACKGROUND/NONE 时进行中的操作被终止 | `onLostFocus()` 处理，标记计时器需补发 |
| OnProxyClosed 后菜单/订阅全失效 | 重置 `menusBuilt` 标记 |
| resetProxy 用于重连，dispose 用于销毁 | 已区分 |

**断连重连（关键修复）：**

```java
// BootReceiver
case "android.bluetooth.device.action.ACL_CONNECTED":
    // 之前只调 startSdl()，SdlService 里 `if (proxy == null)`
    // 会短路（proxy 对象还在），结果什么都没做
    startSdl(context, /*forceReset=*/ true);
```

**更隐蔽的一种情况：启动时必须判断 lifecycle，不能只看 proxy 是否为 null。**

`onProxyClosed` 之后 `proxy` 对象<b>仍然存在</b>（只是失效了），
而 SDK 在 `TRANSPORT_DISCONNECT` 之后自己不会重建 SPP 连接。所以：

```
① 蓝牙断开 → onProxyClosed(TRANSPORT_DISCONNECT)，proxy 对象还在
② 没有蓝牙重连广播（用户没进车）
③ 用户手动打开 App → onStartCommand 看到 proxy != null → 跳过 startProxy()
④ 监听是死的 → 一直显示「未连接 · 等待车机」，永远不会重连
```

正确写法：

```java
private boolean needRebuild() {
    if (proxy == null) return true;
    // proxy 对象在，但连接已断 —— 必须重建
    return lifecycle.getState() == SyncLifecycle.State.DISCONNECTED;
}
```

而且要用 `resetProxy()` 而不是 `startProxy()`（后者开头有
`if (proxy != null) return;`，会挡掉失效的 proxy）。

### 4.2.1 蓝牙设备过滤

只响应目标设备，避免蓝牙耳机/音箱误触发重建。判定顺序：

| 顺序 | 条件 | 结果 | 记入 MAC 白名单 |
|---|---|---|---|
| 1a | 设备名含 `SYNC`（不分大小写） | ✅ | ✅ |
| 1b | 设备名读得到但都不匹配 | ❌ | — |
| 2 | 设备名读不到，MAC 在白名单 | ✅ | — |
| 3 | 设备名读不到，MAC 不在白名单 | ❌ | — |

**MAC 白名单用 `StringSet`，不限数量**：

```java
// 写：getStringSet 返回的集合不可直接修改，必须先复制
Set<String> set = new HashSet<>(sp.getStringSet(KEY_KNOWN_MACS, new HashSet<>()));
if (set.add(addr)) sp.edit().putStringSet(KEY_KNOWN_MACS, set).apply();
```

凡成功识别过的设备都记下来（车机 + 以后换的车互不覆盖），
以后名字读不到时还能靠 MAC 认出。

**名字读得到时以名字为准，不查 MAC** —— 名字变了说明换了设备。
（早期版本还有「名字含 FORD 也算」和「名字读不到就无条件放行」两条，
都已移除：前者误判配件，后者会让任何蓝牙耳机都触发重建。）

### 4.3 高德导航数据（AmapLink）

**这是整个项目最难的部分。**

高德手机版内置了一条给智能眼镜/穿戴设备的导航通道：

```java
Intent("com.autonavi.minimap.service.NAVIGATION_SERVICE")
  .setComponent(ComponentName("com.autonavi.minimap",
      "com.amap.bundle.wearable.connect.glass.AmapLinkService"))
```

**AIDL 接口（手写 Parcel 调用）：**

```java
ILinkSdkControl:
  transact 1 = 授权 → 返回 {"status":"success","data":{"token":"..."}}
  transact 2 = 执行命令（cmd/requestId/token/sessionId）
  transact 3 = 注册回调（writeStrongBinder）
  transact 4 = 注销回调

ILinkSdkCallback:
  transact 1 = String（小包）
  transact 2 = (String metadata, ParcelFileDescriptor fd)（大包走共享 FD）
```

**回调是 AES-GCM 加密的，但密钥就在信封里：**

```
Base64 解码后：[int idLen][id][int keyLen][key][nonce 12][ciphertext]
AES-GCM 解密，AAD = id，密钥 = 信封里带的 key
```

**业务数据格式（bizType == 113）：**

| type | 含义 | 关键字段 |
|---|---|---|
| 4 | 导航信息 | `curManeuverID` / `segmentRemainDist` / `routeRemainDist` / `routeRemainTime` / `nextRouteName` |
| 5 | 转向图标 | `maneuverID` |
| 7 | 开始导航 | — |
| 14 | 车道线 | `laneCount` / `backLane` / `frontLane` |
| 100 | 导航结束 | — |
| 200 | 已到达 | — |

**注意 type=5 不能用。** 它比 type=4 早到约 200ms，而且两者的编号经常不同：
5 的 `maneuverID` 是「下一条路的入口图标」，4 的 `curManeuverID` 才是「当前这一段该做的动作」。
拿 5 改写指令文字会让箭头先跳到新图标、200ms 后被 4 改回来，肉眼就是闪一下。
所以 type=5 只在「一句话都还没有」时兜底，其余情况一律忽略，转向以 type=4 为准。

**转向码（ManeuverIconID）：**

依据高德导航 SDK v11.1.0 的 `AmapIconType`（与车机 `ManeuverIconID` 同源）：

| 码 | 含义 | 码 | 含义 | 码 | 含义 |
|---|---|---|---|---|---|
| 0 | 无定义 | 20 | 顺行 | 42 | 滑道 |
| 1 | 自车 | 21 | 环岛左转 | 43 | 阶梯 |
| 2 | 左转 | 22 | 环岛右转 | 44 | 斜坡 |
| 3 | 右转 | 23 | 环岛直行 | 45 | 过桥 |
| 4 | 左前方 | 24 | 环岛掉头 | 46 | 轮渡 |
| 5 | 右前方 | 25 | 环岛左转(左行) | 47 | 地铁通道 |
| 6 | 左后方 | 26 | 环岛右转(左行) | 48 | 进入建筑 |
| 7 | 右后方 | 27 | 环岛直行(左行) | 49 | 离开建筑 |
| 8 | 掉头 | 28 | 环岛掉头(左行) | 50 | 电梯换层 |
| 9 | 直行 | 29 | 人行横道 | 51 | 楼梯换层 |
| 10 | 途经点 | 30 | 过街天桥 | 52 | 扶梯换层 |
| 11 | 进环岛 | 31 | 地下通道 | 53 | 红绿灯路口 |
| 12 | 出环岛 | 32 | 广场 | 54 | 路口 |
| 13 | 服务区 | 33 | 公园 | 55 | 直行（实测：导航刚启动） |
| 14 | 收费站 | 34 | 扶梯 | 65 | 靠左 |
| 15 | 到达终点 | 35 | 直梯 | 66 | 靠右 |
| 16 | 隧道出口 | 36 | 索道 | | |
| 17 | 进环岛(左行) | 37 | 空中通道 | | |
| 18 | 出环岛(左行) | 38 | 通道 | | |
| 19 | 掉头(左行) | 39 | 行人道路 | | |
| | | 40 | 游船路线 | | |
| | | 41 | 观光路线 | | |

**65/66 是 v11 新增的「靠左/靠右」。** 实车验证：2012 Focus 跑「长青街辅路」时，
高德推送的是 66 而不是任何转向码——那是辅路分岔处的靠右行驶，语义完全吻合。
早期版本没有这两个码，之前把它显示成「未知(66)」，会把内部编号暴露给驾驶者。

**未收录编号一律显示「行驶」**，不显示「未知(N)」。

**29~54 是骑行/步行专有图标**，驾车场景基本用不到，但为了日志可读性也做了映射。

**连接策略（按官方 iOS SDK 推荐）：**

```java
MAX_ATTEMPTS = 50;        // 官方 maxReconnectAttempts
RETRY_DELAY_MS = 2000;    // 官方 reconnectDelay
// 总共约 100 秒
```

**触发时机：**
- 用户点「高德地图」按钮 → 拉起高德，5 秒后连接
- 用户点「回家」/「去公司」 → 拉起导航，6 秒后连接
- 收到高德通知 → 说明它启动了，触发连接

### 4.4 歌曲信息

**不需要额外权限**，通过通知里的 `EXTRA_MEDIA_SESSION` 拿 `MediaSession.Token`：

```java
// NotificationCatcher
Object obj = n.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION);
MediaController c = new MediaController(this, (MediaSession.Token) obj);
MediaMetadata md = c.getMetadata();

String title = md.getString(METADATA_KEY_TITLE);
long pos = c.getPlaybackState().getPosition();
```

**关键点：通知只在内容变化时发**，播放位置不会实时推送。需要主动轮询：

```java
// 用"最后更新时间 + 流逝时间"推算真实位置
long base = ps.getPosition();
long since = SystemClock.elapsedRealtime() - ps.getLastPositionUpdateTime();
if (playing && since > 0) base += since;
```

### 4.5 歌词

**主源：网易云音乐**（实测无需登录、无需签名，只要带 `Referer`）

两步走：

```
① 搜索定位 songId
GET https://music.163.com/api/search/get/web?s=晴天&type=1&limit=8&offset=0
Header: Referer: https://music.163.com/

② 拉歌词
GET https://music.163.com/api/song/lyric?id=186016&lv=1&kv=1&tv=-1
```

返回的 `lrc.lyric` 就是标准 `[mm:ss.xxx]` 格式，可直接交给 `Lrc.parse()`。
首几行常是「作词/作曲/编曲」等人名，会被 `Lrc` 当作普通行显示，无副作用。

> ⚠️ 这是**非公开接口**，网易随时可能收紧（2025 年就收紧过登录校验）。
> 所以代码里 lrclib 是完整可用的后备，不能删。

**备源：lrclib.net**（免费、无 API Key）

```
GET https://lrclib.net/api/get?track_name=晴天&artist_name=周杰伦&duration=270
Header: User-Agent: SyncPlay v1.0
```

**注意：** `duration` 必须匹配 **±2 秒**，否则 404；限流约 45~90 次/分钟。

---

#### 4.5.1 缓存 key：原始完整歌名（重要）

**缓存身份一律用通知里拿到的原始完整标题**，不做任何剥括号：

```java
String raw = song.title.trim() + "|" + song.artist.trim();   // → MD5
```

**时长不参与 key。** 很多播放器不给 `METADATA_KEY_DURATION`（值为 0），
一旦时长参与 key，同名不同版本会全部撞在一起。

为什么不剥括号？因为剥了会把不同版本混成一谈：

| 标题 A | 标题 B | 剥括号后 | 后果 |
|---|---|---|---|
| `夕阳之歌` | `夕阳之歌 (Live)` | 都是 `夕阳之歌` | ❌ Live 歌词被缓存成录音室版 |
| 同上，时长还都 307s | | key 完全相同 | ❌ 再也查不对 |

所以：**歌名的身份交给原始字符串，查询时怎么变换是另一回事。**

---

#### 4.5.2 括号内容分两类（只影响「怎么查」）

`TitleNorm.queryTitle()` 只在发查询时调用，不参与缓存身份判定。

**A 类 — 可以剥**（歌词相同，剥掉能提高命中率）

- 译名：`君は薔薇より美しい (你比玫瑰更美丽)` → `君は薔薇より美しい`
- 出处：`梦缘 (梅艳芳"夢伴"原曲)` → `梦缘`
- 版本注释：`晴天（原唱 周杰伦）` → `晴天`
- 陌生演唱会之外的编曲：`晴天 (女声版)` → `晴天`
- 母带：`Bohemian Rhapsody (Remastered 2011)` → `Bohemian Rhapsody`

**B 类 — 必须保留**（歌词不同，剥了就查错歌）

`Live` / `现场` / `演唱会` / `Unplugged` / `Remix` / `混音` / `伴奏` /
`Instrumental` / `纯音乐` / `Karaoke` / `Off Vocal` / `翻唱` / `Cover` /
`片段` / `剪辑` / `试听` / `Acoustic` / `不插电` / `Demo`

判据只有一句话：**括号内容是否描述了「这首录音和原版有什么不同」**。
是 → 保留；只是译名/出处/注释 → 剥。

`stripBrackets` 用显式配对扫描（非正则回溯）：
- `晴天 (女声版))` → `晴天 (女声版)`（修掉多余的右括号）
- `别话 (别话 (…))` → `别话`（嵌套也能处理）

---

#### 4.5.3 歌词提前 0.5 秒

车机屏幕最快 1 秒刷一次。我们算歌词时拿到的是「此刻」的播放位置，
等 `Show` 送到车机、再画出来，已经过去几百毫秒，
所以新的一句总是慢半拍。

`StateRenderer.LYRIC_LEAD_MS = 500`，查表时把位置往后拨：

```java
Lrc.paged(lyrics, song.positionMs + LYRIC_LEAD_MS, 7, 2)
```

`ScreenPreviewView` 复用同一个常量，保证预览与实车一致。

#### 4.5.4 歌词分行与翻页（2026-11 改）

**一行 7 个汉字，一屏两行 = 14 字容量**（实车照片实测，见 §9.5）。

| 情况 | 显示 |
|---|---|
| 一句 ≤ 7 字 | 第一行显示，**第二行留空** |
| 8 ~ 14 字 | 折成两行 |
| > 14 字 | **按时长切成多段轮流显示**（翻页） |

翻页算法在 `Lrc.planPages()`（实车降级路径与 sync 成品预生成共用）：

```
总时长 span = 下一句开始时间 − 本句开始时间
段数 slices = ceil(字数 / 14)
每段时长   = span × (该段字数 / 本句总字数)   ← 按字数比例
当前段号   = 按各段起始时间查表
```

一句 35 宽、给了 8 秒，两段宽度 27 : 7，就是 6.3 秒 + 1.7 秒。
极窄的一段可能不足 1 秒（车机最快 1 秒刷一次）——已知取舍，见 §2.5。

**折行（一屏两行）** 走 `SyncLyric.splitTwo()`：① 对半但只在空格处断 →
② 填满第一行（仍在空格处断）→ ③ 完全没有空格才硬切。
旧实现 ① 是「±3 内找不到空格就硬切」，会把 `NO KONPYUUTAA NO` 折成
`NO KONPY` / `UUTAA NO`。

#### 4.5.5 【已修复】歌词只有打开预览页才滚动

**症状**：车机上的歌词几乎不动，一打开「通信日志」页（里面有屏幕预览）就突然跟上。

**根因**：播放位置 `positionMs` 的心跳原本写在 `LogActivity.refreshPreview()` 里：

```java
// 旧代码 —— 只在预览页刷新时执行
NotificationCatcher nc = NotificationCatcher.getInstance();
NowPlaying latest = nc.pollNowPlaying();
r.updatePlaybackPosition(latest.positionMs, latest.playing);
```

通知只在内容变化时才推，播放中的位置**不会**每秒主动送达。
所以不打开预览页时，`song.positionMs` 永远停在最后一次通知的值上，
`Lrc.currentAndNext()` 自然一直返回同一句。

**修复**：把心跳搬进 `StateRenderer`，用 Handler 每 **900ms** 自己拉一次：

```java
private static final long POSITION_POLL_MS = 900;
private final Runnable positionTicker = ... // 构造时启动
```

900ms 而不是 1000ms，是为了和 `Show` 的 1 秒节流错开，避免每次都刚好卡在边界上。

实测（不开预览页）：

```
19:21:34.483 [Render] 位置 42s
19:21:35.419 [Render] 位置 43s
19:21:36.358 [Render] 位置 44s
```

---

#### 4.5.6 完整的降级链

```
① 内存缓存
② 成品缓存（.sync.lrc：转写 + 折行 + 翻页都做完，显示时零算力）
②' 原始磁盘缓存（.lrc，key = 原始完整歌名 + 歌手 的 MD5，不含时长）
②.5 负缓存（.none 文件，6 小时过期）—— 跳过网络
③-A 网易云：搜歌（用**原始完整歌名**，不剥括号）→ 打分选候选 → 拉 lrc
③-B lrclib 三级：
      级别1  原名精确匹配
      级别2  剥括号后精确匹配
      级别3  /api/search 模糊搜索，按时长最接近挑一条
④ 显示"无歌词信息"
```

**成品歌词缓存（第 ② 层）**：

| 项 | 值 |
|---|---|
| 文件 | `<cacheDir>/lyrics/<md5>.v2-14x2.sync.lrc` |
| 首行签名 | `#syncglance sync-lyric v2; units=14; pages=2; romaji=hepburn; caps=kana-seg` |
| 行格式 | `[mm:ss.mmm]第一行␟第二行`（每行 = 一屏，U+241F 分隔，第二行可为空） |
| 失效 | 首行签名不一致 → 整份作废重建 |

- 只有 `.lrc` 而没有成品时：**先按旧路径显示**（不卡首屏），后台生成后替换。
- 0.5 秒提前量不写进文件 —— 那是查询时的补偿，改它不用重建缓存。
- 生成失败（比如 kuromoji 崩了）不影响显示：继续走旧路径，本次不写文件。

**竞态：请求序号**（`LyricManager.loadSeq`）

IO 是单线程且请求不可取消。快速切歌 A→B→A 时，A 的旧网络结果会晚于新请求返回，
以前会直接把 `current` / `renderer.lyrics` 覆盖成 A 的歌词，而车机在放 B。
现在每次 `load()` 自增序号，回调前比对，不一致就丢弃（日志：`丢弃过期歌词`）。

**网易云候选打分规则**（`NeteaseLyric.pickBest`）：

| 条件 | 分值 |
|---|---|
| 剥括号后标题完全相等 | +100 |
| 标题互相包含 | +40 |
| 标题完全不沾边 | −30 |
| 歌手完全相等 | +60 |
| 歌手互相包含 | +25 |
| 时长差 ≤2 秒 | +50 |
| 时长差 ≤5 秒 | +25 |
| 时长差 >30 秒 | −40 |

时长这一项是排除 Live / 翻唱最有效的手段。实车实测典型得分 210
（标题 100 + 歌手 60 + 时长 50）。

**负缓存的判定边界（很重要）：**

网络故障 ≠ 没有歌词。断网时若写入负缓存，这首歌会「消失」6 小时。
代码用两个标志区分：

- `NeteaseLyric.lastWasNetworkError()` —— 连接/读取异常置 true
- `LyricManager.lastHttpWasNetworkError` —— 同上，供 lrclib 各层使用

只有**所有源都正常返回、确实没找到**时才写 `.none`。

#### 4.5.7 模块结构与职责

| 类 | 职责 | 关键方法 |
|---|---|---|
| `LyricManager` | 调度、四级缓存、请求序号 | `load()` / `buildSync()` / `apply()` / `cacheKey()` |
| `SyncLyric` | 成品歌词的生成 / 读写 / 取屏 / 折行 | `build()` / `read()` / `write()` / `fileFor()` / `pageAt()` / `splitTwo()` |
| `Romaji` | 假名→罗马音（kuromoji 分词 + 平文式） | `convert()` / `convertByTokens()` / `needSpace()` |
| `Kana` | 假名罗马音底层表；旧逐字实现（降级兜底） | `romajiOfKana()` / `romajiLegacy()` |
| `Lrc` | LRC 解析、半角宽度口径、**分页唯一实现** | `parse()` / `widthOf()` / `planPages()` / `paged()` |
| `TitleNorm` | 只决定「怎么查」，不参与缓存身份 | `stripBrackets()` / `primaryArtist()` |
| `NeteaseLyric` | 主源：搜索 + 拉 lrc + 候选打分 | `fetch()` / `pickBest()` |
| `StateRenderer` | 取当前屏、0.5 秒提前量、900ms 心跳 | `computeLyricLines()` |
| `SyncPlayCore` / `SdlService` | 换歌入口、清旧歌词、「显示歌词」重载 | `onNowPlaying` / `reloadLyrics()` |

依赖是单向无环的：

```
SdlService / SyncPlayCore  →  LyricManager  →  { NeteaseLyric, SyncLyric, Romaji }  →  Lrc
StateRenderer          →  { SyncLyric, Lrc }
Romaji                 →  Kana（底层假名表）
```

> `Kana.romajiLegacy()` 只被 `Romaji` 的失败分支调用；业务代码不要直接用
> `Kana.romajiSpaced()`（已标 `@Deprecated`，内部转发到 `Romaji.convert`）。

**线程模型**：`current` / `currentKey` / `loadSeq` 只在主线程改；
网络请求与成品生成跑在 `LyricManager` 的单线程 `ExecutorService`，结果通过
`main.post` 回主线程再 `apply`。

#### 4.5.8 一次换歌的完整时序

```
NotificationCatcher（每条通知都会触发）
  → SyncPlayCore.onNowPlaying(np)
      songChanged = !np.sameSong(cur)        // 只比 title + artist，精确相等
      renderer.setSong(np)
      if (songChanged) {
          renderer.setLyrics(null)           // ★ 先清掉上一首，防「新进度切旧时间轴」
          lyricManager.load(np, cb)
      }
      pushClock()
```

`load()` 内部按顺序命中，命中即返回：

| 顺序 | 条件 | 动作 | 回调 |
|---|---|---|---|
| 1 | 内存命中（`key == currentKey && current != null`） | 直接回调 | 主线程，同步 |
| 2 | `<md5>.v2-14x2.sync.lrc` 存在且签名对 | 读成品 → `apply` | 主线程，同步 |
| 3 | `<md5>.lrc` 存在 | 先显示原始歌词，再后台生成成品替换 | 同步 + 异步各一次 |
| 4 | `<md5>.none` 未过期（6 小时） | 回调 null | 主线程，同步 |
| 5 | 以上都没有 | 网络：网易云 → lrclib 三级 | io 线程，异步 |

第 3 步会回调两次，**两次都带同一个 `seq`**：如果用户已经切歌，第二次会被序号拦掉。

#### 4.5.9 成品歌词缓存（`.sync.lrc`）

**为什么需要它**：转写要分词（kuromoji 首次 1~2 秒），折行/翻页也要算；
而同一首歌的结果永远不变，没必要每帧重算。成品把「转写 + 折行 + 翻页」一次性做完。

**文件名**：`<cacheDir>/lyrics/<md5>.v2-14x2.sync.lrc`

`v2` 是规则版本，`14x2` 是「每行 14 半角 × 每屏 2 行」。**任一变化就换名字**，
旧文件自然不再被读到，不需要写迁移代码。

**首行签名**（读取时逐字比对）：

```
#syncglance sync-lyric v2; units=14; pages=2; romaji=hepburn; caps=kana-seg
```

**行格式**：

```
[01:23.456]第一行␟第二行
```

- 每行 = **一屏**，最多两行，用 `␟`（U+241F SYMBOL FOR UNIT SEPARATOR）分隔，
  第二行可以为空，但分隔符一定在。
- **不能用 `\u0001` 这类控制字符**：`Lrc.parse()` 对每行做 `trim()`，
  trim 会吃掉 ≤U+0020 的字符——第二行为空时分隔符会被无声删除，
  之后就分不清「成品」和「原始 LRC」了（踩坑记录 §8.22）。
- 时间戳 = 这一屏**开始显示**的时刻；`planPages` 已把时间按字数比例算好。
- 0.5 秒提前量**不写进文件**：它属于查询时的补偿，改它不用重建缓存。

**生成**（`SyncLyric.build`，跑在 io 线程）：

```
Lrc.parse(原始 lrc)
for 每句:
    roma = Romaji.convert(text)
    if 宽 ≤ 28  → 一屏
    else        → Lrc.planPages(...) 逐屏
    appendPage(时间, 屏文本)  // splitTwo 折成两行 + ␟ 拼接
```

一句都生成不出来 → 返回 null，调用方退回原始歌词。

**读取**（`SyncLyric.read`）四道校验，任一不过都返回 null（触发重建）：

1. 文件不存在
2. 首行签名 ≠ 当前签名
3. `Lrc.parse` 结果为空
4. 没有任何一行含 `␟`

**已知残留**：升级到 v2 后，旧的 `v1-14x2.sync.lrc` 不会被删除（项目还没有缓存清理机制），
每首歌会多留几 KB。要清就 `rm -r cache/lyrics`。

#### 4.5.10 显示时怎么取屏

`StateRenderer.computeLyricLines(lyrics, positionMs)` 是实车与预览的**唯一入口**：

```java
long pos = positionMs + LYRIC_LEAD_MS;              // 0.5 秒提前量
String[] ready = SyncLyric.pageAt(lyrics, pos);     // 成品：直接拆两行
if (ready != null) { out[0] = ready[0]; out[1] = ready[1]; return out; }

// 降级路径（没有成品时现场算）
String[] page = Lrc.paged(lyrics, pos, 14, 2);
return SyncLyric.splitTwo(Romaji.convert(page[0]).trim());
```

成品路径**零算力**：按时间找到当前行，再 `split('\u241F')`。
降级路径只在「第一次播放、成品还没生成」的窗口里出现。

#### 4.5.11 分页与折行算法

**分页**：`Lrc.planPages(text, startMs, endMs, capacity)`（唯一实现，
`Lrc.paged()` 与 `SyncLyric.build()` 共用）

```
宽 ≤ capacity → 单屏，起始 = startMs
否则：
    按 capacity 宽度切片（takeWidth），每片 trim，空片丢弃
    每屏时长 ∝ 该屏宽度：elapsed += span × w_i / Σw
    该屏起始 = startMs + elapsed
```

按字数比例而不是平均分：宽屏字多，理应停久；平均分会让只剩两三个字的末屏
也占掉同样的时间。代价是极窄的一屏可能不足 1 秒（车机最快 1 秒刷一次）。

**折行**：`SyncLyric.splitTwo(text)`，每屏最多两行、**每行 ≤14 半角**：

```
① 对半，但只在空格处断（中点向左 3 个字符内找空格）
② 找不到空格（中点落在长单词里）→ 尽量填满第一行，仍然只在空格处断
③ 整屏一个空格都没有（纯中文 / 一个超长单词）→ 按宽度硬切
```

不变量：两行都 ≤14，且不丢字（单测 `SyncLyricTest` 覆盖）。

**已知局限**：翻页切片本身仍是「每屏 28 宽硬切」，所以长句的**翻页点**仍可能落在
词中间（`KON|PYUUTAA`）；折行已经不会切断单词了。彻底修法见 `todo.md` 的 E 项。

#### 4.5.12 转写引擎（`Romaji`）

- 词边界由 **kuromoji（IPADIC）** 给，包名 `com.atilika.kuromoji`，
  依赖 `com.atilika.kuromoji:kuromoji-ipadic:0.9.0`
- 词典打在 jar 内（原始 31.9 MB，压缩后仍占 APK **12.7 MB**），
  从 classpath 资源加载，**不联网、不需要外部文件**
- **惰性初始化**：第一次遇到含假名的文本才 `new Tokenizer()`（约 1~2 秒），
  发生在 io 线程（成品生成时），不阻塞首屏
- 失败（老设备 OOM、资源缺失）→ 置 `sInitFailed`，本进程内降级
  `Kana.romajiLegacy()`（旧的逐字空格），不再重试；显示链路不中断
- **线程安全**：`Tokenizer` 不是线程安全的，`tokenize` 包在 `synchronized (tk)` 里
- 结果进 **512 条 LRU**：`Show` 每几秒会重算歌名/导航，避免重复分词
- **不含假名就原样返回**：中文、英文、德文不会被分词、也不会被加空格
- 转写规则 R1~R9、平文式依据见 [design.md §2.5.1](design.md)

词间距的判断（`Romaji.needSpace`，按优先级）：

1. 标点紧贴前文；标点后空一格
2. **接续助词**（词性 `助詞` + 二级 `接続助詞`）粘前一个词：
   `いれ` + `ば` → `ireba`；`食べ` + `て` → `食べte`
3. **送假名**：纯假名 token 紧跟汉字、且不是助词/助动词/接续词/感动词/记号 → 不空格：
   `新` + `しい` → `新shii`
4. 其余：词与词之间一个空格

大小写按**词段**整体判定（片假名字符数 > 平假名 → 大写），不逐字符，
避免 `KAr` 这种混排。

#### 4.5.13 失败与降级矩阵

| 失败点 | 处理 | 影响 |
|---|---|---|
| 网易云 HTTP 非 200 | 返回 null，**不算网络错误** | 继续 lrclib |
| 网易云连接异常 | `lastWasNetworkError=true` | 继续 lrclib，**不写**负缓存 |
| lrclib 404 | null，不算网络错误 | 三级全失败 → 写负缓存 |
| lrclib 429 限流 | null，**置网络错误标志** | 不写负缓存（否则一次限流消失 6 小时） |
| 拿到 lrc 但解析为空 | 视为「确实没有」 | 写负缓存 |
| kuromoji 初始化失败 | `catch (Throwable)` 后降级 | 罗马音变逐字空格，仍能显示 |
| 成品生成失败 | `build` 返回 null | 继续用原始歌词，本次不写成品 |
| 成品损坏 / 签名不符 | `read` 返回 null | 重建 |
| 旧请求迟到 | `seq != loadSeq` → 丢弃 | 不会覆盖当前歌曲 |
| 断网且无缓存 | 回调 null | 显示「无歌词信息」，下次切歌重试 |

#### 4.5.14 日志关键字速查（TAG = `Lyric` / `Romaji`）

| 日志 | 含义 |
|---|---|
| `歌词就绪(内存 / 磁盘缓存 / 成品缓存 / 网易云 / lrclib)` | 正常，括号里是命中的那一层 |
| `成品歌词就绪: <歌名> 共 N 屏` | 后台生成完成，下次直接读 |
| `负缓存命中，跳过网络查询` | 6 小时内不再查；清 `cache/lyrics/*.none` 可强制重试 |
| `网络异常导致无歌词，不写负缓存` | 断网，未污染缓存 |
| `无歌词: 歌名 - 歌手` | 所有源都正常返回但确实没找到 |
| `丢弃过期歌词(源)` | 快速切歌，旧结果被序号拦下 |
| `kuromoji 就绪，耗时 Nms` | 分词器首次加载完成 |
| `kuromoji 初始化失败，本进程内降级为逐字转换` | 词典加载失败，罗马音会变成逐字空格 |
| `lrclib 命中(原名 / 剥括号 / 搜索)` | 命中哪一级 |
| `网易云选中 id=... score=...` | 候选打分结果 |
| `lrclib 搜索结果时长偏差 Ns，可能是错误版本` | 模糊搜索挑到的版本存疑 |

### 4.6 防刷屏节流

**`Show` 有 4 秒硬限流：**

```java
private static final long MIN_INTERVAL_MS = 4200;

// 状态变了只标记 dirty，由定时器统一调度
private void markDirty(String why) {
    dirty = true;
    schedule();
}
```

**高德数据推送节流**：`AmapLinkClient` 最多每 2 秒推一次给显示层。

### 4.7 导航数据过期检测

高德闪退后不会发结束信号，数据会卡在最后一条。三层兜底：

| 层 | 触发条件 | 反应时间 |
|---|---|---|
| 1 | 绑定断开 | 立刻 |
| 2 | 收到 type 100/200 | 立刻 |
| 3 | **15 秒没更新** | 15 秒 |

### 4.8 省电优化

**车机没连时跳过解码**（AES-GCM 是 CPU 大头）：

```java
if (!shouldDecode()) {
    callbackCount++;
    if (shouldLogHeartbeat()) {
        Log2.i(TAG, "收到回调 #" + callbackCount + "（未连车机跳过解码）");
    }
    return;    // 但仍要读完 fd 并关闭，否则泄漏
}
```

**保留日志**：能看到 `收到回调 #128` 心跳，确认链路是通的。

**强制解析开关**：测试导航时可以打开，不连车机也解码。

---

## 五、主要依赖

### 5.1 Gradle 配置

```kotlin
// app/build.gradle.kts
android {
    namespace = "com.xigaigai.syncglance"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.xigaigai.syncglance"
        minSdk = 24
        targetSdk = 35        // 4.12.2 时代必须是 32；5.8.0 起 35
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.10.0")

    // ★ 核心依赖：SDL 官方 SDK
    implementation("com.smartdevicelink:sdl_android:4.12.2")
}
```

```properties
# gradle.properties
android.enableJetifier=true    # sdl_android 依赖旧 support 库
android.useAndroidX=true
```

### 5.2 关键权限

```xml
<!-- 蓝牙 -->
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />

<!-- 前台服务 -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />

<!-- 开机自启 -->
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

<!-- 联网查歌词 -->
<uses-permission android:name="android.permission.INTERNET" />

<!-- ★ 高德导航服务（dumpsys 显示 prot=normal，声明即可） -->
<uses-permission android:name="com.autonavi.minimap.permission.NAVIGATION_SERVICE" />

<!-- Android 11+ 需要声明才能 bind 或查询其他应用 -->
<queries>
    <package android:name="com.autonavi.minimap" />
    <intent><action android:name="com.autonavi.minimap.service.NAVIGATION_SERVICE" /></intent>
</queries>
```

**注意：查歌词之外的所有数据都不需要签名级权限。**

### 5.3 界面完整调用链

```
用户操作 → MainActivity → SdlService → StateRenderer → SDL RPC → 车机
                ↓
         LogActivity ← 每秒 ticker → 刷新日志 + 车机预览
```

**主界面按钮 → RPC 映射：**

| 按钮 | 调用的方法 | 发出的 RPC |
|---|---|---|
| 播放网易云 | `NeteaseControl.startPlayback()` | — （系统媒体按键） |
| 暂停/继续 | `NeteaseControl.toggle()` | — （系统媒体按键） |
| 高德地图 | `launchAmapAndConnect()` | `AmapLinkClient.wakeUp()`（已连接时跳过） |
| 回家 / 去公司 | `navigateTo()` | 拉起高德 amapuri |
| 正计时 | `doTimer(0,0,0,"COUNTUP")` | `SetMediaClockTimer` |
| 倒计时 | `doTimer(0,m,s,"COUNTDOWN")`，上限 `59:59` | `SetMediaClockTimer` |
| 暂停计时 | `doTimer(0,0,0,"PAUSE")` | `SetMediaClockTimer`（带 startTime，定格显示） |
| 清零 | `doTimer(0,0,0,"CLEAR")` | `SetMediaClockTimer` |
| 导航模式 | `setManualNavMode()` | — （本地状态） |
| 软键测试 | `setSoftKeyCount(1~6)` | — （改变 Show 里 softButtons 数量） |
| 键位设置 | `KeyMap.bind()` | — （SharedPreferences） |
| 权限检查 | `checkAllPermissions()` | — （系统弹窗） |
| 重置连接 | `resetProxy()` | 重建 proxy |
| 通信日志 | 打开 `LogActivity` | — |

> 「播放网易云」不再直接把网易云界面拉到前台：先只发媒体键，
> 1.5 秒后仍未播放才退回启动界面。见 §8 踩坑记录。

### 5.3 外部服务

| 服务 | 用途 | 费用 | 是否需要 Key |
|---|---|---|---|
| music.163.com | 歌词**主源** | 免费 | 否（非公开接口，无签名） |
| lrclib.net | 歌词**备源** | 免费 | 否 |
| 高德 AmapLinkService | 导航数据 | — | 否（但需高德在运行） |

---

## 六、代码结构

```
app/src/main/java/com/xigaigai/syncglance/
├── activity/
│   ├── MainActivity.java          主界面（四分组按钮）
│   └── LogActivity.java           信息页（日志 + 车机预览）
├── service/
│   ├── SdlService.java            前台服务，连接管理
│   ├── NotificationCatcher.java   通知监听 + 媒体信息
│   ├── CommandRouter.java         菜单/按键路由
│   └── KeyMap.java                按键映射配置
├── display/
│   ├── StateRenderer.java         四状态渲染 + 节流
│   ├── SyncLifecycle.java         HMI 状态机
│   └── ScreenPreviewView.java     车机屏幕预览
├── nav/
│   ├── AmapLinkClient.java        高德 AIDL 通信
│   └── LinkEnvelope.java          AES-GCM 信封解密
├── lyric/
│   ├── LyricManager.java          歌词调度 + 四级缓存 + 请求序号
│   ├── SyncLyric.java             成品歌词（转写+折行+翻页）预生成与读写
│   ├── Romaji.java                假名→罗马音（kuromoji 分词 + 平文式规则）
│   ├── NeteaseLyric.java          网易云主源（搜索 + 拉 lrc）
│   ├── TitleNorm.java             查询用歌名提取（A/B 类括号）
│   └── Lrc.java                   LRC 解析 / 宽度 / 折行
├── media/
│   └── NeteaseControl.java        媒体按键控制
├── model/
│   ├── NowPlaying.java            歌曲状态
│   └── Navigation.java            导航状态
├── receiver/
│   └── BootReceiver.java          蓝牙/开机事件
└── util/
    ├── Log2.java                  日志中枢
    └── Kana.java                  假名罗马音底层表 + 旧逐字实现（降级兜底）
```

---

## 七、菜单与交互

> 界面视觉规范见 **第九章**。

### 7.1 车机菜单结构

```
More...
├─ 音乐 (menuID=901)
│   ├─ 上一首 (1001) / 下一首 (1002) / 播放暂停 (1003)
│   └─ 显示歌词 (1004) / 隐藏歌词 (1005)
├─ 导航 (menuID=902)
│   └─ 开启导航模式 (2001) / 关闭导航模式 (2002)
└─ 计时 (menuID=903)
    └─ 开始正计时 (3001) / 开始倒计时 (3002)
    └─ 暂停 (3003) / 继续 (3004) / 清零 (3005)
```

**父菜单用 `AddSubMenu`，子项用 `AddCommand` 且必须带 `menuParams.parentID`**——实测验证 `parentID` 工作正常。

### 7.2 按键映射

车机支持 15 个按键：

```
OK, SEEKLEFT, SEEKRIGHT, PRESET_0~9, PLAY_PAUSE (×2)
```

**不支持**：TUNEUP、TUNEDOWN（返回 `UNSUPPORTED_RESOURCE`）

**默认映射：**

| 键 | 功能 |
|---|---|
| SEEKLEFT | 上一首 |
| SEEKRIGHT | 下一首 |
| PLAY_PAUSE | 播放/暂停 |

其余按键可在「键位设置」里自定义，存在 `SharedPreferences`。

**按键去抖**：本车 OK 键与 PLAY_PAUSE 是同一个物理键，按一次会收到两条
`OnButtonPress`（`OK` 和 `PLAY_PAUSE` 各一条）。若两者绑了同一功能，
不去抖就会执行两次、切换类功能互相抵消。`CommandRouter` 按 **cmdID**
做了 300ms 去抖：同一 cmdID 在窗口内重复触发直接丢弃。

### 7.3 软键

**已实车验证有效。** 按压回调走单独通道：

```
Show.softButtons  ← 下发
OnButtonPress { buttonName: CUSTOM_BUTTON, customButtonID: 9001 }  ← 回调
```

不走 `OnCommand`，也不在物理键表里，`CommandRouter.handleSoftButton()` 单独处理。

底栏共 4 格：

| 位置 | 内容 |
|---|---|
| 第 1 格 | 固定播放/暂停（`▶` / `⏸`），ID 9001 |
| 第 2、3 格 | 空闲（可用软键测试按钮占位探测） |
| 第 4 格 | 「更多…」，车机自带，点进去是 7.1 的菜单页 |

主界面「软键测试」按钮会把软键数量在 1→2→3→4→5→6 之间循环，
用于探测车机实际能画几个、画满时「更多…」是否被挤掉。

> ⚠️ `softButtonCapabilities` 只声明 1 项，能画几个尚未在车上验证。

---

## 八、踩过的坑

### 8.1 车机发现不了 App

**原因**：缺 `MultiplexTransportConfig`。

```java
builder.setTransportType(new MultiplexTransportConfig(ctx, APP_ID));
```

### 8.2 装完就"闪退"

**现象**：启动时弹"选择要使用的应用程序"（两个相同图标）。

**原因**：应用被自动克隆到 user 900。

**解决**：`adb shell pm uninstall --user 900 <包名>`

### 8.3 `Show` 限流是 1 秒

旧文档写的 4.2 秒是过时限制，实车按 1 秒节流即可（`StateRenderer.MIN_INTERVAL_MS = 1000`）。

### 8.4 `mediaClock` 参数无效

`Show.mediaClock` 传了没用——它被系统时钟占用。

**真正能显示时间的是 `SetMediaClockTimer` RPC**，输出在**第 3 行右侧**。

### 8.4.1 媒体时钟格只有 `mm:ss`，没有小时位

这是踩了很多次才定下来的硬约束，所有 `StartTime` 都必须遵守：

| 实测输入 | 结果 |
|---|---|
| `hours=0, minutes≤59` | SUCCESS |
| `hours=16, minutes=21` | **REJECTED** |
| `minutes=99` | **INVALID_DATA** |
| `COUNTUP` / `CLEAR` | SUCCESS |

照片实测那一格渲染成 `1:55`（分:秒），确认没有小时位。

**两个直接后果：**

1. **ETA 要借用 mm:ss 位**：到达时刻 `16:21` 不能发 `StartTime(16,21,0)`，
   要发 `StartTime(0,16,21)`，车机才会原样渲染成 `16:21`。
2. **倒计时上限 59:59**：分钟滚轮只到 59，因为 60 分以上无处安放。

`SdlService.clockTime(h,m,s)` 是所有 `StartTime` 的唯一出口：
统一把 hours 压成 0，超过 3599 秒的夹到 59:59。宁可停格，也不要被车机整条拒掉。

### 8.5 `mediaTrack` 两屏共用

只有一个值，不能给两屏设不同的。这是硬限制。

### 8.6 高德广播协议手机版不发

`AUTONAVI_STANDARD_BROADCAST_SEND` 是**车机版**的接口。手机版即使收到握手也不发。

**必须走 AmapLink（AIDL）这条路。**

### 8.7 高德进程不在时 bindService 返回 false

`AmapLinkService` 必须先让高德运行起来才能绑上。前几次失败是正常的，需要重试。

### 8.8 断连后车机搜不到 App

**根因**：`ACL_CONNECTED` 时 `SdlService` 里 `if (proxy == null)` 短路，因为 proxy 对象还在（只是失连）。

**修法**：蓝牙重连时带 `EXTRA_FORCE_RESET` 标记，强制 `resetProxy()`。

### 8.9 PowerShell 修改文件会加 BOM

会导致 Java 编译报错 `非法字符: '\ufeff'`。

**修法**：
```powershell
[System.IO.File]::WriteAllText($f, $c, (New-Object System.Text.UTF8Encoding $false))
```

### 8.10 导航结束信号被节流丢弃（重要）

**现象**：导航结束后，车机界面卡在最后一条导航指令，必须等下一次音乐通知才刷新。

**根因**：`AmapLinkClient.dispatch()` 有 2 秒节流：

```java
if (now - lastDispatchAt < 2000) return;   // 直接丢弃
```

导航刚结束时，如果距上次推送不到 2 秒，**「清空」信号被直接扔掉**。

**修法**：关键事件强制推送。

```java
case 100:
case 200:
    nav.clear();
    criticalEvent = true;    // 标记
    return true;

// 推送时
if (changed) {
    dispatch(criticalEvent);   // 关键事件绕过节流
    criticalEvent = false;
}
```

### 8.11 超时降级太慢

**原配置**：`STALE_MS = 15s` + 每 5 秒检查 → 最坏 **20 秒**才降级。

**改为**：`STALE_MS = 8s` + 每 2 秒检查 → 最坏 **10 秒**。

实测：杀掉高德后 **7.2 秒**完成降级。

### 8.12 预览轮询导致歌词永远加载不到（重要）

**现象**：预览里歌词一直不出来，第 2 屏显示「无歌词信息」。

**根因**：`LogActivity.refreshPreview()` 每秒调：

```java
NowPlaying latest = nc.pollNowPlaying();
if (latest != null) r.setSong(latest);   // 直接替换整个对象
```

`pollNowPlaying()` 返回新的 `NowPlaying` 对象，直接 `setSong()` **绕过了 `SdlService.onNowPlaying()`**，所以：

- ✅ 播放位置更新了（这是想要的）
- ❌ 歌词加载逻辑根本没被触发

**修法**：只更新位置字段，不动歌曲身份。

```java
// StateRenderer 新增
public void updatePlaybackPosition(long positionMs, boolean playing) {
    if (song == null) return;
    song.positionMs = positionMs;
    song.playing = playing;
    markDirty("position");
}

// LogActivity 改用
r.updatePlaybackPosition(latest.positionMs, latest.playing);
```

**教训**：**任何"更新歌曲状态"的入口都必须经过 `onNowPlaying`**，否则会绕过副作用（歌词加载）。

### 8.13 中文混排的字符画对不齐

**问题**：用 `+---+---+` 画边框时，中文占 2 格、英文占 1 格，但等宽字体下渲染宽度不精确，右边界永远参差。

**尝试过但失败的方案**：
- 按「中文=2、英文=1」计算填充 → 字体渲染不精确
- 用 `\t` 制表符 → 不可控

**最终方案**：**放弃字符画，改用真实 Android 布局。**

用 `FrameLayout` + 固定宽高的蓝色画布，每个文字元素用 `TextView` 绝对定位。这样：

- 不受字符宽度影响
- 可以精确控制字体、字号、颜色
- 沉浸感更好

### 8.14 预览依赖 `doShow()` 的副作用（重要）

**两次踩同一个坑。**

**第一次**：`softKey1`（软键文字）只在 `doShow()` 里赋值。
`doShow()` 需要 proxy 存在且 HMI 是 FULL 才执行，没连车机时它不跑，
字段一直是 `null`，预览里那一格就显示成 `—`。

**第二次**：`trackKind`（track 装的是歌曲还是导航）同样只在 `doShow()` 里赋值。
没连车机时一直是初始值 `NONE`，于是 `statusPrefix()` 返回空串，
**预览里 track 行完全没有状态指示符**。

**根因**：预览读的状态是「上次真正发出去时的快照」，而不是「如果现在发会是什么」。

**规则**：凡是预览要用的数据，必须能**独立于发送流程实时计算**。

```java
// ✅ 正确：实时算
public String getSoftKey1() { return playingNow() ? "⏸" : "▶"; }

public TrackKind getTrackKind() {
    switch (currentState()) { ... }     // 按当前状态推导
}

// ❌ 错误：读 doShow 的副作用
private String softKey1;                // 只在 doShow 里赋值
private TrackKind trackKind;            // 同上
```

### 8.15 纯 ASCII 歌词永远不换行（重要）

**症状**：德语/英语/罗马音歌词只显示一行，第二行永远空着。

**根因**：`splitAt()` 原本按「第 n 个**汉字**」找切点：

```java
for (int i = 0; i < s.length(); i++) {
    if (isWideChar(s.charAt(i))) {
        count++;
        if (count > n) { target = i; break; }
    }
}
if (target < 0) return s;      // ← 纯 ASCII 文本永远走这里
```

纯 ASCII 文本一个汉字都没有，`count` 从不增加，`target` 保持 `-1`，
直接返回整串。调用方拿到 `out[0] = 整串`、`out[1] = substring(整串长度)` = 空串。

中文歌词不会暴露这个问题，因为汉字能被正确计数。

**修复**：宽度统一改成**半角单位**（汉字 2、ASCII 1），
切点按「半角宽度 n」定位，一行容量 14 半角。

```java
// Lrc.widthOf()  —— 汉字 2、ASCII 1
// Lrc.paged()    —— 按半角宽度切段
// StateRenderer.splitAt() —— 按半角宽度找切点，优先空格处断开
```

**顺带修掉**：`displayWidth()` 原来把汉字和 ASCII 都算 1，
导致 17 个德文字母被当成 17 个汉字宽（实际一行只能放 7 个汉字 = 14 个字母）。

### 8.16 OK 键与 PLAY_PAUSE 是同一个物理键

本车按一次 OK，会收到**两条**通知：

```
OnButtonPress { OK, SHORT }
OnButtonPress { PLAY_PAUSE, SHORT }
```

现在不出问题，因为 OK 默认未绑定。但**若在「键位设置」里把 OK 也绑成播放/暂停**，
一次按键就触发两次切换：播放 → 暂停 → 再播放，**等于没按**。

**修复**：`CommandRouter` 按 **cmdID** 做 300ms 去抖：同一 cmdID 在窗口内
重复触发直接丢弃。按 cmdID 而不是按键名，这样 OK 和 PLAY_PAUSE 绑同一功能时
能正确合并，绑不同功能时两条都会执行（这是对的，是两个不同动作）。

### 8.17 网易云搜歌词不能用截断后的歌名

**症状**：搜唐朝乐队的《天堂》，拿到了另一个歌手《天堂》的歌词。

**根因**：为了处理「歌名（中文名）」这类括号，曾把括号剥掉再搜。
但括号里往往正是区分度最高的信息（译名/出处/版本），剥掉等于自废武功。
剥完的 `天堂` 和无数同名歌撞车，时长又接近，打分选了错的。

**修复**：

- 缓存身份用**原始完整标题**（不做任何剥离）
- 网易云搜索也用**完整标题**
- 剥括号只在 lrclib 精确匹配那一级使用（那边是精确匹配，剥了才有意义）
- 打分加严：整串完全一致 +140，歌手对不上 −50，完全不沾边 −60

### 8.18 车机字库不认任何指示符（重要）

**症状**：`⧗` / `▶` / `⏸` / 八方向箭头，在车机上全部渲染成一个「·」。

**结论**：SYNC 1.1 / MFD4 的点阵字库极小，装饰性符号基本没有。

**处理**：所有指示符集中到 `Glyphs` 类，用 `ENABLED` 一个开关整体控制。
关掉时退回汉字兜底：

| 位置 | 开着 | 关掉 |
|---|---|---|
| mediaTrack 前缀（计时器） | `⧗` | 空 |
| mediaTrack 前缀（歌曲） | `▶` / `⏸` | 空 |
| mediaTrack 前缀（导航） | `↖` 等箭头 | 空 |
| 软键第 1 格 | `▶` / `⏸` | **`播放` / `暂停`** |
| 空软键占位 | `—` | 空格 |

软键第 1 格必须留文字，否则按钮变空白，人都不知道该点哪。

**下一步**：用主界面的「字符测试」按钮逐组探测，找出车机真正有字形的字符，
再决定 `Glyphs.ENABLED` 要不要打开、具体保留哪些。

> ⚠ 2026-11-22 备注：**「字符测试」按钮和 `CharProbe` 已在 `130d078` 移除**
> （调试功能清理）。下面的结论是当时实测得到的，仍然有效；
> 若哪天还要复测，得先把 `CharProbe` 加回来。

#### 8.18.1 关于「另一套编码」——日语字符的几种可能

Unicode 把所有文字统一到一个码位空间，但**车机字库是按某套旧编码的子集做的**。
所以「同一个字」在 Unicode 里可能有多个码位，落在不同编码区，字形有无也不一样。
能试的方向：

| 方向 | 码位区间 | 说明 |
|---|---|---|
| **平假名 / 片假名** | U+3040–30FF | 来自 JIS X 0208。**GB2312 第 4、5 区就有这些字**，若车机字库按 GB2312 建，理论上应该有字形 —— 这是最可能意外成功的一组 |
| **半角片假名** | U+FF61–FF9F | 来自 JIS X 0201，**GB2312 里没有**，GBK 才补。若车机只做到 GB2312，这组会全灭而全角假名正常，能反推字库范围 |
| **全角 ASCII** | U+FF01–FF5E | 同样来自 JIS X 0208，与半角 ASCII 是两套独立码位 |
| **CJK 兼容表意文字** | U+F900–FAFF | 是「同一个汉字的另一个码位」。老字库按 Big5 / KS X 1001 建表时，某些汉字只存在于兼容区 |
| **日文汉字** | U+4E00+ | 与中文共享码位。只要中文能显示，日文汉字通常也能 —— 不需要另找编码 |
| **罗马字** | ASCII | 最终兜底。`TitleNorm` 已对日文歌名做罗马音转换 |

`CharProbe` 里第 4~9 组就是照这个思路排的：先试全角假名，再试半角假名，
再试兼容表意文字，最后用统一的 CJK 汉字对照。

### 8.19 车机卡在「连接中」且再也连不上（重要）

**症状**：上车后车机主动连 SyncPlay，此时手机 App 没起来 → 车机停在「连接中」。
之后再把 App 打开也连不上，只能到车机上删掉应用重新添加。

**根因**（来自 SDL 官方文档 *Establishing a Transport*）：

车机只有在蓝牙 A2DP + HFP 连上后的**一个很短窗口**内才会去连 SDL 的 RFCOMM 通道：

| 场景 | RouterService 前台保持 |
|---|---|
| 从没见过这台车机 | 约 10 秒 |
| 见过但从未连通 | 立即退后台 |
| 成功连过 | 约 20 秒 |

窗口内没连上，RouterService 退到后台等系统回收，**而车机不会再回头连**。
手机侧 proxy 却一直在等车机发起连接 —— 双方互相等，谁都不动。

**修法（按官方链路重做，不是自己造轮子）**：

1. **`SdlRouterService`** —— 继承库的 `SdlRouterService`（类名必须与基类同名，见 §8.21），
   Manifest 里声明
   action `com.smartdevicelink.router.service`。
   它才是负责监听 RFCOMM、注册 SDP 记录、让车机「发现本应用」的那一层。
   没有它，车机端根本不知道手机上有 SDL 应用。
2. **`SdlReceiver`** —— 继承库的 `SdlBroadcastReceiver`，实现
   `defineLocalSdlRouterClass()` 和 `onSdlEnabled()`。
   官方 receiver 监听三个事件，我们自写的 BootReceiver 只监听了第一个：

   | 事件 | 作用 |
   |---|---|
   | `ACL_CONNECTED` | 唤起 RouterService 开始监听 |
   | `sdl.router.startservice` | **RouterService 反过来唤醒应用进程** ← 这条之前完全没有 |
   | `USB_ACCESSORY_ATTACHED` | USB 传输（本车用不到） |

3. **`queryForConnectedService()`** —— 官方给的「应用后启动」补连接手段。
   现在**只挂在 `SyncGlanceApp.onCreate`**（每个进程一次，失败 2 秒后重试）和
   `MainActivity.onCreate`。
   上车时应用不在 → 车机放弃 → 用户后来才打开应用，这一问能让 RouterService
   重新挂起来。官方也注明：**车机端还得重新做一次 SDP 查询**才感知得到，这一步不受应用控制。

   > ⚠ **不要放回 `BootReceiver` 里**（曾经放过）。广播的 context 是
   > `ReceiverRestrictedContext`，库会拿它 `bindService` → `ReceiverCallNotAllowedException`
   > → 进程闪退（2026-11-21 实车崩过两次）。只有 Application / Activity 的 context 才安全。

4. **`BootReceiver` 只记日志，既不启动服务也不做探测**（2026-11-21 修正，提交 `fcbef0a`；
   2026-11-22 起连「探测」的措辞也一并纠正）。
   以前这里是 `startForegroundService(SdlService)`，注释写的是「只保证进程活着」。
   那是 4.x 的语义：`SdlProxyALM` 会自己等 Router。
   **在 5.x 里这行代码的实际效果是立刻建一条 `SdlManager` 连接** —— 而那一刻
   Router 还没被库叫起来（库走异步约 110ms，我们只要 5~20ms），于是 5~11ms 自毁，
   车机侧看到「app 在但注册不完成」，卡在「正在连接」**2 分钟**才超时。

   实车四轮验证（2026-11-21 15:13~15:17）：从 `ACL_CONNECTED` / `BOOT_COMPLETED`
   启动 `SdlService` **全部失败**，只有 `SdlReceiver.onSdlEnabled`（车机已连通 Router）
   那条成功。详见 [连接可靠性排查.md](连接可靠性排查.md)。

5. **两个 receiver 的分工（2026-11-22 澄清，反编译确认）**

   | receiver | 声明 `ACL_CONNECTED` | 收到后做什么 |
   |---|---|---|
   | `SdlReceiver`（继承库的 `SdlBroadcastReceiver`） | ✅ | **由库代码拉起 Router**（`wakeUpRouterService()` → `startForegroundService(RouterService)`）；车机连通后再回调 `onSdlEnabled` → 拉起 `SdlService` |
   | `BootReceiver`（4.x 时代自写） | ✅ | **一个 start 都不调**：只记日志（唤醒来源 + 冷/热启动）+ 维护 MAC 白名单 |

   **Router 自己不听广播** —— 它是 Service 不是 receiver。整个库里唯一匹配
   `ACL_CONNECTED` 的是 `SdlBroadcastReceiver`。

   所以 `BootReceiver` 那条监听对「拉起」是**冗余**的，删掉不影响连接；
   留着是因为它记录了「谁把进程叫醒」（`SdlReceiver` 要等车机连通 Router 后才打日志），
   而且零成本。日志措辞 2026-11-22 起从「【启动来源】」改为按冷热区分：

   ```
   [Boot] 【进程被拉起】蓝牙 ACL_CONNECTED ← SYNC / <MAC>      ← Application.onCreate 后 1.5s 内
   [Boot] 【进程已运行】收到 蓝牙 ACL_CONNECTED ← SYNC / <MAC>  ← 进程早就活着
   [Boot] 【事件】蓝牙已开启                                    ← STATE_CHANGED
   ```

   > 曾经写过一版"20 秒没连上就 resetProxy 重试两次"的看门狗，已废弃 ——
   > 那是绕开官方机制硬拉，治标不治本。

### 8.20 车机上的应用名

**实测**（2026-11-20 照片）：

| 位置 | 实际用的字段 |
|---|---|
| 顶部标题栏 | `appName` |
| 底部「离开 X」按钮 | `shortAppName` |

`setShortAppName("SyncPlay")` **只影响对话框/按钮这类窄处**，改它不会让标题栏变。
标题栏显示 `西改改的Sync` 时会被截断成 `西改改的...`（约 5 汉字宽）。

**定案**：车机上全部叫 `SyncPlay`，即 `APP_NAME = SHORT_APP_NAME = "SyncPlay"`。
手机桌面图标名由 `AndroidManifest.xml` 的 `android:label` 控制，仍是「西改改的Sync」，不受影响。

### 8.21 应用分身抢启动（本机特有，非代码问题）

**症状**：点图标弹「选择要使用的应用程序」，两个一模一样的图标；
或者主界面看着正常但 `SdlService` 根本没起来。

**根因**：这台 Motorola 会自动把 adb 安装的应用复制到「应用分身」用户（user 900）。
于是系统里有两份同名同包，`am start` 可能落到 `ResolverActivity`。

**修法**：`install` **之后**再 `pm uninstall --user 900 <pkg>`。
顺序反了没用 —— 安装的瞬间系统又复制一份。
每次重装都要再清一次，直到 `pm list packages --user 900` 查不到。

详见 `DEVICE-CONNECTION.md`。

---

### 8.22 成品歌词的分隔符不能用控制字符

**现象**：`SyncLyric` 最初用 `\u0001` 做「一屏两行」的分隔符，单测直接失败
（`每屏必须是两行 expected:<2> but was:<1>`）。

**原因**：`Lrc.parse()` 对每行文本做了 `trim()`，而 `String.trim()`
会删掉所有 **≤ U+0020** 的字符 —— 第二行为空时，行尾的 `\u0001` 被无声吃掉，
「成品」与「原始 LRC」再也分不出来。

**解决**：分隔符改成 **U+241F（␟ SYMBOL FOR UNIT SEPARATOR）**：
码位远高于空白、不会被 trim、肉眼可辨、不会出现在歌词里。

> 教训：凡是「写进文本文件再解析回来」的标记，都要考虑解析端会不会 `trim()`。

### 8.23 两个 kuromoji jar 带的许可文件重名，AGP 8 直接构建失败

**现象**：

```
2 files found with path 'META-INF/CONTRIBUTORS.md' from inputs:
  kuromoji-ipadic-0.9.0.jar
  kuromoji-core-0.9.0.jar
```

**原因**：`kuromoji-ipadic` 传递依赖 `kuromoji-core`，两个 jar 各带一份
`META-INF/CONTRIBUTORS.md` 和 `META-INF/LICENSE.md`。AGP 8 遇到重名资源
不再「后者覆盖」，而是直接失败。

**解决**：`app/build.gradle.kts` 里加 `packaging.resources.pickFirsts`，
只保留第一份。两个文件**哈希完全一致**，取哪份都一样；
许可文件仍留在 APK 里，不破坏 Apache-2.0 的声明要求
（`NOTICE.md` 只有 ipadic 带，不冲突，会自动保留）。

### 8.24 串烧歌词取成了其中一首单曲

**现象**：放《艳舞台+烈焰红唇(Live)》（311 秒串烧），车机上显示的是 227 秒录音室版
《烈焰红唇》的歌词。日志：

```
[Lyric] 网易云选中 id=120869 《烈焰红唇》 梅艳芳 score=60
```

**根因**（三条，都在电脑上复现验证过）：

1. **串烧名被当成"标题包含"而加分**：`wantMain.contains(cMain)` 给 +40，
   它分不清「同一首歌的另一种写法」和「串烧里的一个片段」。
2. **时长差 84 秒只扣 40 分**：`40 + 60 − 40 = 60`，还是正分。
3. **采纳门槛是 `score > 0`**：没有及格线。而网易云搜索结果里**根本没有这首串烧**
   （单独搜「艳舞台」也搜不到），lrclib 也无结果，没有第二道校验能纠正。

**解决**（三管齐下，详见 `docs/design.md` §3.2.4 / §3.2.5）：

- 用 `METADATA_KEY_MEDIA_ID` 直取 —— 网易云给的 mediaId 就是 song id，反查校验通过后 100% 命中
- 串烧必须整串覆盖，只覆盖一段直接淘汰
- 改成档位 + 硬门槛（时长、歌手），不再算加权总分
- 新增 `NeteaseLyricTest` 锁住 4 组串烧判定用例

### 8.25 车机会自己从第 2 屏跳回第 1 屏

**现象**：把歌词放在第 2 屏（`mainField3/4`）后，歌词显示几秒就消失，
画面回到第 1 屏的歌名/歌手。

**原因**：不是我们发的 `Show`（`Show` 是覆盖式写入），是**车机自己的行为** ——
停在第 2 屏一段时间后自动跳回第 1 屏。

**解决**：把"需要持续看"的内容放第 1 屏。仅歌曲模式：
有歌词时第 1 屏给歌词、第 2 屏给歌名/歌手；没歌词时反过来（没有要一直看的东西）。
导航+歌曲模式保持导航在第 1 屏（开车时导航比歌词更不能丢）。

### 8.26 RouterService 不启动是正常的，不要为了状态显示去拉起它

**背景**：通知栏要显示 Router 状态（正文里的 `Router listening` 等），需要知道 Router 活不活。

> 2026-11-22 通知曾短暂移除、当晚恢复。`routerAlive` 现在有三处用途：
> 通知正文（`Router listening` / `Registering` / `Waiting for Router`）、
> 日志，以及**秒失败判定**（`RouterAlive=false` 基本等于「Router 还没就绪」）。
> 见 `docs/连接可靠性排查.md` §11.3。

**踩过的误区**：以为 Router 会一直常驻、或者需要主动拉起它。
实测（手动 `am startservice` 后观察）：

```
* ServiceRecord{... .receiver.SyncRouterService
    createTime=-6m19s812ms  startRequested=true}
进程：com.smartdevicelink.router  还在跑
```

无车机连接时 Router **并没有主动关闭**，只是退到后台等系统回收（至少活 6 分钟）。

**真相**：车机是主动方，手机端从不发起连接。

```
① 蓝牙音频连接建立（A2DP/HFP，跟 SDL 无关）
② Android 发 ACL_CONNECTED → ③ 拉起 RouterService
④ Router 注册 SDP + 监听 RFCOMM → ⑤ 车机 SDP 发现 + RFCOMM 连接
⑥ 多路复用给 SdlService → SdlManager → RAI
```

所以 Router **不需要常驻**，只需要"在车机要连的那一刻活着"。
Router 没跑 ≈ 车机蓝牙没连上，通知里就该如实说「蓝牙未连接」（不是"等待车机"）。

**结论**：
- **不主动拉起 Router**，只被动监听它上下线（靠 `SdlRouterService` 自己发应用内广播）
- **不做应用层定时重试**（4.12.2 时代写过「每 5 秒 ×12 次」，会让车机侧反复收到
  同一个 appName 的注册请求、卡在「恢复连接」）
- SDL 5.8 没有 `isRouterServiceRunning()` 这类 API，只能自己上报

### 8.27 高德被关闭后重开，导航数据不再推送

**现象**：导航中途把高德杀掉或划掉，之后重新打开高德并开始导航，
SyncPlay 再也收不到导航数据，必须重启 App。

**根因链**（三条叠加）：

1. `onServiceDisconnected` → `retry()` → `retire()`（unbind）+ 延迟 rebind。
2. `retry()` 在 `!shouldDecode()`（既没连车机、也没开预览）时**直接 return** ——
   既不排下一次 bind，也不留任何标记，等于**静默死掉**。
3. 重试预算 `attempts` 只在 `start()` / `wakeUp()` 里重置。
   高德"重新打开"时**不一定会发通知**，`NotificationCatcher` 那条唤醒链路不可靠 ——
   于是预算耗尽后没有任何路径再重置它。

**为什么通知靠不住**：高德不是每次启动都发通知（只开地图、不进导航时很可能没有）。
用户实测遇到过"重开了但没推送"。

**参考实现的做法**（本项目的 AIDL 接口来自 [fw867/hypercar]
(https://github.com/fw867/hypercar)，看 `AmapLinkProbe.kt` + `LinkBindingState.kt`）：

```kotlin
fun begin(): Long? {
    if (closed || attempts >= 8) return null    // 8 次上限
    ...
}
val retryDelayMillis: Long get() = (2000L * attempts.coerceAtLeast(1)).coerceAtMost(15_000)
fun mapStarted() { if (!closed) attempts = 0 }   // ★ 用户拉起地图 → 重置预算
fun registered(token: Long) { ...; attempts = 0 }
```

它**不解绑保活**，也是 `retire()`（unbind）后递增退避重绑；
关键是 `start(context, mapStarted = true)` —— **应用自己拉起地图时显式重置预算**。

**本项目对照改法**：

| 项 | 之前 | 现在 |
|---|---|---|
| 重试上限 | 10 次 | **8 次**（对齐 hypercar） |
| 退避 | 固定 2s / 10s | **2s → 4s → … 封顶 15s**（合计约 72 秒） |
| 预算重置入口 | `wakeUp()`（靠通知） | 再加 **`wakeUpFromMapLaunch()`**（用户点高德按钮）+ **`onResume` 补一次** |
| 省电暂停 | 直接 return，静默死掉 | 标记 `pausedForPower`，**条件恢复时自动 `wakeUp()`** |
| 高德按钮 | 已连接也排重连 | **已连接只跳转；未连接才重置预算并重绑** |

> ⚠ 这里**没有引入任何轮询**。全部是事件驱动：
> 用户点按钮、`onResume` 一次、车机连上、预览打开。
> 这也是选择这条路而不是"定时探测高德进程"的原因（功耗）。

## 九、界面规范

### 9.1 主界面布局

```
状态
  连接：已连接(FULL) / 已建立，等待车机选中
  媒体：年度之歌 / 谢安琪  [播放]
  导航：已连接（导航中）/ 尝试重连中 (6/50) / 高德地图未启动

音乐
  [播放网易云]  [暂停/继续]

导航
  [高德地图]  [回家]  [去公司]

计时器
  [正计时]  [倒计时]
  [暂停计时]  [清零]

其他
  [软键测试]  [导航模式]  [键位设置]
  [权限检查]  [重置连接]  [通信日志]
```

### 9.2 信息页（LogActivity）布局

```
┌─────────────────────────────────┐
│ 信息                            │
├─────────────────────────────────┤
│ [复制] [分享] [JSON:关] [清空]  │
│ [暂停刷新] [清空歌词缓存]       │
├─────────────────────────────────┤
│                                 │
│  通信日志（可滚动）              │
│  带毫秒时间戳 + 原始 JSON       │
│                                 │
├─────────────────────────────────┤
│ 模式：导航 + 歌曲                │
│ ┌──────────────────┐            │
│ │ SyncPlay (未连接) 21:39        │   ← 第 1 屏
│ │  ▲  右转 145 m                  │
│ │ SYNC 动力二街                   │
│ │  ▼  →23.0 km        22:13      │
│ │  ▶  —  —  更多…                │
│ └──────────────────┘            │
│ ┌──────────────────┐            │
│ │ SyncPlay (未连接) 21:39        │   ← 第 2 屏
│ │  ▲  歌词上句                    │
│ │ SYNC 歌词下句                   │
│ │  ▼  →23.0 km        22:13      │
│ │  ▶  —  —  更多…                │
│ └──────────────────┘            │
└─────────────────────────────────┘
```

**预览特点：**
- 两块独立画布（不切换，同时可见）
- 蓝色 SYNC 主题（`#1B3A5C`）
- 宽 240dp 居中，模拟 4 寸屏比例
- 左侧 `▲ SYNC ▼` 滚动条列（60dp 宽）
- 文字按**半角宽度 14**（= 7 汉字）截断，超出加 `...`
- **底栏 4 格按真实软键规则绘制**：第 1 格是我们发的播放/暂停，
  第 2、3 格空着显示 `—`，第 4 格固定「更多…」（车机自带）
- 歌词、指示符、软键都与实车共用同一套计算函数，不重复实现

> **重要**：所有预览数据都必须能<b>实时计算</b>，不能依赖 `doShow()` 的副作用。
> 历史 bug：`softKey1` 和 `trackKind` 都只在 `doShow()` 里赋值，
> 没连车机时预览就全空了。见 §8.14。

### 9.3 字体

| 用途 | 字体 | 说明 |
|---|---|---|
| 标题栏 | `sans-serif-medium` | 青色 `#4DD0E1` |
| 正文 | `sans-serif` | 白色，15sp |
| 计时器 | `monospace` | 青色，等宽保证不跳动 |
| 软键 | `sans-serif` | 白色/灰色，9sp |

**关于 Tahoma**：Sync 在 Windows 上用的字体，Android 系统没有。`sans-serif`（思源黑体/Noto Sans CJK）是最近的等价物。

### 9.4 配色

| 元素 | 颜色 |
|---|---|
| 画布背景 | `#1B3A5C`（深蓝） |
| 标题栏文字 | `#4DD0E1`（青色） |
| 正文 | `#FFFFFF` |
| 计时器 | `#4DD0E1` |
| 分隔线 | `#3D6A96` |
| 滚动条箭头 | `#7FB3D5` |
| 禁用软键 | `#90A4AE` |

### 9.5 屏宽实测（实车照片测量，2026-11）

> 依据：一张实车 MFD4 屏照片（`P_20260812_211327.jpg`），屏上显示
> `这是第一行文字` / `这是第二行文字` / `这是轨…` / `更多…`

**结论：正文一行 = 7 个汉字。**

| 实测项 | 结果 |
|---|---|
| 正文行能放的汉字数 | **7 个**（`这是第一行文字` 正好 7 字占满） |
| 正文行放 8 字时 | 截断成 6 字 + `…`（`这是轨…` = 3 字 + 省略号，说明余量不足 2 字时更早截） |
| 居中区可用宽度 | 约等于 7 个全角汉字 |
| 左右两侧 | 左侧有 SYNC 图标条（约 1 个汉字宽），右侧有滚动条（约半个汉字宽） |
| 标题栏 | `西改改的…` → 放得下 4 字 + 省略号，容量比正文小 |
| 底栏软键 | `更多…` 占一格，说明**软键槽位是固定几格均分**，不是按内容自适应 |

**对代码的直接影响：**

- `StateRenderer.LINE_CHARS` / `ScreenPreviewView.LINE_CHARS` 取 **7** 是对的
- 罗马音一旦展开成 `shi a wa se na`（11 字符），**必然溢出**，会被截成 `...`
- 底栏那几个「音乐 / 导航 / 计时 / 更多」是**车机自己的固定菜单**，
  不是我们 `Show.softButtons` 画出来的 —— 我们的软键是另一格

**软键实测（同批验证）：**

```
18:45:08.428 OnButtonPress {buttonName: CUSTOM_BUTTON, customButtonID: 9001}
```

- `Show.softButtons` 在 SYNC 1.1 / MFD4 上**有效**，按压回调正常
- 之前文档标的「⚠️ 尚未实测」可以划掉了
- 回调走 `OnButtonPress{CUSTOM_BUTTON, customButtonID}`，不进 `OnCommand`

---

## 十、实测数据

### 9.1 车机能力清单

```json
{
  "displayType": "MFD4",
  "sdlVersion": "2.1",
  "systemSoftwareVersion": "5.11",
  "syncMsgVersion": "2.0.0",
  "vehicleType": { "make": "Ford", "modelYear": "2015", "model": "C346A FOCUS CHINA" },
  "language": "ZH-CN",
  "hmiDisplayLanguage": "ZH-CN",
  "graphicSupported": false,
  "mediaClockFormats": ["CLOCK3", "CLOCKTEXT4"],
  "textFields": "16 个，width 全是 40（scrollableMessageBody 是 200）",
  "buttonCapabilities": "15 个（OK/SEEKLEFT/SEEKRIGHT/PRESET_0~9/PLAY_PAUSE×2）",
  "softButtonCapabilities": "1 个（只支持短按，imageSupported=true）",
  "presetBankCapabilities": { "onScreenPresetsAvailable": false },
  "speechCapabilities": ["TEXT", "PRE_RECORDED", "SILENCE", "LHPLUS_PHONEMES"],
  "hmiZoneCapabilities": ["FRONT"]
}
```

> ⚠️ 早期文档记的 `language: EN-GB` 是错的，实测是 **ZH-CN**。
> 因为车机语言是中文，`SetGlobalProperties` 的 helpPrompt 之类也应该用中文。

### 9.2 策略表放行情况

| RPC | 允许的 HMI 等级 |
|---|---|
| Show / AddCommand / SubscribeButton | BACKGROUND, LIMITED, FULL |
| SetMediaClockTimer | BACKGROUND, LIMITED, FULL |
| **ScrollableMessage / Slider** | **仅 FULL** |
| Speak / Alert / PerformInteraction | LIMITED, FULL |
| **GetVehicleData / ReadDID / GetDTCs** | **不在白名单（封锁）** |

### 9.3 验证过的功能

| 功能 | 结果 |
|---|---|
| 车机发现 App | ✅ |
| 双屏四行显示 | ✅ |
| 三个父菜单 + 12 子项 | ✅ |
| 15 个按键订阅（TUNEUP/TUNEDOWN 不支持） | ✅ |
| 计时器正/倒计时 | ✅ |
| 高德导航数据（转向/距离/路名/ETA） | ✅ |
| 歌词显示 | ✅ |
| 假名转罗马音 | ✅ |
| **`Show.softButtons` 软键 + 回调** | ✅ 实车验证（`CUSTOM_BUTTON` id=9001） |
| **`OnLockScreenStatus` 通知** | ✅ 收到（`showLockScreen: OFF/OPTIONAL/REQUIRED`） |
| **`OnDriverDistraction` 通知** | ✅ 收到（`DD_ON` / `DD_OFF`） |
| 车辆数据 | ❌ 策略封锁 |
| 诊断接口 | ❌ 策略封锁 |

---

## 十一、参考资料

### 10.1 官方文档

- [AppLink Android Developers Guide 1.6.0](file:///D:/DSH/syncdev/Ford-AppLink-master/) —— 本地存档
- [AmapLinkClient API](https://lbs.amap.com/api/aiot-sdk/guide/strong-ties/amaplinkclient-api)
- [iOS AMap IPC Link Kit](https://lbs.amap.com/api/aiot-sdk/guide/strong-ties/amap-ipc-link-kit)
- [IPC Link ErrorCode](https://lbs.amap.com/api/aiot-sdk/guide/strong-ties/ipcLlink-errorcode)
- [lrclib API 文档](https://lrclib.net/docs)

### 10.2 开源参考

- [hypercar](https://github.com/fw867/hypercar) —— AmapLink 逆向实现，本文档的导航部分主要参考
- [Navi-Link](https://github.com/shuhao1022/Navi-Link) —— 高德广播方案（车机版可用）
- [sdl_android](https://github.com/smartdevicelink/sdl_android) —— SDL 官方 SDK

### 10.3 本地存档

| 路径 | 内容 |
|---|---|
| `<资料目录>\syncdev\knowledge\` | 协议参考、构建指南、踩坑记录 |
| `<资料目录>\SyncFirmware\03-策略表与车辆数据.md` | 车辆数据封锁的完整证据链 |
| `<资料目录>\syncdev\references\hypercar\` | AmapLink 参考实现 |
| `<资料目录>\syncdev\SyncGlance-待办总表.md` | 完整待办清单 |
| `<工程目录>\` | 项目源码 |

> 原文档里写的是本机绝对路径，已换成占位符。

---

## 十二、构建与部署

### 11.1 构建

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
cd <工程目录>
.\gradlew.bat :app:assembleDebug
```

APK 输出：`app\build\outputs\apk\debug\app-debug.apk`

### 11.2 安装

```powershell
$adb = "C:\Users\<用户名>\AppData\Local\Android\Sdk\platform-tools\adb.exe"

& $adb install -r app-debug.apk
& $adb shell pm uninstall --user 900 com.xigaigai.syncglance   # ★ 清分身
& $adb shell pm grant com.xigaigai.syncglance android.permission.BLUETOOTH_CONNECT
& $adb shell pm grant com.xigaigai.syncglance android.permission.BLUETOOTH_SCAN
& $adb shell am start -n com.xigaigai.syncglance/.activity.MainActivity
```

### 11.3 首次使用

1. 打开 App，点「权限检查」授予蓝牙权限
2. 去系统设置打开 **通知使用权**（SyncPlay）
3. 车机蓝牙连上 SYNC，App 会自动启动
4. 手机打开高德 + 开始导航 → 车机自动显示导航

---

## 十三、已知限制

| 限制 | 原因 | 能否绕过 |
|---|---|---|
| 无法读取车辆数据 | 策略表封锁 | ❌ Ford 授权入口已关（2022） |
| `mediaTrack` 两屏共用 | SYNC 协议限制 | ❌ |
| 软键能画几个未验证 | `softButtonCapabilities` 只声明 1 项 | 软键测试按钮可探测 |
| 高德必须先运行 | AmapLink 依赖高德进程 | ❌ |
| 日语假名显示为方块 | 车机无日文字库 | ✅ 已转罗马音 |
| 标题栏可能截断 | 车机标题栏宽度有限 | ✅ 用短名 SyncPlay |
| 歌词冷门歌可能没有 | 网易云 + lrclib 双重覆盖仍有限 | 部分 |
| 歌名带括号导致匹配失败 | 网易云曲名常带「(中文名)」 | ✅ 查询时剥 A 类括号 |
| Live/翻唱版歌词串台 | 剥括号会抹掉版本信息 | ✅ key 用原始歌名 + B 类标签保护 |
| 歌词比声音慢半拍 | 车机刷新 1 秒 | ✅ 提前 0.5 秒 |
| 罗马音太长被截断 | 逐字加空格时宽度翻倍（现已改词级转换） | ✅ 多数句子能一行放下；极长句走翻页 |
| `⧗` / `⏸` 字形未验证 | 车机点阵字体可能缺这两字 | ⚠️ 上车确认，缺则换 ASCII |
| 网易云接口随时可能收紧 | 非公开接口，无契约 | ⚠️ lrclib 作为完整后备 |
| 锁屏界面未实现 | 未开发 | ❌ Ford 验证项会失败 |

---

## 十四、上车测试清单

### 14.1 基础连接

- [ ] 蓝牙连上 SYNC，App 自动启动
- [ ] 车机应用列表里显示 **西改改的Sync**
- [ ] 标题栏显示 **SyncPlay**（不截断）
- [ ] 车机选中 App 后，手机端状态变成 `已连接(FULL)`
- [ ] **通知栏状态是否跟着变**：`Waiting for connection (…)` → `Connecting through SDL (…)`
      → `Connected through SDL (FULL: PLY)`，格式见 `docs/通知移除与存活权衡.md` §12.3
- [ ] **断开后通知是否随服务一起消失**（官方语义：通知寿命 = 服务寿命）
- [ ] 日志里能看到 `【进程被拉起】` 或 `【进程已运行】收到` + 广播名，
      确认是被什么拉起的（2026-11-22 起替代旧的「启动来源」措辞）

### 14.2 断连重连（重点）

- [ ] 蓝牙断开 → 再连上 → 车机能重新搜到 App（**不用手动点重置**）
- [ ] 连蓝牙耳机（非 SYNC 设备）不会误触发服务启动

> 这是 §8.8 修复的核心问题，必须验证。

### 14.3 导航

- [ ] 车机没开高德时，100 秒后状态显示 `高德地图未启动`
- [ ] 点「高德地图」→ 拉起高德 → 自动连上 → 状态变 `已连接`
- [ ] 高德已连接时再点「高德地图」→ **不再重复连一次**
- [ ] 点「回家」→ 高德开始导航 → 车机显示导航
- [ ] 车机第 1 屏显示 `右转 145 m` / `动力二街`
- [ ] 车机第 2 屏显示 `预计22:13到达` / `23.0 km`
- [ ] **mediaTrack 显示 `→ 23.0 km`（带方向箭头）**
- [ ] **第 3 行右侧显示**ETA 时刻 `22:13` **并停住不走秒**
- [ ] **ETA 用 mm:ss 位承载**：到达 `16:21` 发的是 `StartTime(0,16,21)`，不是 `(16,21,0)`
- [ ] 导航中 ETA 变化 → 车机上的时刻**跟着更新**（导航数据推送即触发，不必等换歌）
- [ ] 结束导航 → **10 秒内**车机自动回到空闲模式

### 14.4 音乐

- [ ] 点「播放网易云」→ **不应该跳到网易云界面**
- [ ] 放歌 → 车机显示歌名 + 歌手
- [ ] 第 2 屏显示歌词，长句能折成两行
- [ ] **纯英文/德语歌词也能折行**（不能只有一行）
- [ ] mediaTrack 显示 `▶ 歌名`，暂停时变 `⏸`
- [ ] 第 3 行右侧显示播放进度在**走动**
- [ ] **不打开信息页（预览页）时，歌词也会自动滚动**（§8 心跳修复）
- [ ] 无歌词的歌 → 第 2 屏显示「无歌词信息」
- [ ] 日文歌名/歌词 → 显示成**词级**罗马音（如 `kimi ga ireba`，不是 `ki mi ga i re ba`）
- [ ] 独立助词处理正确：`私 は 学生 です` → `私 wa 学生 desu`
- [ ] 送假名不空格：`新しい` → `新shii`
- [ ] 片假名歌名整段大写：`カラオケ` → `KARAOKE`
- [ ] 第 2 屏长句已按新宽度折行，**没有出现 `...` 截断**
- [ ] **折行不切断单词**：长词（如片假名外来语）不会被劈成两半
- [ ] **翻页时每屏停留时间与字数成正比**：宽屏停得久、窄屏停得短
- [ ] 极窄的一屏（剩两三个字）是否来得及显示（≤1 秒会一闪而过，属已知取舍）
- [ ] 暂停超过 60 秒 → 自动回到欢迎页
- [ ] 从下拉栏划掉网易云媒体控件 → 自动回到欢迎页

### 14.5 导航 + 歌曲

- [ ] 状态显示 `导航 + 歌曲`
- [ ] 第 1 屏：导航指令 + 路名
- [ ] 第 2 屏：歌词两行
- [ ] **mediaTrack 显示 `→ 23.0 km`（不是歌名，且是箭头不是播放符）**
- [ ] 第 3 行右侧：ETA 时刻

### 14.6 计时器

- [ ] 正计时：车机第 3 行右侧数字在**走**
- [ ] 倒计时：滚轮选**分:秒** → 车机倒计时，**上限 59:59**（分钟滚轮只到 59）
- [ ] **暂停时那一格仍然显示计时值**（不能被清空）
- [ ] **暂停时 mediaTrack 前的指示符变成 `⧗`**
- [ ] 继续 / 清零都正常
- [ ] **计时器激活时，ETA 和播放进度都让位**
- [ ] **空闲模式下启动计时器，mediaTrack 位置显示 `⧗`**
- [ ] 失焦再回来，计时器还在走

### 14.7 菜单与按键

- [ ] More 里能看到三个父菜单（音乐/导航/计时）
- [ ] 点父菜单能展开子项（**不再是空菜单**）
- [ ] 点子项能触发对应动作
- [ ] 左右键能切歌
- [ ] 按 OK / PLAY_PAUSE 只触发**一次**（不能播放→暂停→播放）
- [ ] 按键不会触发语音播报「播放暂停」
- [ ] 键位设置在手机上改了能生效

### 14.8 软键

- [ ] 底栏第 1 格显示 `▶` 或 `⏸`（**确认字形不是方块**）
- [ ] 点它能切歌 / 暂停
- [ ] 点「软键测试」依次试 2、3、4、5、6 个：**记下车机实际画了几个**
- [ ] 软键占满时，第 4 格「更多…」是否被挤掉
- [ ] 日志里能看到 `OnButtonPress CUSTOM_BUTTON id=9001`
- [ ] **沙漏符号 `⧗` 在车机上能正常显示**（不是方块）

### 14.9 日志

- [ ] 信息页能看到带毫秒时间戳的日志
- [ ] 打开 JSON 开关，能看到完整 RPC
- [ ] 信息页打开时（预览可见），非 FULL 也能看到导航数据；关掉信息页就不再解析
- [ ] 「清空歌词缓存」能删掉缓存并重查当前歌（弹二次确认）
- [ ] 复制 / 分享功能正常

---

## 十五、快速排障

| 现象 | 检查 |
|---|---|
| 车机搜不到 App | 蓝牙配对了吗？`MultiplexTransportConfig` 加了吗？ |
| 打开就闪退 | 执行 `pm uninstall --user 900` |
| 显示"等待车机选中"很久 | 车机上手动选一下 SyncPlay |
| 导航一直显示"高德地图未启动" | 高德在运行吗？有没有给"关联启动"权限 |
| 歌词一直不出来 | 日志搜 `[Lyric]`：看是「网易云选中」还是「无歌词」。前者是解析问题，后者是匹配问题 |
| 日志出现「负缓存命中，跳过网络查询」 | 之前判定过没歌词，6 小时内不再查。清 `cache/lyrics/*.none` 可强制重试 |
| 日志出现「网络异常导致无歌词，不写负缓存」 | 断网了，不会污染缓存，联网后切歌即可重试 |
| 界面卡在旧内容 | 点「重置连接」 |
| 时间不显示 | 车机是否真的在 FULL 状态（看日志 `OnHMIStatus`） |
| 日文歌词还是逐字空格 | 搜 `kuromoji 初始化失败`：词库加载失败已降级；或旧缓存作祟，清 `cache/lyrics/*.sync.lrc` |
| 日志出现「成品歌词就绪」 | 正常：转写+折行已落盘，下次直接读，不用再算 |
| 日志出现「丢弃过期歌词」 | 正常：快速切歌时旧请求的结果被序号拦下 |
| 换歌瞬间第 2 屏闪一下「无歌词信息」 | 预期行为：新歌词未到前先清空，避免拿新歌进度切旧歌时间轴 |
