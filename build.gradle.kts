plugins {
    id("com.android.application") version "9.2.0" apply false
    // AGP 9 compiles Android Kotlin sources with its built-in Kotlin support.
    // Do not apply org.jetbrains.kotlin.android beside it.
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    id("com.google.devtools.ksp") version "2.3.10" apply false
    id("com.google.dagger.hilt.android") version "2.60.1" apply false
}
