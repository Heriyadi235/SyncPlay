plugins {
    id("com.android.application")
}

android {
    namespace = "com.xigaigai.syncglance"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // ★ SDL 5 迁移测试版：包名加 .sdl5 后缀，与正式版（com.xigaigai.syncglance）并存，
        //   这样同一台手机上可以同时装两个版本做 A/B 对比。
        //   注意：车机侧靠 appName 认应用，所以 SdlService.APP_NAME 也同步改成了 SyncPlay5，
        //   否则两个 APK 会撞 DUPLICATE_NAME。
        applicationId = "com.xigaigai.syncglance.sdl5"
        minSdk = 24
        // ★ 必须是 32 或更低：sdl_android 4.12.2 内部用 registerReceiver(receiver, filter)
        //   老 API，targetSdk 33+ 会抛 SecurityException（缺 RECEIVER_NOT_EXPORTED）
        targetSdk = 32
        versionCode = 1
        versionName = "1.1-sdl5"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    // 不开 viewBinding：布局里有重复 id 会编译失败，且我们用 findViewById
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.10.0")

    // ★ SDL 官方 SDK —— 4.12.2 是实车验证过的版本；
    //   迁移分支换成 5.8.0（proxy 层已移除，必须用 SdlManager）
    implementation("com.smartdevicelink:sdl_android:5.8.0")

    // 高德 Agent SDK（探测依赖是否可解析，暂不启用）
    // implementation("com.amap.lbs.client:amap-agent:1.1.41")
}
