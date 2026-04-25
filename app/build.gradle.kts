import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.safeguardassistant"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    // AGP 9+: ativar *antes* de resValue no defaultConfig / buildTypes
    buildFeatures {
        resValues = true
        viewBinding = true
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.example.safeguardassistant"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Release (e base): URL vazia. Debug sobrescreve abaixo.
        resValue("string", "ai_inspection_endpoint", "")
        resValue("string", "ai_inspection_vision_endpoint", "")
    }

    buildTypes {
        getByName("debug") {
            buildConfigField("boolean", "ANNOTATE_A11Y_WITH_VIEW_IDS", "true")
            // Para testes: pode baixar para 0.50f; em produção use 0.85f+
            buildConfigField("float", "VISION_TAP_MIN_CONFIDENCE", "0.85f")
            // Emulador: 10.0.2.2. Celular físico (USB/Wi‑Fi/QR): IP do Mac — ver gradle.properties
            val host = (project.findProperty("safeguard.debug.serverHost") as String?)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "10.0.2.2"
            resValue("string", "ai_inspection_endpoint", "http://$host:8787/inspect")
            resValue("string", "ai_inspection_vision_endpoint", "http://$host:8787/inspect-vision")
        }
        release {
            buildConfigField("boolean", "ANNOTATE_A11Y_WITH_VIEW_IDS", "false")
            buildConfigField("float", "VISION_TAP_MIN_CONFIDENCE", "0.85f")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(libs.mlkit.text.recognition)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.google.material)
    implementation(libs.okhttp)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
