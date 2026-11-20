plugins {
    id("com.android.application")
}

android {
    namespace = "com.xigaigai.syncglance"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // 正式包名。4.12.2 的旧实现完整保留在 legacy/sdl4 分支；
        // 主线自 2.0 起换用 sdl_android 5.8.0 底座。
        applicationId = "com.xigaigai.syncglance"
        minSdk = 24
        // ★ 必须是 32 或更低：SDL 库内部用 registerReceiver(receiver, filter)
        //   老式两参 API，targetSdk 33+ 会抛 SecurityException（缺 RECEIVER_NOT_EXPORTED）
        targetSdk = 32
        versionCode = 2
        versionName = "2.0"
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
