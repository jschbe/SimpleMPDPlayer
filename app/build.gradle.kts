plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.github.jschbe.simplempdplayer"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.github.jschbe.simplempdplayer"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        resourceConfigurations += listOf("en", "de", "fr", "it", "es", "uk", "ru")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        create("deviceTest") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".devicetest"
            matchingFallbacks += "debug"
        }
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    testBuildType = "deviceTest"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

abstract class GenerateLegalResources : DefaultTask() {
    @get:InputFile abstract val license: RegularFileProperty
    @get:InputFile abstract val notices: RegularFileProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction fun generate() {
        val raw = outputDirectory.dir("raw").get().asFile.apply { mkdirs() }
        license.get().asFile.copyTo(raw.resolve("gpl_v3.txt"), overwrite = true)
        notices.get().asFile.copyTo(raw.resolve("third_party_notices.txt"), overwrite = true)
    }
}
val generateLegalResources = tasks.register<GenerateLegalResources>("generateLegalResources") {
    license.set(rootProject.layout.projectDirectory.file("LICENSE"))
    notices.set(rootProject.layout.projectDirectory.file("docs/THIRD_PARTY_NOTICES.txt"))
    outputDirectory.set(layout.buildDirectory.dir("generated/legalRes"))
}
androidComponents.onVariants { variant ->
    variant.sources.res?.addGeneratedSourceDirectory(generateLegalResources, GenerateLegalResources::outputDirectory)
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    add("deviceTestImplementation", libs.androidx.compose.ui.test.manifest)
}
