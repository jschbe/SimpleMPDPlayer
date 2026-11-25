import org.gradle.api.configuration.BuildFeatures
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

abstract class SigningBuildFeatures @Inject constructor(val features: BuildFeatures)

val uploadSigningVariables = listOf(
    "SIMPLEMPDPLAYER_UPLOAD_STORE_FILE",
    "SIMPLEMPDPLAYER_UPLOAD_STORE_PASSWORD",
    "SIMPLEMPDPLAYER_UPLOAD_KEY_ALIAS",
    "SIMPLEMPDPLAYER_UPLOAD_KEY_PASSWORD",
)
val configurationCacheRequested = objects.newInstance<SigningBuildFeatures>()
    .features.configurationCache.requested.getOrElse(false)
// AGP needs the signing values during configuration. Never load them into a cached build.
val uploadSigningValues = if (configurationCacheRequested) emptyMap() else
    uploadSigningVariables.associateWith { providers.environmentVariable(it).orNull?.takeIf(String::isNotEmpty) }
val missingSigningVariables = uploadSigningVariables.filter { uploadSigningValues[it] == null }
val releaseSigningError = when {
    configurationCacheRequested -> "Release signing requires --no-configuration-cache. Use --no-daemon as well."
    missingSigningVariables.isNotEmpty() -> "Missing release signing inputs: ${missingSigningVariables.joinToString()}"
    else -> null
}

abstract class CheckReleaseSigningInputs : DefaultTask() {
    @get:Input @get:Optional abstract val errorMessage: Property<String>

    @TaskAction fun checkInputs() {
        errorMessage.orNull?.let { throw GradleException(it) }
    }
}

val checkReleaseSigningInputs by tasks.registering(CheckReleaseSigningInputs::class) {
    errorMessage.set(releaseSigningError)
}

// Guard artifact production even when an incomplete signing config would otherwise be treated as unsigned.
tasks.configureEach {
    if (name in setOf("packageRelease", "packageReleaseBundle", "signReleaseBundle", "validateSigningRelease")) {
        dependsOn(checkReleaseSigningInputs)
    }
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
        versionName = "1.0.0"
        resourceConfigurations += listOf("en", "de", "fr", "it", "es", "uk", "ru")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("upload") {
            if (releaseSigningError == null) {
                storeFile = file(uploadSigningValues.getValue("SIMPLEMPDPLAYER_UPLOAD_STORE_FILE")!!)
                storePassword = uploadSigningValues.getValue("SIMPLEMPDPLAYER_UPLOAD_STORE_PASSWORD")
                keyAlias = uploadSigningValues.getValue("SIMPLEMPDPLAYER_UPLOAD_KEY_ALIAS")
                keyPassword = uploadSigningValues.getValue("SIMPLEMPDPLAYER_UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        create("deviceTest") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".devicetest"
            matchingFallbacks += "debug"
        }
        release {
            signingConfig = signingConfigs.getByName("upload")
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
