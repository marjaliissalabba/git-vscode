pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "SpinApp"

include(":core")

// Android-appen bygges bare når Android SDK finnes (f.eks. i GitHub Actions).
// Uten SDK kan kjernelogikken i :core fortsatt bygges og testes.
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null
if (hasAndroidSdk) {
    include(":app")
}
