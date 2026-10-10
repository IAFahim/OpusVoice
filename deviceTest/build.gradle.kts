plugins { alias(libs.plugins.android.application) }

android {
    namespace = "org.pinhole.devicetest"
    compileSdk { version = release(37) }
    defaultConfig {
        applicationId = "org.pinhole.devicetest"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":pinhole"))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.runner)
}
