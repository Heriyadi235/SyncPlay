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
        // targetSdk 35（Android 15）。
        // 4.12.2 时代必须锁 32：库内部用老式两参 registerReceiver，33+ 会抛 SecurityException。
        // sdl_android 5.7.0 起修好了「动态注册广播的 export 标记」(PR #1858)
        // 和「Android 14 前台服务类型」(PR #1860)，升到 5.8.0 后可以正常提到 35。
        targetSdk = 35
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
    testOptions {
        // 单元测试跑在 JVM 上：Romaji 会走 Log2 → android.util.Log，
        // 不打开这个开关，桩方法会抛 "Stub!" 把转写链路打断。
        unitTests.isReturnDefaultValues = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    // 不开 viewBinding：布局里有重复 id 会编译失败，且我们用 findViewById

    packaging {
        resources {
            // ★ kuromoji-core 与 kuromoji-ipadic 各带一份**内容完全相同**的
            //   META-INF/CONTRIBUTORS.md 与 META-INF/LICENSE.md（哈希一致），
            //   AGP 8 遇到重名资源会直接让构建失败。
            //   取第一份即可：许可文件仍然留在 APK 里，不破坏 Apache-2.0 的声明要求
            //   （NOTICE.md 只有 ipadic 带，不冲突，会自动保留）。
            pickFirsts += setOf(
                "META-INF/CONTRIBUTORS.md",
                "META-INF/LICENSE.md",
            )
        }
    }
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

    // ★ 日语分词器（歌词假名→罗马音需要词边界，见 docs/design.md「显示文本转写规范」）
    //   包名是 org.atilika.kuromoji（groupId 才是 com.atilika）；
    //   ipadic 版把 IPADIC 词典（12.7 MiB）直接打进 jar，从 classpath 资源加载，
    //   无需外部文件、无需运行时下载。首次 build() 约 1~2 秒，之后复用。
    implementation("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")

    // 单元测试（只进 testImplementation，不进 APK）
    testImplementation("junit:junit:4.13.2")
}
