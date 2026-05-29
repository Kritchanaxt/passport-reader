import org.jetbrains.kotlin.gradle.dsl.JvmTarget
 
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20"
}
 
android {
    namespace = "com.tananaev.passportreader"
    compileSdk = 36
 
    defaultConfig {
        applicationId = "com.tananaev.passportreader"
        minSdk = 23
        targetSdk = 36
        versionCode = 23
        versionName = "3.4"
    }
 
    signingConfigs {
        create("release") {
            val storePath = System.getenv("KEYSTORE_FILE")
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }
 
    flavorDimensions += "default"
    productFlavors {
        create("regular") {
            isDefault = true
            extra["enableCrashlytics"] = false
        }
        create("google")
    }
 
    buildTypes {
        getByName("release") {
            if (System.getenv("KEYSTORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
 
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
 
    packaging {
        resources {
            excludes += listOf("META-INF/LICENSE", "META-INF/NOTICE")
        }
    }
}
 
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}
 
dependencies {
    implementation(libs.material)
    implementation(libs.androidx.core.ktx)
    implementation(libs.materialdatetimepicker)
    implementation(libs.jmrtd)
    implementation(libs.scuba.sc.android)
    implementation(libs.spongycastle.prov)
    implementation(libs.jnbis)
    implementation(libs.bcpkix.jdk15on) // do not update
    implementation(libs.commons.io)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mlkit.text.recognition)

    // Jetpack Compose
    implementation("androidx.activity:activity-compose:1.9.3")
    val composeBom = platform("androidx.compose:compose-bom:2024.11.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // MLKit for Software Auto-framing (Face and Card Bounding Box detection)
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.google.mlkit:object-detection:17.0.2")

    "googleImplementation"(platform(libs.firebase.bom))
    "googleImplementation"(libs.firebase.analytics)
    "googleImplementation"(libs.firebase.crashlytics)
    "googleImplementation"(libs.play.services.ads)
    "googleImplementation"(libs.play.review.ktx)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
}

