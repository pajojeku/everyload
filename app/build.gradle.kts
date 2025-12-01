plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.elteam.everyload"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.elteam.everyload"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

val youtubedlAndroid = "0.18.1"

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    // OkHttp for contacting a yt-dlp server
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    // RecyclerView for jobs list
    implementation("androidx.recyclerview:recyclerview:1.3.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation("io.github.junkfood02.youtubedl-android:library:${youtubedlAndroid}")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:${youtubedlAndroid}")
    implementation("io.github.junkfood02.youtubedl-android:aria2c:${youtubedlAndroid}")
}