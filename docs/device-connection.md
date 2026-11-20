# 设备连接信息（给新对话用）

> 手机已通过 USB 连接电脑并开启 USB 调试，`adb` 可直接操作。
> 把本文件发给新对话，它就能直接把编译好的 APK 装到手机上。

---

## 一、工具路径

```powershell
# adb（平台工具）
$adb = "C:\Users\<用户名>\AppData\Local\Android\Sdk\platform-tools\adb.exe"

# Android SDK
$env:ANDROID_HOME = "C:\Users\<用户名>\AppData\Local\Android\Sdk"

# JDK（Android Studio 自带，构建时用）
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
```

---

## 二、设备信息

| 项 | 值 |
|---|---|
| **序列号** | `&lt;设备序列号&gt;`（本机实测值已脱敏） |
| **连接状态** | `device`（已授权，可直接操作） |
| 型号 | `&lt;手机型号&gt;`（Motorola 系） |
| 产品代号 | `&lt;产品代号&gt;` |
| Android 版本 | **15**（API 35） |
| 当前用户 | **user 0** |
| transport_id | `2` |

验证命令：
```powershell
& $adb devices -l
# 应输出：<序列号>  device product:<代号> model:<型号> device:<代号> transport_id:N
```

---

## 三、⚠️ 安装流程（必须按这个顺序）

```powershell
$adb = "C:\Users\<用户名>\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$pkg = "com.xigaigai.syncglance"          # SyncPlay
$apk = "<工程目录>\app\build\outputs\apk\debug\app-debug.apk"

# 1. 安装
& $adb install -r $apk

# 2. ★ 必做：清掉「应用分身」副本（一定要在 install 之后！）
& $adb shell pm uninstall --user 900 $pkg

# 3. 授予蓝牙权限（免掉弹窗）
& $adb shell pm grant $pkg android.permission.BLUETOOTH_CONNECT
& $adb shell pm grant $pkg android.permission.BLUETOOTH_SCAN

# 4. 启动
& $adb shell am start -n $pkg/.activity.MainActivity
```

### 为什么第 2 步必做

这台 Motorola 会**自动把通过 adb 安装的应用复制到「应用分身」用户（user 900）**，
导致启动时弹「选择要使用的应用程序」（两个同名图标），
表现完全像"闪退 / 点了没反应"，且 `am start` 会落到 `ResolverActivity`。

**⚠️ 顺序很关键：先 install，再 uninstall --user 900。**
反过来做的话，install 时系统又会立刻复制一份，等于白清。
每次重新安装后都必须再清一次，直到 `pm list packages --user 900` 查不到为止。

**排查命令**：
```powershell
& $adb shell pm list packages --user 900 | Select-String '你的包名'
& $adb shell pm uninstall --user 900 你的包名
```

如果主用户的安装记录也损坏了（`dumpsys package` 里 `User 0: installed=false`）：
```powershell
& $adb shell pm install-existing --user 0 你的包名
```

---

## 四、常用调试命令

```powershell
# 清空并重启应用
& $adb logcat -c
& $adb shell am force-stop $pkg
& $adb shell am start -n $pkg/.activity.MainActivity

# 看日志（按 tag 过滤）
& $adb logcat -d -v brief | Select-String 'AppLinkPro|syncpro'

# 看崩溃堆栈
& $adb logcat -d -v brief | Select-String 'FATAL EXCEPTION' -Context 0,12

# 截图
& $adb shell screencap -p /sdcard/s.png
& $adb pull /sdcard/s.png <工作目录>\artifacts\shot.png
& $adb shell rm /sdcard/s.png

# 检查前台窗口
& $adb shell dumpsys window | Select-String 'mCurrentFocus'

# 检查进程是否活着
& $adb shell pidof $pkg

# 拿界面控件坐标（比猜坐标可靠）
& $adb shell uiautomator dump /sdcard/ui.xml
& $adb pull /sdcard/ui.xml <工作目录>\artifacts\ui.xml
# 解析 text / content-desc 与 bounds="[x1,y1][x2,y2]"，取中点去 input tap
```

---

## 五、已安装的应用

| 包名 | 说明 |
|---|---|
| `com.xigaigai.syncglance` | **SyncPlay**（主力开发对象）← `sdl_android:4.12.2` + Multiplex 传输 |
| `<前身工程包名>` | AppLinkPro（旧，已停用） |
| `<更旧工程包名>` | AppLinkTester（更旧，2013 老 jar） |

---

## 六、构建命令

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cmd /c "cd /d <工程目录> && gradlew.bat :app:assembleDebug --console=plain > <工作目录>\artifacts\build.log 2>&1"
Get-Content <工作目录>\artifacts\build.log | Select-String 'BUILD|error:'
```

产物：`<工程目录>\app\build\outputs\apk\debug\app-debug.apk`

---

## 七、工程位置

| 工程 | 路径 | 说明 |
|---|---|---|
| **SyncGlance** | `<工程目录>` | ← **当前主力**，App 名 SyncPlay |
| AppLinkPro | `<工程目录>-AppLinkPro` | 前身，`sdl_android:4.12.2` + Multiplex 传输 |
| AppLinkTester | `<工程目录>-AppLinkTester` | 旧工程，2013 老 jar，车机搜不到 |
| HelloApplink | `<工程目录>-HelloApplink` | 最小示例 |

> 具体盘符与用户名因人而异，请按自己机器的实际路径替换 `<工程目录>`。

**SyncGlance 的关键配置**（别改错）：
```kotlin
applicationId = "com.xigaigai.syncglance"
compileSdk 37 / minSdk 24 / **targetSdk 35**     // 仅 4.12.2 需锁 32；SDL 5.8.0 起为 35
implementation("com.smartdevicelink:sdl_android:4.12.2")
android.enableJetifier=true                       // gradle.properties 里
主题用 @style/Theme.AppCompat.Light.DarkActionBar // 不能是系统主题
```

**SDL 官方链路（2026-11-20 加入，缺了会「车机连不上」）**：
- `receiver/SyncRouterService` —— 继承库的 `SdlRouterService`，Manifest 里 action 必须是
  `com.smartdevicelink.router.service`。负责监听 RFCOMM + 注册 SDP 记录。
- `receiver/SdlReceiver` —— 继承库的 `SdlBroadcastReceiver`，实现
  `defineLocalSdlRouterClass()` 和 `onSdlEnabled()`。
- `SdlService.onStartCommand` 里调一次 `queryForConnectedService`（应用后启动时补连接）。

---

## 八、已知的启动失败原因对照表

| 现象 | 原因 | 解决 |
|---|---|---|
| 启动弹"选择要使用的应用程序" | 应用分身副本 | `pm uninstall --user 900 $pkg`（必须在 install 之后做） |
| 崩溃：`You need to use a Theme.AppCompat theme` | 主题不对 | 用 `@style/Theme.AppCompat.Light` |
| 崩溃：`RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED` | targetSdk ≥ 33（仅 4.12.2） | 降到 32，或升级到 sdl_android ≥ 5.7.0 |
| 崩溃：`You need to use a Theme...`（另一处） | 同上 | 同上 |
| 应用打开后闪退 | 服务启动异常 | 看 logcat 的 `FATAL EXCEPTION` |
| 主界面正常但 SdlService 不出现 | 多半还是分身副本抢了启动 | 查 `dumpsys activity services <pkg>`，再清一次分身 |
