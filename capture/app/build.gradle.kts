plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.kotlin.android)
	alias(libs.plugins.kotlin.compose)
	alias(libs.plugins.kotlin.serialization)
	alias(libs.plugins.ksp)
}

android {
	// Kotlin can't have a package segment named `fun` (it's a keyword), so the
	// code namespace differs from the applicationId. The applicationId is what
	// Android/Play treat as the app's identity, and it matches the Capacitor
	// app so this can ship as an upgrade of the same install.
	namespace = "fartingaround.capture"
	compileSdk = 36

	defaultConfig {
		applicationId = "fun.fartingaround.capture"
		minSdk = 31
		targetSdk = 36
		versionCode = 2
		versionName = "2.0"
	}

	buildTypes {
		release {
			isMinifyEnabled = true
			isShrinkResources = true
			proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
		}
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}
	buildFeatures {
		compose = true
	}

	// Kotlin sources live under src/main/kotlin rather than src/main/java.
	sourceSets["main"].kotlin.srcDir("src/main/kotlin")
}

// Room exports its schema here so migrations can be checked in later.
ksp {
	arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
	compilerOptions {
		jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
	}
}

dependencies {
	implementation(libs.androidx.core.ktx)
	implementation(libs.androidx.activity.compose)
	implementation(libs.kotlinx.coroutines.android)

	implementation(platform(libs.androidx.compose.bom))
	implementation(libs.androidx.compose.ui)
	implementation(libs.androidx.compose.ui.tooling.preview)
	implementation(libs.androidx.compose.material3)
	implementation(libs.androidx.compose.material.icons)
	debugImplementation(libs.androidx.compose.ui.tooling)

	implementation(libs.androidx.navigation.compose)
	implementation(libs.androidx.lifecycle.viewmodel.compose)
	implementation(libs.androidx.lifecycle.runtime.compose)

	implementation(libs.androidx.work.runtime)
	implementation(libs.androidx.datastore.preferences)
	implementation(libs.androidx.exifinterface)
	implementation(libs.androidx.media3.exoplayer)
	implementation(libs.androidx.media3.ui)

	implementation(libs.okhttp)
	implementation(libs.kotlinx.serialization.json)

	implementation(libs.coil.compose)
	implementation(libs.coil.network.okhttp)
	implementation(libs.coil.video)

	// Consume mode: feeds + items live in Room (thousands of rows, queried by feed/unread).
	implementation(libs.androidx.room.runtime)
	implementation(libs.androidx.room.ktx)
	ksp(libs.androidx.room.compiler)

	// Scan a QR code to subscribe. Google's own scanner screen does the camera
	// work, so the app still asks for no camera permission.
	implementation(libs.play.services.code.scanner)
}
