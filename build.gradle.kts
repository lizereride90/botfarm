plugins {
	id("com.android.application") version "8.5.2" apply false
	id("org.jetbrains.kotlin.android") version "2.0.20" apply false
	id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
}

allprojects {
	repositories {
		google()
		mavenCentral()
		maven { url = uri("https://jitpack.io") }
	}
}

group = "io.botfarm"
version = "0.1.0"
