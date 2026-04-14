plugins {
    id("com.android.library")
}

android {
    namespace = "com.termux.terminal"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        named("main") {
            jni.srcDirs()
            jniLibs.srcDirs()
        }
        named("test") {
            java.srcDirs("src/test/java")
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
