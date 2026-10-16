plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.anttech.smsapp"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.anttech.smsapp"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val releaseStoreFile = providers.gradleProperty("SMS_STORE_FILE")

    signingConfigs {
        if (releaseStoreFile.isPresent) {
            create("release") {
                storeFile = file(releaseStoreFile.get())
                storePassword = providers.gradleProperty("SMS_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("SMS_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("SMS_KEY_PASSWORD").get()
            }
        }
    }

    sourceSets {
        // The exported Room schemas (the KSP room.schemaLocation output) are
        // the migration test harness's input: MigrationTestHelper reads the
        // start/end schema of each migration out of the assets its
        // instrumentation context can see. Robolectric unit tests run against
        // the debug variant and use that variant's merged assets as context
        // assets (test source-set assets are not merged there), so the schemas
        // are wired into the debug asset source set only. Being debug-only,
        // no schema JSON is shipped in the release APK.
        getByName("debug").assets.directories.add("$projectDir/schemas")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release").also {
                // Warn rather than silently produce an unsigned APK. The signing config
                // is intentionally absent in CI/dev builds, but the message makes it
                // obvious when credentials were accidentally omitted from a real release.
                if (it == null) logger.warn(
                    "WARNING: Release signing config not found. " +
                    "Pass -PSMS_STORE_FILE=... to sign the release APK."
                )
            }
        }
    }

    testOptions {
        // Robolectric can only resolve app resources (R.string, R.drawable) when
        // AGP hands it the merged resource table. Without this the ids exist at
        // compile time but throw NotFoundException at runtime, which is what
        // NotificationChannels and the notification action labels need.
        unitTests {
            isIncludeAndroidResources = true

            // Robolectric loads Conscrypt's JNI library by lowercasing the OS
            // name through the JVM default locale. Under a Turkish locale that
            // turns "Linux" into "lınux" (dotless i), the native-library lookup
            // misses, and every Robolectric test dies with UnsatisfiedLinkError.
            // Pinning the test JVM to English keeps the name stable regardless
            // of the developer's shell locale.
            all {
                it.systemProperty("user.language", "en")
                it.systemProperty("user.country", "US")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.google.play.services.auth)

    implementation(libs.coil.compose)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.androidx.core.testing)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
