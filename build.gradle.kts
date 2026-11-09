buildscript {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
        classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.0.21")
        if (System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null) {
            classpath("com.android.tools.build:gradle:8.7.3")
        }
    }
}
