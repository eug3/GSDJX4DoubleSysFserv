import java.security.MessageDigest
import java.math.BigInteger

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.guaishoudejia.x4doublesysfserv"
    compileSdk = 35
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.guaishoudejia.x4doublesysfserv"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // 关键：同时支持 32 位 (v7a) 和 64 位 (v8a) 架构
        ndk {
            abiFilters.add("arm64-v8a")
            abiFilters.add("armeabi-v7a")
        }
        
        // CMake 配置 - Native OCR (暂时禁用，等待 PaddleLite 编译完成)
        // 取消注释以下代码以启用 Native C++ OCR
        /*
        externalNativeBuild {
            cmake {
                cppFlags("-std=c++11", "-frtti", "-fexceptions")
                arguments("-DANDROID_PLATFORM=android-23", "-DANDROID_STL=c++_shared", "-DANDROID_ARM_NEON=TRUE")
            }
        }
        */
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        jniLibs {
            // 对于包含 JNI 库的应用，建议开启此项
            useLegacyPackaging = true
        }
    }
    
    // CMake 配置 - 指向 CMakeLists.txt 文件 (暂时禁用)
    // 取消注释以下代码以启用 Native C++ 编译
    /*
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
    */
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // Compose Material icons (extended set for Bluetooth icon)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.okhttp)
    implementation("androidx.browser:browser:1.8.0")
    implementation("org.mozilla.geckoview:geckoview:115.0.20230706202047")
    
    // ONNX Runtime Mobile for PP-OCRv5
    implementation(libs.onnxruntime.android)
    implementation(libs.onnxruntime.extensions.android)

    // PaddleOCR - Paddle-Lite v2.14 (自行编译版本，兼容 NDK 29)
    implementation(files("libs/PaddlePredictor.jar"))

    // OpenCV for DBNet post-processing (contour detection, polygon approximation)
    implementation("org.opencv:opencv:4.9.0")
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// PaddleLite 本地编译版本配置 - 不需要下载
// 编译完成后运行: /Users/beijihu/Github/Paddle-Lite/copy_to_project.sh
// 如需重新下载预编译版本，可取消注释以下代码

/*
// OpenCV 和 PaddleLite 下载配置 - Native C++ 编译需要
// PaddleLite v2.14-rc 从官方 GitHub Release 下载
val archives = listOf(
    mapOf(
        "src" to "https://github.com/PaddlePaddle/Paddle-Lite/releases/download/v2.14-rc/inference_lite_lib.android.armv8.clang.c++_shared.with_extra.with_cv.tar.gz",
        "dest" to "PaddleLite"
    ),
    mapOf(
        "src" to "https://paddlelite-demo.bj.bcebos.com/libs/android/opencv-4.2.0-android-sdk.tar.gz",
        "dest" to "OpenCV"
    )
)

tasks.register("downloadDependencies") {
    doFirst {
        println("正在下载 PaddleLite 和 OpenCV...")
    }
    doLast {
        val cachePath = "cache"
        val cacheDir = file(cachePath)
        if (!cacheDir.mkdirs()) {
            cacheDir.exists()
        }
        
        archives.forEach { archive ->
            val dest = file(archive["dest"] as String)
            val shouldDownload = !dest.exists()
            
            if (shouldDownload) {
                val messageDigest = MessageDigest.getInstance("MD5")
                messageDigest.update((archive["src"] as String).toByteArray())
                val cacheName = BigInteger(1, messageDigest.digest()).toString(32)
                val tarFile = file("$cachePath/$cacheName.tar.gz")
                
                if (!tarFile.exists()) {
                    println("正在从 ${archive["src"]} 下载...")
                    ant.invokeMethod("get", mapOf(
                        "src" to archive["src"],
                        "dest" to tarFile
                    ))
                }
                
                println("正在解压到 ${archive["dest"]}...")
                copy {
                    from(tarTree(tarFile))
                    into(archive["dest"] as String)
                }
                println("${archive["dest"]} 下载完成！")
            } else {
                println("${archive["dest"]} 已存在，跳过下载")
            }
        }
    }
}

tasks.named("preBuild") {
    dependsOn("downloadDependencies")
}
*/