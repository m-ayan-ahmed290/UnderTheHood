plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
 id("org.jetbrains.kotlin.plugin.compose")
 id("com.google.devtools.ksp")
}
android {
 namespace = "app.underthehood"
 compileSdk = 35
 defaultConfig {
  applicationId = "app.underthehood"
  minSdk = 29
  targetSdk = 35
  versionCode = 1
  versionName = "0.1.0"
  testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
 }
 buildFeatures { compose = true; buildConfig = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
 packaging { jniLibs { useLegacyPackaging = false; keepDebugSymbols += "**/libuth.so" }; resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1") }
 buildTypes { release { isMinifyEnabled = false } }
 testOptions { unitTests.isReturnDefaultValues = true }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
 implementation(platform("androidx.compose:compose-bom:2025.04.01"))
 implementation("androidx.activity:activity-compose:1.10.1")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.material:material-icons-extended")
 implementation("androidx.compose.ui:ui-tooling-preview")
 debugImplementation("androidx.compose.ui:ui-tooling")
 implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
 implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
 implementation("androidx.room:room-runtime:2.7.1")
 implementation("androidx.room:room-ktx:2.7.1")
 ksp("androidx.room:room-compiler:2.7.1")
 implementation("androidx.work:work-runtime-ktx:2.10.1")
 implementation("androidx.documentfile:documentfile:1.0.1")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
 testImplementation("junit:junit:4.13.2")
 androidTestImplementation("androidx.test.ext:junit:1.2.1")
 androidTestImplementation("androidx.test:runner:1.6.2")
 androidTestImplementation("androidx.test:rules:1.6.1")
 androidTestImplementation("androidx.work:work-testing:2.10.1")
 androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
 androidTestImplementation("androidx.compose.ui:ui-test-junit4")
 debugImplementation("androidx.compose.ui:ui-test-manifest")
}

val verifyBundledEngine by tasks.registering {
 doLast {
  listOf("arm64-v8a", "x86_64").forEach { abi ->
   check(file("src/main/jniLibs/$abi/libuth.so").isFile) {
    "Missing bundled Rclone library for $abi. Run scripts/build-native.sh before packaging."
   }
  }
 }
}
tasks.configureEach {
 if (name == "mergeDebugNativeLibs" || name == "mergeReleaseNativeLibs") dependsOn(verifyBundledEngine)
}
