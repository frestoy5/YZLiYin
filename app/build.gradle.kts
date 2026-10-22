plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "top.aryun.yzliyin"
    compileSdk = 36

    defaultConfig {
        applicationId = "top.aryun.yzliyin"
        minSdk = 26
        targetSdk = 35
        // versionCode 由 versionName 去点编码：2.1.01022 → 2101022，
        // major*100万 + minor*10万 + patch（patch 需小于 10 万），保证版本号单调递增；
        // versionName 末尾的 beta 之类后缀只写进 name，不参与 versionCode 计算
        versionCode = 2101022
        versionName = "2.1.01022beta"
        vectorDrawables.useSupportLibrary = true
    }

    // release 用 Android 默认调试证书签名：assembleRelease 直接产出可安装的包，
    // 同时不把任何 keystore/口令放进仓库。要正式发布请换成自己的 keystore。
    val debugKeystore = file("${System.getProperty("user.home")}/.android/debug.keystore")
    if (debugKeystore.exists()) {
        signingConfigs {
            create("release") {
                storeFile = debugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    androidResources {
        generateLocaleConfig = false
    }

    lint {
        // values/ 是中文（本 App 的原生语言，同时充当默认资源），values-ja / values-en
        // 逐屏补齐。没翻译到的 key 有意回落到中文而不是留白——这是设计，不是缺陷；
        // 而 MissingTranslation 默认为 fatal，会让 lintVitalRelease 直接失败。
        disable += "MissingTranslation"
    }
}

dependencies {
    // Xposed 拓展（assets/xposed_init → top.aryun.yzliyin.xposed.HookEntry）。
    // 只在编译期用得到：运行时由 LSPosed 注入 API，打成 implementation 会和框架里的
    // 同名类打架，所以走 compileOnly —— 不会进 APK、也不会被 R8 碰到
    compileOnly("de.robv.android.xposed:api:82")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2026.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // AndroidX
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-service:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.8")

    // 图片加载
    implementation("io.coil-kt.coil3:coil-compose:3.4.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.4.0")

    // 网络
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // 一起听协议层的 JSON 编解码（与 NeriPlayer-LTW 服务端协议字段一一对应）
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    // 网易云接口的 Brotli 响应解码（移植自 NeriPlayer）
    implementation("org.brotli:dec:0.1.2")
    // 网易云扫码登录的二维码生成
    implementation("com.google.zxing:core:3.5.3")

    // 播放器
    implementation("androidx.media3:media3-exoplayer:1.9.0")
    implementation("androidx.media3:media3-datasource:1.9.0")
    implementation("androidx.media3:media3-session:1.9.0")
    implementation("androidx.media3:media3-ui:1.9.0")

    // YouTube（移植自 NeriPlayer fork，版本取 fork 的 gradle/libs.versions.toml）
    // NewPipeExtractor 只在 JitPack 发布（fork 坐标 com.github.TeamNewPipe:NewPipeExtractor:v0.26.4），
    // 依赖它之后代码里的 Java 包名才是 org.schabi.newpipe.extractor.*；settings.gradle.kts 已加 jitpack 仓库
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.4")
    implementation("androidx.webkit:webkit:1.16.0")
    implementation("androidx.javascriptengine:javascriptengine:1.1.0")
    // YouTube 登录态加密存储（EncryptedSharedPreferences / MasterKey）
    implementation("androidx.security:security-crypto:1.1.0")
    // 后台定期轮换 YouTube 会话凭据（YouTubeAuthRotationWorker）
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    // YouTube 直链/HLS 播放（media3 版本与本项目既有 1.9.0 对齐，避免版本分裂）
    implementation("androidx.media3:media3-exoplayer-hls:1.9.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.9.0")
}
