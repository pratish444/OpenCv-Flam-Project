plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.opencvflam"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.opencvflam"
        minSdk = 26 // Camera2 API requirement
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isJniDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        viewBinding = true
    }
}

// Optional: Copy OpenCV .so files to jniLibs automatically
tasks.register<Copy>("copyOpenCVLibs") {
    description = "Copy OpenCV native libraries to jniLibs"
    val opencvPath = file("${projectDir}/../../opencv-android-sdk/sdk/native/libs")
    from(opencvPath) {
        include("**/*.so")
    }
    into(file("${projectDir}/src/main/jniLibs"))
}

// Ensure OpenCV copy runs before building
tasks.named("preBuild") {
    dependsOn("copyOpenCVLibs")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
