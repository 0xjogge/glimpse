plugins {
    id("com.diffplug.spotless") version "8.10.4"
    id("com.android.application") version "9.4.1" apply false
    id("com.android.library") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}

spotless {
    kotlin {
        target("**/src/**/*.kt")
        ktfmt("0.65").kotlinlangStyle()
    }
    kotlinGradle {
        target("*.gradle.kts", "app/*.gradle.kts", "core/*.gradle.kts", "engines/*/*.gradle.kts")
        ktfmt("0.65").kotlinlangStyle()
    }
}
