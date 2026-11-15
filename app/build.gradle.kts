import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// release 서명 키는 저장소에 올리지 않는다. keystore.properties가 있으면 그 키로, 없으면 debug 키로 서명한다
// (저장소를 내려받은 사람도 바로 release 빌드를 만들어 볼 수 있게).
val keystoreFile = rootProject.file("keystore.properties")
val keystore = Properties().apply { if (keystoreFile.exists()) keystoreFile.inputStream().use(::load) }

android {
    namespace = "com.nunchi.app"
    compileSdk = 35
    // 속도·기어는 Pleos SDK가 안내하는 대로 AAOS CarPropertyManager로 읽는다
    useLibrary("android.car")

    defaultConfig {
        applicationId = "com.nunchi.app"
        minSdk = 28 // Pleos 요구사항
        targetSdk = 34 // Pleos Connect Emulator v2.1.0 = API 34
        versionCode = 3
        versionName = "0.2.1"
    }

    signingConfigs {
        if (keystoreFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("androidx.core:core-ktx:1.15.0")

    testImplementation("junit:junit:4.13.2")
}
