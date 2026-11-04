plugins {
	id("com.android.application")
	id("org.jetbrains.kotlin.android")
	id("org.jetbrains.kotlin.plugin.compose")
}

android {
	namespace = "io.nexus.farm"
	compileSdk = 34

	defaultConfig {
		applicationId = "io.nexus.farm"
		minSdk = 26
		targetSdk = 34
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
			applicationIdSuffix = ".debug"
		}
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}

	kotlinOptions {
		jvmTarget = "17"
	}

	buildFeatures {
		compose = true
	}

	packaging {
		resources {
			excludes += setOf("META-INF/*.kotlin_module", "META-INF/AL2.0", "META-INF/LGPL2.1")
		}
	}
}

dependencies {
	val compose = platform("androidx.compose:compose-bom:2024.09.00")

	implementation(compose)
	implementation("androidx.compose.ui:ui")
	implementation("androidx.compose.ui:ui-graphics")
	implementation("androidx.compose.material3:material3")
	implementation("androidx.compose.material:material-icons-extended")
	implementation("androidx.activity:activity-compose:1.9.2")
	implementation("androidx.core:core-ktx:1.13.1")
	implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
	implementation("androidx.lifecycle:lifecycle-service:2.8.6")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
	implementation("org.apache.commons:commons-compress:1.26.2")

	debugImplementation(compose)
	debugImplementation("androidx.compose.ui:ui-tooling")
}
