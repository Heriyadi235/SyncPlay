# SyncPlay —— 设计文档

> SYNC 1.1 / AppLink 车载信息面板
> 歌曲 · 歌词 · 导航 · 计时器
>
> 最后更新：2026-11-19
>
> 本文是**设计规格**——说明「做成什么样、为什么这样定」。
> 具体实现、踩坑记录、日志分析见 `SyncPlay-开发文档.md`。

---

## 一、项目定位

在 2012 款福特福克斯（中国）的 SYNC 1.1 车机（MFD4 四寸屏）上，
把手机变成一块**车载信息面板**：

1. **歌曲信息** —— 歌名、歌手、播放状态
2. **歌词** —— 带时间轴自动跟随，长句自动翻页
3. **导航信息** —— 转向指令、路名、剩余距离、预计到达时刻
4. **计时器** —— 正计时 / 倒计时，常驻第 3 行右侧

### 1.1 命名

| 场景 | 名字 |
|---|---|
| 车机应用列表（Mobile Apps 菜单） | **西改改的Sync** |
| 车机顶部标题栏 | **SyncPlay** |
| 手机桌面图标 | 西改改的Sync |

标题栏很窄（约 4 个汉字），所以用短名；菜单里空间大，用完整名。
实现上靠 `setShortAppName()` 把两者分开。

### 1.2 技术底座

- `sdl_android 4.12.2`（官方 SDK）
- 传输层 `MultiplexTransportConfig`（蓝牙 SPP，车机才能发现）
- `targetSdk = 32`（4.12.2 时代的要求：再高 SDL 库内部的 `registerReceiver` 会抛异常。升级到 5.8.0 后已提到 35）

---

## 二、屏幕布局

### 2.1 硬件约束（实车实测）

- 车机是 **MFD4**，`graphicSupported = false`（不能用图片）
- 文本字段 16 个，`width = 40`，但**实际可见宽度只有 7 个汉字**
  （`scrollableMessageBody` 例外，宽度 200）
- **底栏 4 格**：前 3 格归我们（`Show.softButtons`），
  第 4 格固定是车机自带的「更多…」，点进去是 `AddCommand` / `AddSubMenu` 建的菜单页
- 两屏由**右侧滚动条上下翻页**切换，app 无法控制

### 2.2 两屏三行

```
第 1 屏                          第 2 屏
┌──────────────────┐            ┌──────────────────┐
│ SyncPlay   20:18 │            │ SyncPlay   20:18 │
├──────────────────┤            ├──────────────────┤
│ mainField1       │            │ mainField3       │
│ mainField2       │  ←翻页→    │ mainField4       │
│ mediaTrack  clock│            │ mediaTrack  clock│
├──────────────────┤            ├──────────────────┤
│ 软键1 —  —  更多…│            │ 软键1 —  —  更多…│
└──────────────────┘            └──────────────────┘
```

**关键约束**：`mediaTrack` 是**全局唯一字段，两屏共用同一个值**，
无法给两屏设不同内容。第 3 行右侧的时钟由 `SetMediaClockTimer` 控制。

### 2.3 四种状态

状态由「有没有导航数据」×「有没有歌曲数据」决定：

| 状态 | 导航 | 歌曲 | 第 1 屏 | 第 2 屏 | mediaTrack |
|---|---|---|---|---|---|
| **A** 空闲 | ✗ | ✗ | 问候语 / 西改改 | （空） | （空） |
| **B** 仅导航 | ✓ | ✗ | 转向+距离 / 路名 | ETA 文字 / 剩余距离 | 剩余距离 |
| **C** 仅歌曲 | ✗ | ✓ | 歌词两行<br>（无歌词时：歌名 / 歌手） | 歌名 / 歌手<br>（无歌词时：「无歌词信息」） | 歌名 |
| **D** 导航+歌曲 | ✓ | ✓ | 转向+距离 / 路名 | 歌词两行 | 剩余距离 |

**D 状态的设计取舍**：第 1 屏给导航（开车时最重要），第 2 屏给歌词，
`mediaTrack` 给剩余距离（导航信息比歌名更需要余光可见）。

**C 状态为什么把歌词放第 1 屏**：车机会自己跳回第 1 屏，详见 §2.5。

### 2.4 文字宽度口径

统一用**半角单位**：

- 汉字 / 假名 / 全角标点 = 2
- ASCII 字母数字 = 1
- **一行容量 = 14 半角**（= 7 个汉字 = 14 个英文字母）

这个口径同时用于：歌词分行、歌词翻页切段、预览截断。

> 早期实现按「汉字个数」算，导致纯英文/德文歌词被误判成放不下、
> 且永远折不了行。改用半角单位后中英日文都正确。

### 2.5 车机会自己跳回第 1 屏（★ 决定布局的铁律）

**实车观察：停在第 2 屏一段时间后，车机会自动跳回第 1 屏。**

不是我们发的 `Show`（`Show` 是覆盖式写入，不会自己翻页），是车机自己的行为。

后果：

| 屏 | 可见性 |
|---|---|
| 第 1 屏（`mainField1/2`） | **稳定可见** |
| 第 2 屏（`mainField3/4`） | 看一会儿就没了 |

所以 **「需要持续看」的内容必须放第 1 屏**。据此调整过两处：

- **仅歌曲（C 状态）**：有歌词时第 1 屏给歌词两行、第 2 屏给歌名/歌手。
  （早先是反过来，结果歌词刚显示就被跳走，屏幕上只剩歌名歌手）
- **没歌词时**：第 1 屏仍给歌名/歌手（没有"要一直看"的东西，不必让位），
  第 3 行给一句「无歌词信息」。
- **导航+歌曲（D 状态）保持原样**：导航指令在第 1 屏、歌词在第 2 屏 ——
  开车时导航比歌词更不能丢。


### 2.6 歌词显示规则

| 情况 | 显示 |
|---|---|
| 一句 ≤ 一行（14 半角） | 第 1 行显示，**第 2 行留空** |
| 一句 ≤ 一屏（28 半角） | 折成两行，**优先从空格处断开** |
| 一句 > 一屏 | 按时间轴切成多段轮流显示（翻页） |
| 无歌词 | 第 2 屏第 1 行显示「无歌词信息」 |

**翻页算法**：

```
段数 = ceil(总宽度 / 28)
每段时长 = 本句总时长 × (该段宽度 / 本句总宽度)   ← 按字数比例，不是平均分
当前段   = 按各段起始时间查表
```

一句 35 宽、给了 8 秒，两段宽度 27 : 7，就是 6.3 秒 + 1.7 秒。

> **为什么按比例**：宽的一段唱得内容多，理应停久一点；平均分会让只剩两三个字
> 的最后一段也占掉同样长的时间。代价是极窄的一段可能不足 1 秒，而车机最快
> 1 秒才刷一次 —— 已知取舍，段数本身不会因此变化。

**折行的空格策略**（一屏内两行，2026-11 收紧）：

| 顺序 | 策略 |
|---|---|
| ① | **对半，但只在空格处断**（中点附近 3 字符内找空格） |
| ② | ① 找不到空格（中点落在长单词里）→ 尽量填满第一行，仍只在空格处断 |
| ③ | 整屏一个空格都没有（纯中文 / 一个超长单词）→ 按宽度硬切 |

之前 ① 是「±3 内找不到空格就硬切」，于是 `NO KONPYUUTAA NO` 会被折成
`NO KONPY` + `UUTAA NO`（词中被切断）。现在②会兜住这种情况。

**时间轴提前 0.5 秒**：车机屏幕最快 1 秒刷一次，算歌词时拿到的是「此刻」位置，
等 `Show` 送到车机再画出来已经过去几百毫秒。提前量让新句在它该唱之前就送到。

#### 2.5.1 假名 → 罗马音（词级，平文式）

车机没有日文字库，假名必须转写。**不是逐字加空格**（旧实现 `ki mi ga i re ba`
太长、且一行放不下），而是**按词切分**：

| 原文 | 输出 | 体现的规则 |
|---|---|---|
| `私 は 学生 です` | `私 wa 学生 desu` | 助词读 `wa`；词与词之间一个空格 |
| `新しい` | `新shii` | 送假名紧跟汉字，不空格 |
| `きみがいれば` | `kimi ga ireba` | 接续助词「ば」粘前词 |
| `カラオケ` | `KARAOKE` | 片假名词段 → 大写 |

**规则（项目内规范）**：

| 编号 | 规则 |
|---|---|
| R1 | 汉字 / ASCII / 数字 / 标点原样保留，不转写 |
| R2 | 平假名 → 小写；片假名 → 大写（按**词段**整体判定，不逐字符） |
| R3 | 助词按平文式读音：は→`wa`、へ→`e`、を→`o`（仅词性判定为助词时） |
| R4 | 长音 `ー` 重复前一元音（ラーメン→`raamen`）；**不用 macron**（车机字库只认 ASCII） |
| R5 | 促音 `っ`：双写，且 `sh→ssh`、`ch→tch`、`ts→tts`（こっち→`kotchi`） |
| R6 | 拨音 `ん` 后接元音 / y 时写 `n'`（しんよう→`shin'you`） |
| R7 | 词间 1 个空格；送假名与前面汉字之间**不**空格 |
| R8 | 接续助词（ば・て・ても・ながら・から（因为）…）粘在前一个词尾，不空格 |
| R9 | 原文空格视为词边界并保留（输出统一压缩成单空格） |

**规范依据**：拼写采用**平文式罗马字（Hepburn）**，它是目前最通用的方案，
日本护照也用它；R3 是平文式明文规定的助词读法。「平假名小写 / 片假名大写」
**没有官方标准**，是本项目自定义的显示约定（目的是一眼区分外来语），
写在这里作为唯一口径；代价是懂日文的人会觉得和标准写法不一致。

**实现**：`lyric/Romaji.java`

- 词边界用 kuromoji（IPADIC 词典）切，词性由分词器给出，不靠猜
- 依赖 `com.atilika.kuromoji:kuromoji-ipadic:0.9.0`（Apache-2.0），
  词典在 jar 内（**12.7 MiB**），从 classpath 资源加载，无需运行时下载
- 首次初始化 1~2 秒、内存峰值较高，所以**惰性**初始化；失败（老设备 OOM /
  资源缺失）自动降级到 `Kana.romajiLegacy`（旧的逐字空格），显示链路不中断
- 中文 / 英文 / 德语等**不含假名**的文本原样返回，不会被分词加空格

### 2.7 track 状态指示符

`mediaTrack` 最前面固定**一个**字符，**跟随该行内容类型**（不跟状态编号走）：

| 该行内容 | 指示符 | 说明 |
|---|---|---|
| 空 | 无 | 欢迎页不该出现孤立符号 |
| 歌曲信息 | `▶` 播放 / `⏸` 暂停 | |
| 导航信息 | 八方向箭头 | 见下表 |
| 计时器激活（含暂停） | `⧗` 沙漏 | 覆盖以上全部 |

动画方向箭头（用转向指令文本近似，高德不给方位角）：

| 指令 | 符号 |
|---|---|
| 掉头 / 后方 | `↓` |
| 左 / 左前 / 左后 | `←` / `↖` / `↙` |
| 右 / 右前 / 右后 | `→` / `↗` / `↘` |
| 直行 / 兜底 | `↑` |

**设计原则**：判定依据是「这一行当前装的是什么」，而不是「现在是 ABCD 哪个状态」。
以后加新布局，只要在填 track 时标明内容类型，指示符自动就对。

### 2.8 计时器显示

- 位置：第 3 行右侧，由 `SetMediaClockTimer` 渲染
- **暂停时仍占屏**：用「两步法」把读数定格 —— 见下
- 计时器存在（走秒或暂停）时，ETA 和播放进度都让位
- **倒计时上限 59:59**：车机那一格只有 `mm:ss`，分钟滚轮给到 59 为止
  （车机时钟格只有 `mm:ss`，没有小时位）

#### 2.8.1 「定格一个读数」必须两步（实车语义）

2026-11-21 用 RPC Builder 在车上验证出来（详见 `AGENTS.md` §6.6.1）：

| 指令 | `startTime` 是否生效 |
|---|---|
| `COUNTUP` / `COUNTDOWN` | ✅ 生效，从给定时间起表 |
| `PAUSE` / `RESUME` | ❌ **被忽略**，只暂停/继续当前那块表 |
| `PAUSE`，当前无 timer | ❌ 返回 `REJECTED` |
| `PAUSE`，当前已是暂停态 | ❌ 返回 `IGNORE` |

所以「把那一格定在某个读数」必须：

```
① COUNTUP(startTime=目标值)     起一块表
② PAUSE                         停住它（不带 startTime，带了也白搭）
```

两条**必须按顺序发**（`sendSequentialRPCs`）。并发发出去的话 PAUSE 可能先到，
就会被拒或者停错表。

> 历史教训：以前用的是 `updatePauseWithNewStartTime(定格读数)` 单条 PAUSE，
> 车机压根不看那个时间参数 —— 所以"暂停定格"其实一直是坏的，
> 表现就是那一格空着或者保持旧值。

### 2.9 导航 ETA 显示

**直接显示「几点到达」的固定时刻，不是倒计时。**

高德数据包里本来就带 ETA 时刻，没必要换算成「还剩多少」再倒计时。

**借 mm:ss 位显示到达时刻**：车机那一格是 `mm:ss`，把 `hh:mm` 拆成
`minutes=hh, seconds=mm`，车机会原样渲染成 `16:21`：

```java
// 两步法（见 §2.8.1）：
//   ① COUNTUP(StartTime(0, hh, mm))  → 车机起表
//   ② PAUSE                          → 停住
// ⚠ 单步 PAUSE + startTime 无效，车机会 REJECTED
SetMediaClockTimer[] pair = renderer.freezeClockRpc(hh, mm, 0);
```

> ⚠ **待实车确认**：两步法下 `CLOCK3` 那一格到底会显示成 `16:21`
> 还是别的东西（如 `00:16`）尚未验证。旧版一直用单步 PAUSE，
> 全被拒，从没看到过真实渲染结果。

**更新规则**：到达时刻变了才重发。高德的 ETA 按分钟算，同一分钟内反复推送
都是同一个值，所以不需要额外门槛。

**触发时机**：导航数据每次推送都要检查一次 ETA。只在换歌时检查的话，
一路导航下来到达时刻会永远停在第一次推送的那个值。


---

## 三、数据来源

### 3.1 歌曲信息

**路径**：`NotificationListenerService` → `Notification.EXTRA_MEDIA_SESSION`
→ `MediaSession.Token` → `MediaController` → `MediaMetadata`

```java
String title  = m.getString(MediaMetadata.METADATA_KEY_TITLE);
String artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
long   durMs  = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
PlaybackState ps = c.getPlaybackState();   // 播放状态 + 位置
```

**只需一个权限**：通知使用权（用户在系统设置里手动开）。
不需要签名级的 `MEDIA_CONTENT_CONTROL`。

**播放位置怎么拿**：通知只在内容变化时才推，播放中的位置不会每秒主动送达。
所以 `StateRenderer` 内部有个 **900ms 心跳**，定期 `pollNowPlaying()` 拉最新位置。
用「最后更新时间 + 流逝时间」推算比直接读 `getPosition()` 准。

### 3.2 歌词

**主源：网易云音乐**（实测无需登录、无需签名，带 `Referer` 即可）

```
① 搜索定位 songId
GET https://music.163.com/api/search/get/web?s=完整歌名&type=1&limit=8
② 拉歌词
GET https://music.163.com/api/song/lyric?id=xxx&lv=1&kv=1&tv=-1
```

返回的 `lrc.lyric` 是标准 `[mm:ss.xxx]` 格式，可直接解析。

**备源：lrclib.net**（免费、无 key，`duration` 必须匹配 ±2 秒）

**降级链**：

```
① 内存缓存
② 成品缓存（.sync.lrc：转写 + 折行 + 翻页都已做完，显示时零算力）
②' 原始磁盘缓存（.lrc，key = 原始完整歌名 + 歌手的 MD5）
②.5 负缓存（.none 文件，6 小时过期）—— 跳过网络
③-A 网易云：MEDIA_ID 直取（优先）→ 搜歌 → 硬门槛筛候选 → 拉 lrc
③-B lrclib 三级：原名精确 → 剥括号精确 → /api/search 模糊
④ 显示「无歌词信息」
```

**手动重查**：信息页有「清空歌词缓存」按钮，删掉 `.lrc` / `.sync.lrc` / `.none`
三类缓存并重查当前这首（歌词匹配错了、或负缓存把有歌词的歌锁死时用）。


#### 3.2.1 缓存 key 用原始完整歌名

**不做任何剥括号。** 因为剥了会让不同版本撞在一起：

| 标题 A | 标题 B | 剥括号后 | 后果 |
|---|---|---|---|
| `夕阳之歌` | `夕阳之歌 (Live)` | 都是 `夕阳之歌` | Live 歌词被缓存成录音室版 |

时长**不**参与 key —— 很多播放器不给 `DURATION`（值为 0），
一旦参与就会让同名不同版本全部撞在一起。

#### 3.2.2 括号内容分两类（只影响「怎么查」）

查询时（`TitleNorm.queryTitle()`）按内容分类处理，不参与缓存身份判定：

**A 类 — 可以剥**（歌词相同）

- 译名：`君は薔薇より美しい (你比玫瑰更美丽)` → `君は薔薇より美しい`
- 出处：`梦缘 (梅艳芳"夢伴"原曲)` → `梦缘`
- 母带：`Bohemian Rhapsody (Remastered 2011)` → `Bohemian Rhapsody`

**B 类 — 必须保留**（歌词不同，剥了就查错歌）

`Live` / `现场` / `演唱会` / `Unplugged` / `Remix` / `混音` / `伴奏` /
`Instrumental` / `纯音乐` / `Karaoke` / `翻唱` / `Cover` / `片段` / `Acoustic` / `Demo`

判据一句话：**括号内容是否描述了「这首录音和原版有什么不同」**。
是 → 保留；只是译名/出处/注释 → 剥。

#### 3.2.3 网易云搜索用完整歌名

搜索时**不剥括号**。括号里往往正是区分度最高的信息（译名/出处/版本），
剥掉会搜到同名不同曲（实测：唐朝乐队《天堂》剥完搜到了另一个歌手的《天堂》）。

#### 3.2.4 MEDIA_ID 直取（首选路径）

网易云播放时，媒体会话的 `METADATA_KEY_MEDIA_ID` **就是网易云的 song id**
（实测 `276235` → `/api/song/detail?ids=[276235]` 返回的正是当前这首）。

所以第一步先拿它反查曲目信息，与通知里的歌名/歌手/时长校验通过后直接取歌词 ——
**100% 命中，绕开整个"搜索 + 猜"环节**。

校验不过就退回搜索：别的播放器 mediaId 含义各异（专辑内序号、数据库主键……），
撞号会查到毫不相干的歌。校验条件：标题档位 ≥ 主标题相同、时长差 ≤ 5 秒、
歌手不是完全不认识。

#### 3.2.5 候选筛选用「档位 + 硬门槛」，不是加权总分

打分制被一个真实 case 推翻过：放《艳舞台+烈焰红唇(Live)》（311 秒串烧），
网易云搜不到这首，返回的候选里只有 227 秒的录音室版《烈焰红唇》。
旧打分给它 `标题包含 +40 / 歌手一致 +60 / 时长差 84 秒 −40 = 60 分`，
**只要 > 0 就采纳** → 整首歌显示另一首曲子的词。

现在的规则：

| 门槛 | 规则 |
|---|---|
| 串烧必须整串覆盖 | 查询侧是 `A+B` 多段式时，候选必须覆盖**所有段**，只覆盖一段 = 另一首曲目，**直接淘汰** |
| 时长硬门槛 | 标题只是"包含"关系且时长差 > 60 秒 → 淘汰（另一首录音） |
| 歌手硬门槛 | 标题对上了但歌手完全是别人 → 淘汰（整串完全相同的情况放宽） |
| 排序 | 标题档位 > 歌手档位 > 时长差（没有加权总分，不通过就淘汰） |

段与段的比对会**忽略所有括号内容**（`TitleNorm.stripBrackets()` 是故意保留
`Live` 的，用它会让「烈焰红唇(Live)」和「烈焰红唇」算成两段）。


候选打分维度：

| 条件 | 分值 |
|---|---|
| 整串完全一致 | +140 |
| 剥括号后主标题一致 | +100 |
| 标题互相包含 | +40 |
| 完全不沾边 | −60 |
| 歌手完全一致 | +60 |
| 歌手完全不沾边 | −50 |
| 时长差 ≤2 秒 | +50 |
| 时长差 >30 秒 | −40 |

时长是排除 Live / 翻唱最有效的一招。

#### 3.2.4 成品歌词缓存（车机专用 LRC）

**为什么要有**：假名转罗马音要分词（首次 1~2 秒），长句还要折行/翻页。
每帧算一遍纯属浪费，而**同一首歌永远不需要算第二次**。
所以把「转写 + 折行 + 翻页」一次性做完存成新文件，显示时只按时间取屏。

| 项 | 内容 |
|---|---|
| 文件 | `<cacheDir>/lyrics/<md5>.v2-14x2.sync.lrc` |
| 首行 | `#syncglance sync-lyric v2; units=14; pages=2; romaji=hepburn; caps=kana-seg` |
| 行格式 | `[mm:ss.mmm]第一行␟第二行` —— 每行是**一屏**，用 U+241F 分隔，第二行可为空 |
| 失效 | 读取时逐字比对首行签名；规则版本 / 每行宽度 / 每屏行数任一变化即作废重建 |

几个刻意决定：

- **分隔符不能用 `\u0001`**：`Lrc.parse()` 会对每行 `trim()`，而 trim 会吃掉
  ≤ U+0020 的字符，第二行为空时分隔符就被无声删除，「成品」与「原始 LRC」再也分不出来。
- **0.5 秒提前量不写进文件**：那是查询时的补偿，改它不需要重建缓存。
- **每屏最多两行、每行 ≤14 半角**在生成时就固化，渲染端不再计算宽度。
- **生成时机**：网络拿到歌词后当场生成；若只有旧的 `.lrc` 而没有成品，
  先按旧路径显示（不卡首屏），再后台生成并替换。

#### 3.2.5 请求序号：丢弃过期结果

`LyricManager` 每次 `load()` 自增一个序号，所有回调前比对。因为 IO 是单线程、
请求不会取消，快速切歌（A→B→A）时旧请求的结果会晚于新请求到达，
不校验就会「放着 B、屏幕显示 A 的歌词」，且一直错到下次切歌。

### 3.3 导航信息

**来源：高德 AmapLink（穿戴设备通道）**，不是通知。

```
Intent("com.autonavi.minimap.service.NAVIGATION_SERVICE")
  → ComponentName("com.autonavi.minimap",
                  "com.amap.bundle.wearable.connect.glass.AmapLinkService")
```

流程：bindService → 授权（拿 token）→ 注册回调 → 收加密包 → AES-GCM 解密 → 解析

**关键业务字段**（bizType 113 → datas 数组）：

| type | 含义 | 关键字段 |
|---|---|---|
| 4 | 导航信息 | `curManeuverID` `segmentRemainDist` `routeRemainDist` `routeRemainTime` `nextRouteName` |
| 5 | 转向图标 | |
| 7 | 导航开始 | 需清空旧状态 |
| 14 | 车道信息 | |
| 100 | 导航结束 | 需立即降级 |
| 200 | 到达 | 同上 |

**重连策略**：官方配置 `maxReconnectAttempts = 50`、`reconnectDelay = 2s`（约 100 秒）。
超时后显示「高德地图未启动」。

**超时降级**：高德正常每秒推一次，**8 秒**没更新判定导航已停止，
2 秒检查一次，最坏 10 秒内自动降级到 B/A 状态。

---

## 四、交互

### 4.1 菜单结构（More… 里）

```
More...
 ├─ 音乐 (menuID=901)
 │   ├─ 上一首 (1001) / 下一首 (1002) / 播放暂停 (1003)
 │   └─ 显示歌词 (1004) / 隐藏歌词 (1005)
 ├─ 导航 (menuID=902)
 │   └─ 开启导航模式 (2001) / 关闭导航模式 (2002)
 └─ 计时 (menuID=903)
     └─ 开始正计时 (3001) / 开始倒计时 (3002)
        暂停 (3003) / 继续 (3004) / 清零 (3005)
```

父菜单用 `AddSubMenu`，子项用 `AddCommand` 且**必须带 `menuParams.parentID`**
（否则父菜单展开是空的）。

### 4.2 软键（底栏）

**已实车验证有效**。下发与回调：

```
Show.softButtons  ← 下发
OnButtonPress { buttonName: CUSTOM_BUTTON, customButtonID: 9001 }  ← 回调
```

- **第 1 格固定播放/暂停**（`▶` / `⏸`），软键 ID 9001
- 第 2、3 格空闲
- 第 4 格「更多…」由车机自带，不可定制
- 软键**不走** `OnCommand`，也不在物理键表里，需单独处理
- 车机 `softButtonCapabilities` 只声明 1 项，**能画几个待实车验证**
  （主界面「软键测试」按钮可循环发 1~6 个来探测）

### 4.3 物理按键

车机声明 **15 个**按键（注意 `PLAY_PAUSE` 声明了两次）：

```
OK, SEEKLEFT, SEEKRIGHT, PRESET_0~9, PLAY_PAUSE ×2
```

**不支持**：`TUNEUP` / `TUNEDOWN`（返回 `UNSUPPORTED_RESOURCE`）

**默认映射**：

| 键 | 功能 |
|---|---|
| SEEKLEFT | 上一首 |
| SEEKRIGHT | 下一首 |
| PLAY_PAUSE | 播放/暂停 |

其余可在 app 内「键位设置」里自定义，存 `SharedPreferences`。

#### 4.3.1 OK 与 PLAY_PAUSE 是同一个物理键

按一次会收到**两条** `OnButtonPress`（间隔 5~11ms）：

```
OnButtonPress { OK,         SHORT }
OnButtonPress { PLAY_PAUSE, SHORT }
```

**处理**：`CommandRouter` 按「同一物理键别名」去重，200ms 窗口内后到的丢弃。

**注意不能按 cmdID 通用去抖** —— 那会把「用户快速连按同一个键」也吃掉
（快速切两首歌，第二下失效）。只针对 OK/PLAY_PAUSE 这一对合并。

结果：一次按键只触发一个动作，且快速连按不受影响。

---

## 五、生命周期

### 5.1 HMI 状态机

```
DISCONNECTED → CONNECTING → NONE → BACKGROUND → LIMITED → FULL
```

- **只有 FULL 能发 RPC**，其他等级发出去会抛「没有有效连接」
- `firstRun = true` 表示本次连接首次进入，需做完整初始化
- `OnProxyClosed` 后，车机上所有菜单/订阅都被删除，重连必须全量重建
- 掉出 FULL 时车机会自动终止进行中的操作（含计时器）

### 5.2 autoActivateID 持久化

首次启动生成 32 位 UUID 存 `SharedPreferences`，之后每次注册都回传。

**作用**：官方说明它可「重新获取 HMILevel = FULL」，且**没有有效期限制**。
App 进程被系统杀掉、或车机重新扫描应用时，车机能凭这个 ID 直接把我们
重新激活成全屏，而不必让用户再去菜单里点一次。

**必须永久稳定** —— 一变车机就认不出是同一个 App 实例。只有卸载重装才换新的。

### 5.3 语言不指定

`languageDesired` 传 `null`（即不调用 `setLangDesired`），跟随车机当前语言。

本车实测语言是 `ZH-CN`。硬编码会在车机语言被切换时直接注册失败。
Ford 自家 `Sync Destinations` 也是留空。

### 5.4 蓝牙唤醒：两个 receiver 的分工（2026-11-22 定稿）

车机无法主动唤醒手机上的 app，所以靠系统广播把进程叫起来 —— **但只叫进程，不建连接**。

#### 5.4.1 关键事实：Router 自己不听广播

`SdlRouterService` 是 **Service 不是 receiver**，没法监听 `ACL_CONNECTED`，只能被别人启动。
反编译 `sdl_android 5.8.0` 确认：**整个库里唯一匹配该 action 的是 `SdlBroadcastReceiver`**，
它收到后调 `wakeUpRouterService()` → `Context.startForegroundService(RouterService)`
（顺带也是那条 `SDL: 包名 / Waiting for connection...` 通知的来源）。

```
车机蓝牙连上 → 系统发 ACL_CONNECTED
  → 命中 SdlReceiver 的 intent-filter（我们继承了库的 SdlBroadcastReceiver）
  → 系统拉起应用进程 → 库的 SdlBroadcastReceiver.onReceive()
  → wakeUpRouterService() → startForegroundService(RouterService)
  → 车机连通 Router → sdl.router.startservice → SdlReceiver.onSdlEnabled()
  → startForegroundService(SdlService)
```

#### 5.4.2 本项目两个 receiver 的分工

| receiver | 声明 `ACL_CONNECTED` | 收到后做什么 |
|---|---|---|
| `SdlReceiver`（继承库的 `SdlBroadcastReceiver`） | ✅ | **由库拉起 Router**；车机真正连通 Router 后再回调 `onSdlEnabled` → 拉起 `SdlService` |
| `BootReceiver`（4.x 时代自写） | ✅ | **一个 start 都不调**：只记日志 + 维护 MAC 白名单 |

→ 所以 `BootReceiver` 的 `ACL_CONNECTED` 监听对「拉起」是**冗余**的，删掉不影响连接行为。
留着是因为它是唯一记录「谁把进程叫醒」的地方（`SdlReceiver` 要等车机连通后才打日志），
而且零成本（同一次广播进程只拉起一次）。

完整的事件 → 处理者对照：

| 事件 | 谁处理 | 动作 |
|---|---|---|
| `ACL_CONNECTED`（任意蓝牙设备） | `SdlReceiver`（库代码） | 拉起 Router |
| `ACL_CONNECTED` / `ACL_DISCONNECTED` | `BootReceiver` | 只记日志（含设备白名单判断）+ MAC 白名单 |
| `BOOT_COMPLETED` / 蓝牙 `STATE_CHANGED` / `MY_PACKAGE_REPLACED` | `BootReceiver` | 只记日志 |
| `sdl.router.startservice` | `SdlReceiver` | `onSdlEnabled` → 启动 `SdlService` |

**`BootReceiver` 明确不做的两件事**（都踩过）：

| 不做 | 原因 |
|---|---|
| `startForegroundService(SdlService)` | 4.12.2 时代的做法。5.x 里它等于立刻建 `SdlManager`，而库还没把 Router 叫起来（库异步约 110ms，我们 5~20ms）→ Router 还没 listen 就去 bind → 5~11ms 自毁 → 车机卡「正在连接」**2 分钟** |
| `queryForConnectedService(ctx)` | 广播的 context 是 `ReceiverRestrictedContext`，库会拿它 `bindService` → `ReceiverCallNotAllowedException` → **进程闪退**（实车崩过两次） |

> ⚠ **早期版本的本节表格是错的**：曾把「`ACL_CONNECTED` → `queryForConnectedService` 探测」
> 写成 `BootReceiver` 的行为。那条 2026-11-21 就删了（就是上面那个闪退）。
> 现在探测只发生在 `SyncGlanceApp.onCreate` 和 `MainActivity.onCreate` ——
> 那里是 Application / Activity 的 context，调同一个 API 才安全。

> 真正的服务启动入口只有一个：`SdlReceiver.onSdlEnabled`（车机已连通 Router 之后）。
> 完整证据见 [连接可靠性排查.md](连接可靠性排查.md) §11.1 / §11.5。

#### 5.4.3 设备过滤（`BootReceiver` 里）

**只响应目标设备**，避免蓝牙耳机/音箱误触发。判定顺序：

| 顺序 | 条件 | 结果 | 记入 MAC 白名单 |
|---|---|---|---|
| 1a | 设备名含 `SYNC` | ✅ | ✅ |
| 1b | 设备名读得到但都不匹配 | ❌ | — |
| 2 | 设备名读不到，MAC 在白名单 | ✅ | — |
| 3 | 设备名读不到，MAC 不在白名单 | ❌ | — |

**MAC 白名单是集合，不限数量**：凡成功识别过的设备都记下来，
以后名字读不到时（权限问题、系统未缓存）还能靠 MAC 认出来。
可以同时记住车机 + 以后换的车。

**名字读得到时以名字为准，不查 MAC** —— 名字变了说明换了设备，
此时信名字比信 MAC 可靠。

### 5.5 断连与重建

`onProxyClosed` 后 `proxy` 对象仍存在（只是失效），
而 SDK 在 `TRANSPORT_DISCONNECT` 之后**自己不会重建 SPP 连接**。

所以启动时必须判断 **`lifecycle 是否 DISCONNECTED`**，而不是只看 `proxy == null`：
否则会出现「蓝牙断开 → 用户手动开 App → proxy 非 null → 跳过重建 → 监听永远死着」。

### 5.6 强制停止的后果

被 **force-stop**（系统设置「强行停止」或深度清理）后，Android 会把应用置为
**stopped state**：所有广播失效，**蓝牙自动拉起不会工作**，直到手动打开一次 App。

这是 Android 机制，代码层面绕不过去。建议把应用加进**电池优化白名单**
和**自启动白名单**（国产 ROM 尤其需要）。

### 5.7 通知栏：连接状态通知（随连接生命周期）

> ### ✅ 2026-11-22 晚：通知已**恢复**，文案换了新格式
>
> 当天白天曾整条移除，晚上查清「两条通知」是 SDL 固有设计后恢复
> （[SDL-0309 提案](https://github.com/smartdevicelink/sdl_evolution/blob/master/proposals/0309-android-notifications.md)
> 原文：router 一条 + 每个 app 一条，开发者无法影响，加开关的提案被 Rejected）。
> 来龙去脉、文案矩阵、图标规格见
> **[`docs/通知移除与存活权衡.md`](通知移除与存活权衡.md)** 第十二节。

`SdlService` 挂着一条**不可清除**的通知（`setOngoing(true)`），
标题固定为**应用名**「西改改的Sync」，
小图标用 SDL 的 `sdl_tray_icon`，**不设大图标**（对齐官方 App 那条的样式）。

正文风格对齐官方截图，**只报状态枚举**（不放歌名/导航指令，那两个每秒都可能变）：

```
Connected through SDL (FULL: IDLE)              ← HMI 等级: 车机界面内容
Connected through SDL (FULL: PLY/NAV)
Connecting through SDL (Registering)            ← 连接中，卡在哪一步
Connecting through SDL (Retrying 1/4)           ← 秒失败重试中
Waiting for connection (Router listening)       ← 未连
Connection closed                               ← 连过又断（几乎看不到，随即 stopSelf）
```

| 维度 | 取值 |
|---|---|
| HMI 等级 | `FULL` / `LIMITED` / `BACKGROUND` / `NONE` |
| 界面内容 | `IDLE` / `PLY` / `NAV` / `PLY/NAV` |

完整决策优先级（8 条）与 16 格矩阵见
[通知移除与存活权衡.md §12.3](通知移除与存活权衡.md)。

文案规则集中在 `display/ConnectionStatus.java`（纯逻辑，可单测）。

**Router 存活怎么知道的**：SDL 5.8 没有 `isRouterServiceRunning()` 这类 API，
所以 `SdlRouterService`（我们自己的子类，跑在 `com.smartdevicelink.router` 独立进程）
在 `onCreate` / `onStartCommand` / `onDestroy` 里发**应用内广播**上报，主进程被动接收。
已知盲区：Router 进程被系统直接杀掉时 `onDestroy` 不回调，`routerAlive` 会停在
「运行中」直到下次 Router 起来自愈（影响日志、秒失败判定和通知正文里的 Router 状态）。

> **生命周期跟官方一致**：通知的生命周期 = `SdlService` 的生命周期。
> 断开时刷一下「Connection closed」，紧接着 `stopSelf()` 把服务和通知一起收走。
> 所以**没连车机时通知栏没有 SyncPlay** —— 这是刻意选择
> （见 `AGENTS.md` §1.1：不为了显示去改连接生命周期）。

### 5.8 连接链路的真相：车机是主动方

手机端**从不发起** SDL 连接。完整时序：

```
① 手机与车机建立蓝牙音频连接（A2DP/HFP，跟 SDL 无关）
② Android 发 ACL_CONNECTED 广播
③ SdlReceiver 收到 → 启动 SdlRouterService
④ RouterService 注册 SDP 记录 + 监听 RFCOMM
⑤ 车机做 SDP 服务发现 → 发起 RFCOMM → 连上
⑥ RouterService 多路复用 → SdlService 建 SdlManager → RAI
```

所以 **RouterService 不需要常驻**，它只需要"在车机要连的那一刻活着"：

- 车机发现的是 **SDP 记录**，不是某个一直跑的服务
- Router 没跑时，车机能发现记录但没人 accept → 车机 UI 卡「连接中」
- 只要 `ACL_CONNECTED` 送达并成功拉起 Router，车机重试就能连上
- **唯一会彻底连不上的情况**：App 被 force-stop 或系统禁止自启 → 收不到广播
  → Router 永远起不来 → 只能手动打开一次 App（见 5.6）

> 因此本项目**不主动拉起 Router**，也不做应用层定时重试 —— Router 完全由
> `ACL_CONNECTED` / SDL 库 / `queryForConnectedService` 控制。

#### 5.8.1 补连接（官方推荐）

应用启动时调一次 `SdlBroadcastReceiver.queryForConnectedService()`，
主动问「现在有没有已连接的 RouterService」。用于「上车时 App 没起来 →
车机连不上就放弃 → 用户后来才打开 App」的场景。

- 位置：`SyncGlanceApp.onCreate()`（Application 级，任何进程启动都会跑）
- **必须只在主进程执行**：`SdlRouterService` 跑在独立进程，那个进程里
  「Router 问自己连上了吗」没有意义，还会干扰时序
- **首次安装后的第一次启动会失败**（实测：第二次就成功）—— `Application.onCreate`
  跑得极早，蓝牙权限/库内部初始化还没就绪，异常 message 往往为空。
  所以失败时延迟 2 秒重试一次；仍失败也无妨，`ACL_CONNECTED` 主链路会兜住
- 车机端还得重新做一次 SDP 查询才知道记录回来了，这一步不受应用控制

> 历史：这段逻辑原先挂在 `SdlService.onStartCommand()` 里，用一个**实例字段**
> 标记「只做一次」。但服务是「断开就 stopSelf、靠广播重新拉起」，每次重建都是
> 新实例 → 标记重置，实际语义变成「每个实例一次」，和注释不符。现已挪到
> Application 层。

#### 5.8.2 关于 `IntegrationValidator` 那个 ✕（不要被它误导）

debug 包首次调用 `queryForConnectedService` 时，日志会出现：

```
W/SyncGlance: [SdlReceiver] queryForConnectedService 失败: RuntimeException
  Integration Validator Results:
  ✓ Permission check passed
  ✓ SdlRouterService checks were not performed yet due to supplied flags
  ✕ This application has not specified its SDL Receiver properly.
```

`IntegrationValidator.checkBroadcastReceiver()` 的判据是：用
`queryBroadcastReceivers(Intent("sdl.router.startservice"), 0)` 查结果，
再和 `PackageInfo.receivers` 逐个比类名。

**实测结论：我们的注册是对的**，设备上查得到：

```
$ adb shell cmd package query-receivers -a sdl.router.startservice
1 receivers found:
  name=com.xigaigai.syncglance.receiver.SdlReceiver
  enabled=true exported=true       ← 与官方要求一致
```

`TransportConstants.START_ROUTER_SERVICE_ACTION` 的值也确认是
`"sdl.router.startservice"`（与 Manifest 一致）。

所以这个 ✕ 是**校验器在自己进程内查询时的判定问题**（debug 包 + 该 ROM 的
查询可见性差异），不是配置错误。判断要点：

- 只在 **debug 包**出现（`DebugTool` 由 `FLAG_DEBUGGABLE` 决定，release 不跑）
- **只影响首次调用**：2 秒后的重试就成功了（见 §5.8.1 的失败重试）
- 真实补连接不依赖这个检查（它内部找的是 RouterService，不是 receiver）

> 结论：看到这个 ✕ **不要**去改 Manifest 的 receiver 声明，先按上面三条排除。


### 5.9 与官方 Integration Basics - Java 的逐条对照

官方文档：[Integration Basics - Java](https://smartdevicelink.com/en/guides/android/getting-started/integration-basics-java/)

| 官方要求 | 本项目 | 说明 |
|---|---|---|
| 用**前台服务**承载 SDL，不能用 Activity | ✅ `SdlService` | 官方原话：Android 10 限制后台启动 Activity。（2026-11-22 曾短暂降级为普通服务，当晚已恢复） |
| Service 声明 `exported="true"`、`enabled="true"`、`foregroundServiceType="connectedDevice"` | ✅ | 应用在后台时 Router 可能拉不起服务 |
| `onCreate` 里建通知渠道 + `startForeground` | ✅ | 渠道 `syncglance` / `IMPORTANCE_LOW`；`startForeground` 放在 `onStartCommand` 第一行（见下条） |
| **`onDestroy` 里 `stopForeground`，不要留通知** | ✅ | `stopForeground(STOP_FOREGROUND_REMOVE)`，不删渠道 |
| `onStartCommand` 里建 `SdlManager`，返回 `START_STICKY` | ✅ | 官方在 `onStartCommand` 里建；我们在外面套了 `needRebuild()`（已连接就复用）。**但这只在 `onSdlEnabled` 拉起服务时才有意义** —— 从系统广播直接启动会在 Router 就绪前抢跑，见 §5.4 |
| `MultiplexTransportConfig` 作为传输 | ✅ | 2026-11-22 起**显式传 `FLAG_MULTI_SECURITY_OFF`**（跳过 `RouterServiceValidator` 信任校验，否则空队列路径会再多一刀秒退）。⚠ 它只拆掉一条附加失败路径，**不是**恢复连接失败的根因 —— 根因是车机响应延迟 2~7 秒、5.x 等不起，见 `连接可靠性排查.md` §10 |
| `setAppTypes([MEDIA])` | ✅ | 决定车机把它归到「媒体 / 音频」 |
| `setShortAppName` | ✅ | 车机列表里用短名 |
| `setAppIcon` | ⛔ 故意不做 | 本车 `graphicSupported=false`，图标用不了 |
| `SdlManagerListener` 五个方法 | ✅ 全实现 | `onStart` / `onDestroy` / `onError` / `managerShouldUpdateLifecycle` / `onSystemInfoReceived` |
| **`onSystemInfoReceived` 必须返回 `true`** | ✅ | 官方标了 "Must"：返回 false 会导致应用连不上（AS 自动生成的就是 false） |
| listener 的 `onDestroy()` 里 `stopSelf()` | ✅ | 官方示例就是 `SdlService.this.stopSelf()` |
| `SdlService.onDestroy()` 里 `sdlManager.dispose()` | ✅ | 官方标了 "Must" |
| `SdlManager` 实例不可复用，断开后必须新建 | ✅ | 用「代际号」`generation` 忽略旧对象的迟到回调 |
| `SdlReceiver extends SdlBroadcastReceiver`，`exported="true"`，监听 `ACL_CONNECTED` + `sdl.router.startservice` | ✅ | 缺 `sdl.router.startservice` 会让 `queryForConnectedService` 直接抛异常 |
| `defineLocalSdlRouterClass()` 返回自己的 Router 子类 | ✅ `SdlRouterService` | |
| **`onSdlEnabled` 启动服务要分版本** | ✅ 已落实（双路） | 先发 Router 递来的 PendingIntent（`SdlService` 已加 `sdl.router.startservice` 的 intent-filter 让它可被解析），再 `startSelf` 兜底。细节与实验见 [场景对照](sdl生命周期-场景对照.md) 注② |

**两处曾漏掉、2026-11-21 补齐的**：

1. `SdlService` 的 `exported` 从 `false` 改 `true` —— 应用在后台时，
   Router/系统从外部启动服务需要它可以被外部访问
2. `onSdlEnabled` 补 PendingIntent 路径 —— 官方对 Android 12+ 的 Must

> **2026-11-22 的曲折**：第 2 条一度被撤回（当时 `send()` 后服务起不来），
> 后来查明根因是**没有任何 service 声明 `sdl.router.startservice`**
> —— 那个 action 只挂在 `SdlReceiver`（receiver）上，而 `startForegroundService`
> 只按 service 的 intent-filter 解析，于是 system_server 报 `not found`
> （且该异常不回传给发送方）。
> 给 `SdlService` 加上同名 intent-filter 后已打通，本机实测通过；
> 现在保留**双路**：PendingIntent 优先，`startSelf` 兜底。

> 我们额外做、官方 Integration Basics 里没有的：通知监听（`NotificationCatcher`）、
> 高德 AmapLink 解密、`SyncPlayCore` 把数据采集与连接层解耦。这些属于业务，不影响
> SDL 生命周期主链路。

### 5.10 职责边界：`SyncPlayCore` vs `SdlService`

`SdlService` 现在按官方语义「断开就 `stopSelf`」，**平时根本不存在**
（只在车机连接期间活着）。所以凡是「连接不在也该能用」的东西，不能挂在它上面。

| 能力 | 归属 | 服务不在时 |
|---|---|---|
| 数据采集（音乐通知 / 高德导航） | `SyncPlayCore` | ✅ 正常 |
| 渲染与**屏幕预览**（`StateRenderer` + `ScreenFrame` + `ScreenPreviewView`） | `SyncPlayCore` / 独立预览器 | ✅ 正常 |
| **计时器**（正/倒/暂停/清零） | `SyncPlayCore.doTimer()` | ✅ 本地状态生效、预览正确；RPC 记 pending |
| **导航模式**开关 | `SyncPlayCore` | ✅ 正常 |
| 歌词加载与缓存 | `SyncPlayCore` | ✅ 正常 |
| 发 RPC / 重置连接 / 软键测试 | `SdlService` | ❌ 报「服务未启动」是正确的 |

**踩过的坑**：计时器原本写在 `SdlService.doTimer()` 里，`MainActivity` 走
`SdlService.getInstance()`。服务常驻时看着没问题；一改成官方语义（断开就退出），
点计时器就变成「服务未启动」直接 return —— **连屏幕预览都不动了**。

修复：计时器搬进 `SyncPlayCore`，内部拆成三步
「① 更新本地状态 → ② 构造 RPC → ③ 有连接才发，没连接记 pending」，
连上后由 `SdlService.initializeForConnection()` 调 `resendTimerIfPending()` 补发。

> 另外 `setTimerState()` 曾漏了 `markDirty()`（`setTimerRunning()` 有、它没有），
> 导致点了计时器只有 RPC 发出去、`lastFrame` 不重建，预览那一格不动 —— 一并修了。

#### 5.10.1 计时器那一格显示什么（四条硬规则）

媒体时钟格（mm:ss）的取值优先级：**用户计时器 > 导航 ETA > 音乐播放时间 > 空**。

踩过的三个坑（2026-11-21 全部修复并实测）：

| 现象 | 根因 | 修法 |
|---|---|---|
| **暂停音乐，秒数还在往上爬** | 取指令的回退分支「沿用上一条还在走的指令」把播放计时也沿用了 | 音乐暂停时显式构造 `PAUSE` 定格在最后位置，不再靠沿用 |
| **正计时清零后，不回落到播放时间** | 同上：回退分支拿到旧指令，没走到"播放时间"那条 | 回退分支改为直接返回 `none()`；播放时间分支条件放宽成"只要有歌数据" |
| **倒计时 5 分暂停，显示 00:10 而不是 04:50** | `setTimerState()` 在方法最前面无条件写 `timerH/M/S` 和 `timerCountdown`，点「暂停」时传的是 `(0,0,0,"PAUSE")`，起始值和方向都被冲掉 | 只有 `COUNTUP` / `COUNTDOWN`（开始）才允许重置方向与起始值；`PAUSE` 只停止走秒 |

**track 行的状态指示符：已移除**（2026-11-21）。

最初用 `§`（U+00A7）在 track 前标记"时钟格当前是计时器"，后来软键第 3 格的
**高亮**已经表达了同一件事，两个信号重复还白占 track 的 8 字符宽度，故删除。

> 不要再加回来：车机字库只对 Latin-1 那批符号有字形，箭头 / 三角 / 播放符
> 一律渲染成圆点（见 AGENTS.md 6.8）。

#### 5.10.2 底栏软键：三格 + 计时键

```
┌────────┬────────┬────────┬──────────┐
│ 暂停   │   -    │  计时  │  更多…   │
│ 9001   │ 9002   │ 9003   │ 车机自带 │
└────────┴────────┴────────┴──────────┘
```

- **第 1 格**：播放/暂停（`9001`），文字随播放状态变
- **第 2 格**：**留空，显示 `-`**（`9002`，未绑定动作）
- **第 3 格**：**「计时」（`9003`），文字固定不变**
- **第 4 格**：「更多…」由车机自己画，不是我们下发的

**计时键的三态循环**（`CommandRouter.handleTimerKey`）：

```
未激活 --点--> 正计时开始
走秒中 --点--> 暂停（定格）
暂停中 --点--> 清零
清零后 --点--> 又开始正计时     ← 回到第一态
```

判定依据是 `StateRenderer.isTimerActive()` / `isTimerRunning()`，
所以它同样能管住倒计时（先开机 5 分钟倒计时，按这个键也是"先暂停、再清零"）。

**高亮**：用 SDL 的 `SoftButton.setIsHighlighted(true)`。

> **判据（用户 2026-11-21 明确）**：高亮**完全取决于「媒体时钟格此刻显示的是不是计时器时间」**
> —— 由 `StateRenderer.isTimerOccupyingClock()` 单一入口表达，不另外看
> `timerActive` 之类的旁路标志，避免两套判据再次分叉。
> `ScreenFrame.highlightIndex` 承载，预览里画成琥珀色；`stamp()` 里也包含下标，
> 否则高亮变了不会重发 `Show`。

> ⚠ 软键的**点击回调**只能在车机上验证（`OnButtonPress{CUSTOM_BUTTON, 9003}`），
> 手机端预览是静态 View，点不到。

#### 5.10.3 时钟格优先级与跨焦点存活

**优先级（用户 2026-11-21 确认）**：

```
① 用户计时器（timerActive，含暂停态）   ← 永远优先
② 导航 ETA
③ 播放时间
④ 空（发 CLEAR）
```

> 以前 `songClockRpcList()` 里写的是 `if (timerRunning) return;` —— 只挡住"在跑"的
> 计时器，**暂停态会穿透到 ETA 分支**。后果是导航一开始，暂停中的计时器就被到达时刻
> 覆盖；而 `timerActive` 仍是 true，再点一下计时键会因"暂停中"被判定为清零，
> 时间整个消失。两个现象同一个根因，已改成按 `timerActive` 判断。

**跨焦点存活**：掉出 HMI_FULL 时车机会丢弃定时器（文档 3.4.4.4：FULL/LIMITED 期间
发起的操作会被自动终止），所以手机必须自己记状态。

本地记录：起始值 `timerH/M/S`、方向 `timerCountdown`、起点 `timerStartMillis`、
暂停时刻 `timerPausedAt`、`timerRunning` / `timerActive`。

回到 FULL 时 `SyncPlayCore.pushClockForce()` → `StateRenderer.invalidateClockCache()`
清掉节流缓存 → `timerClockList()` 按本地状态重算读数并重投：

| 状态 | 投递 |
|---|---|
| 在跑 | 一条 `COUNTUP(当前读数)`，车机接着走 |
| 暂停 | **两步**：`COUNTUP(当前读数)` + `PAUSE`（绝不能单发 PAUSE + startTime） |
| 倒计时已走完 | 自动清除本地状态，时钟格回落 ETA / 播放时间 |

> ⚠ `initializeForConnection()` 以前在 `lifecycle.isInitialized() == true` 时直接
> `return` —— 而"从 BACKGROUND 回到 FULL"正好命中这一支，导致时钟补发根本没执行。
> 现在两分支都会强制重推。

节流：`timerClockKey`（`"读数|run/pause"`）保证同一状态只投一次；用户主动操作
（`setTimerState`）和 `invalidateClockCache()` 会清掉它。

---

## 六、权限

| 权限 | 用途 | 授予方式 |
|---|---|---|
| `BLUETOOTH_CONNECT` | AppLink 通信 | 运行时弹窗 |
| `BLUETOOTH_SCAN` | 蓝牙扫描 | 运行时弹窗 |
| `FOREGROUND_SERVICE` | 前台服务（`SdlService` 与 RouterService 都要） | 自动 |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | 前台服务类型（同上） | 自动 |
| `RECEIVE_BOOT_COMPLETED` | 开机自启 | 自动 |
| `INTERNET` | 查歌词 | 自动 |
| `com.autonavi.minimap.permission.NAVIGATION_SERVICE` | AmapLink | 声明即可（prot=normal） |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | 读音乐通知 | **用户去系统设置手动开** |

**不申请** `MEDIA_CONTENT_CONTROL`（签名级，拿不到），
改走通知里的 `EXTRA_MEDIA_SESSION` 拿 `MediaSession.Token`。

---

## 七、屏幕预览

信息页内嵌一块**车机屏幕预览**，用于不上车也能看显示效果。

- 两块独立画布（不切换，同时可见），宽 240dp 模拟 4 寸屏比例
- 蓝色 SYNC 主题（`#1B3A5C`）
- 底栏按**真实软键规则**绘制：第 1 格是我们的软键，第 2/3 格空着显示 `—`，
  第 4 格固定「更多…」

**硬性要求：预览必须与实车行为完全一致。**

因此：
- 歌词分行、指示符、软键内容全部**复用实车同一套计算函数**，不重复实现
- 预览数据必须能**独立于发送流程实时计算**，不能依赖「上次真正发出去时的快照」

> 踩过两次坑：`softKey1` 和 `trackKind` 都只在 `doShow()` 里赋值，
> 没连车机时预览就全空了。改法是把它们改成「按当前状态实时算」。

---

## 八、关键设计决策

| 决策 | 理由 |
|---|---|
| `targetSdk = 32` | 更高会触发 SDL 库内部的 `registerReceiver` 异常（4.12.2 限制；5.8.0 起已提到 35） |
| `Show` 节流 1 秒 | 实测这台车机能 1 秒刷一次；旧文档写的 4 秒是早期限制 |
| `mediaTrack` 单值 | SYNC 协议只有一个字段，两屏共用，无法分设 |
| 状态 D 第 1 屏给导航 | 开车时导航信息最重要，歌词次之 |
| 歌词一行 14 半角 | 实车照片实测：7 个汉字占满一行 |
| 歌词提前 0.5 秒 | 补偿车机 1 秒刷新延迟，否则总慢半拍 |
| 时长不参与缓存 key | 很多播放器不给 `DURATION`，参与会让同名不同版本全撞 |
| 网易云搜索用完整歌名 | 剥括号会搜到同名不同曲 |
| 按键去重按「别名对」 | 按 cmdID 通用去抖会误伤快速连按 |
| 导航 ETA 用定格时刻 | 数据包里就有到达时刻，没必要换算成倒计时 |
| 暂停时计时器仍占屏 | 用户要看定格读数，不是让位给播放进度 |
| 不把网易云拉到前台 | 用户要的是「兜里也能放歌」，不是「打开网易云」 |

---

## 九、不做的事

| 功能 | 原因 |
|---|---|
| 读取车辆数据（转速/车速/油量） | 策略表封锁，`DISALLOWED`；Ford 授权入口已关（2022） |
| 诊断接口（ReadDID / GetDTCs） | 同上 |
| 过滤歌词元信息行（作词/作曲等） | 按需求保留显示 |
| AVRCP 歌词读取 | 普通 app 拿不到 `BluetoothAvrcpController`，且只有单句无时间轴 |
| 高德广播协议 | 手机版高德不发 |
| 自动拉起高德/网易云 | 手动控制更可控 |
| 锁屏界面 | 未实现（Ford 验证项会失败，见待办清单） |

---

## 十、已知限制

| 限制 | 原因 | 能否绕过 |
|---|---|---|
| 无法读车辆数据 | 策略表封锁 | ❌ |
| `mediaTrack` 两屏共用 | SYNC 协议限制 | ❌ |
| 软键能画几个未验证 | `softButtonCapabilities` 只声明 1 项 | 软键测试按钮可探测 |
| 高德必须先运行 | AmapLink 依赖高德进程 | ❌ |
| 日语假名显示为方块 | 车机无日文字库 | ✅ 已转罗马音 |
| 标题栏截断 | 宽度有限 | ✅ 用短名 SyncPlay |
| 罗马音过长被截断 | 假名加空格后宽度翻倍 | ⚠️ 一行 14 半角放不下 |
| `⧗` `▶` `⏸` 字形未验证 | 车机点阵字体可能缺 | ⚠️ 上车确认 |
| 网易云接口随时可能收紧 | 非公开接口 | ⚠️ lrclib 作完整后备 |
| force-stop 后蓝牙拉起失效 | Android stopped state | ❌ 需手动开一次 |
