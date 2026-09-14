plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.shikongbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.shikongbridge"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "0.1.5"
    }

    buildFeatures {
        compose = true
    }

    // 固定调试签名：用仓库里这把 keystore，而不是每台机器自己生成的
    // ~/.android/debug.keystore。签名固定以后换新包可以直接覆盖安装，
    // 不用卸载重装，手机里存的口令和服务器地址都能留着。
    // 口令就是 Android 惯例的 "android"，公开的 —— 它是调试签名，不是正式发布签名。
    // 想用自己的：keytool -genkeypair -keystore app/keystore/debug.keystore \
    //   -storetype PKCS12 -storepass android -keypass android -alias androiddebugkey \
    //   -keyalg RSA -keysize 2048 -validity 10950 -dname "CN=My Debug"
    // 换了 keystore 以后新包和旧包签名不同，手机上要先卸载再装。
    signingConfigs {
        getByName("debug") {
            storeFile = file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    // android.jar 里的 org.json 在单元测试里是空壳（只返回默认值），
    // 想真解析 JSON 得在测试 classpath 上摆一份真的实现。
    testImplementation("org.json:json:20240303")
}
