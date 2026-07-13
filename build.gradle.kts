plugins {
    // AGP 9 ships built-in Kotlin; org.jetbrains.kotlin.android must NOT be applied.
    id("com.android.application") version "9.2.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0" apply false
    id("com.google.devtools.ksp") version "2.3.10" apply false
    id("com.google.dagger.hilt.android") version "2.59.2" apply false
    id("com.google.gms.google-services") version "4.4.4" apply false
}
