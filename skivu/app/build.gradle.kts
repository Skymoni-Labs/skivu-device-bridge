plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
val phoneUseCore = tasks.register<Sync>("preparePhoneUseCore") {
    from("../../app/src/main/java") {
        include("ai/nidhoggfgg/phoneuse/core/**", "ai/nidhoggfgg/phoneuse/device/**")
        exclude("**/KeepAlive*.kt")
    }
    into(layout.buildDirectory.dir("generated/phoneuse-core"))
}
val phoneUseTests = tasks.register<Sync>("preparePhoneUseTests") {
    from("../../app/src/test/java") {
        include("**/DeviceControllerTest.kt", "**/ActionSafetyTest.kt", "**/ObservationSafetyTest.kt", "**/ConditionalObservationTest.kt", "**/SemanticProjectionTest.kt", "**/DeviceReservationRaceTest.kt")
    }
    into(layout.buildDirectory.dir("generated/phoneuse-tests"))
}
android {
    namespace = "ai.nidhoggfgg.phoneuse"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.skivu.devicebridge"
        minSdk = 30
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
            java.srcDir(layout.buildDirectory.dir("generated/phoneuse-core"))
            res.srcDirs("src/main/res","../../app/src/main/res")
        }
        getByName("test") {
            java.srcDirs("src/test/kotlin")
            java.srcDir(layout.buildDirectory.dir("generated/phoneuse-tests"))
        }
    }
}
dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
tasks.matching { it.name.startsWith("compile") || it.name == "preBuild" }.configureEach { dependsOn(phoneUseCore, phoneUseTests) }
