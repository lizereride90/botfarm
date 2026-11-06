plugins {
	id("com.android.application")
	id("org.jetbrains.kotlin.android")
	id("org.jetbrains.kotlin.plugin.compose")
}

android {
	namespace = "io.nexus.farm"
	compileSdk = 36

	defaultConfig {
		// 10 characters on purpose. the termux binaries we unpack have
		// /data/data/com.termux/files/usr compiled in, and this makes our
		// prefix the exact same length, so the patch is a straight byte swap.
		applicationId = "io.botfarm"
		minSdk = 26
		// 28 on purpose. android 10 and up deny execve on files inside the app
		// data dir for anything targeting 29 or higher, and the whole point of
		// this app is running binaries we unpacked into filesDir. same reason
		// termux still ships 28.
		targetSdk = 28
		versionCode = 1
		versionName = "0.1.0"
		ndk {
			abiFilters += listOf("arm64-v8a")
		}
	}

	buildTypes {
		release {
			isMinifyEnabled = true
			isShrinkResources = true
			proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
			signingConfig = signingConfigs.getByName("debug")
		}
		debug {
			// no id suffix. the id length is part of the prefix path the userland
			// binaries were patched to, so both variants have to match it.
			isMinifyEnabled = false
		}
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
		isCoreLibraryDesugaringEnabled = true
	}

	kotlinOptions {
		jvmTarget = "17"
	}

	buildFeatures {
		compose = true
	}

	packaging {
		jniLibs {
			// the manifest asks for extractNativeLibs, so the old packaging
			// layout is the one that matches it.
			useLegacyPackaging = true
		}
		resources {
			excludes += setOf("META-INF/*.kotlin_module", "META-INF/AL2.0", "META-INF/LGPL2.1")
		}
	}

	lint {
		// play wants 33 and up. we want an app that can actually exec the
		// userland, so 28 stays and the store check goes.
		disable += "ExpiredTargetSdkVersion"
		disable += "OldTargetApi"
		// release builds run lintVital only. the full report is noise here.
		checkReleaseBuilds = false
	}
}

dependencies {
	val compose = platform("androidx.compose:compose-bom:2024.09.00")

	implementation(compose)
	implementation("androidx.compose.ui:ui")
	implementation("androidx.compose.ui:ui-graphics")
	implementation("androidx.compose.ui:ui-platform:1.7.0")
	implementation("androidx.compose.material3:material3")
	implementation("androidx.compose.material:material-icons-extended")
	implementation("androidx.activity:activity-compose:1.9.2")
	implementation("androidx.core:core-ktx:1.13.1")
	implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
	implementation("androidx.lifecycle:lifecycle-service:2.8.6")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
	implementation("org.apache.commons:commons-compress:1.26.2")
	implementation("org.tukaani:xz:1.9")

	// embedded termux terminal emulator + view
	implementation(project(":third_party:termux-app:terminal-view"))

	coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:1.1.5")

	debugImplementation(compose)
	debugImplementation("androidx.compose.ui:ui-tooling")
}
