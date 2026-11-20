plugins {
    id("com.android.application")
}

android {
    namespace = "com.xigaigai.syncglance"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.xigaigai.syncglance"
        minSdk = 24
        // ★ 必须是 32 或更低：sdl_android 4.12.2 内部用 registerReceiver(receiver, filter)
        //   老 API，targetSdk 33+ 会抛 SecurityException（缺 RECEIVER_NOT_EXPORTED）
        targetSdk = 32
        versionCode = 1
        versionName = "1.0"
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

    // ★ SDL 官方 SDK —— 实车验证能连上 SYNC 1.1
    implementation("com.smartdevicelink:sdl_android:4.12.2")

    // 高德 Agent SDK（探测依赖是否可解析，暂不启用）
    // implementation("com.amap.lbs.client:amap-agent:1.1.41")
}
