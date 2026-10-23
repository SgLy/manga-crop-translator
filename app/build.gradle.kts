import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath(libs.kotlinpoet)
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
}

// ---------------------------------------------------------------------------
// System prompts live in src/main/java/me/sgly/translator/prompts/*.md (single source of
// truth) and are baked into a generated Kotlin file at build time.
// ---------------------------------------------------------------------------
abstract class GeneratePromptsTask : DefaultTask() {
    @get:InputFiles
    abstract val promptMarkdown: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        // Prompts are text: normalize CRLF (a checkout may use it) and drop the trailing newline.
        fun normalize(text: String) = text.replace("\r\n", "\n").trimEnd('\n')

        fun promptProperty(
            name: String,
            raw: String,
        ) = PropertySpec
            .builder(name, String::class)
            .initializer("%S", normalize(raw))
            .build()

        val markdown = promptMarkdown.files.associateBy { it.name }

        FileSpec
            .builder("me.sgly.translator", "Prompts")
            .addFileComment("Generated from prompts/*.md at build time. Do not edit.")
            .addType(
                TypeSpec
                    .objectBuilder("Prompts")
                    .addModifiers(KModifier.INTERNAL)
                    .addProperty(promptProperty("VISUAL", markdown.getValue("visual.md").readText()))
                    .addProperty(promptProperty("TRANSLATE", markdown.getValue("translate.md").readText()))
                    .build(),
            ).build()
            .writeTo(outputDirectory.get().asFile)
    }
}

val promptsDirectory = layout.projectDirectory.dir("src/main/java/me/sgly/translator/prompts")

val generatePrompts =
    tasks.register<GeneratePromptsTask>("generatePrompts") {
        promptMarkdown.from(promptsDirectory.file("visual.md"))
        promptMarkdown.from(promptsDirectory.file("translate.md"))
        outputDirectory.set(layout.buildDirectory.dir("generated/prompts"))
    }

androidComponents {
    onVariants { variant ->
        variant.sources.java?.addGeneratedSourceDirectory(
            generatePrompts,
            GeneratePromptsTask::outputDirectory,
        )
    }
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.androidResources.localeFilters.addAll(listOf("en-rXA", "ar-rXB"))
    }
}

android {
    namespace = "me.sgly.translator"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "me.sgly.translator"
        minSdk = 35
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    androidResources {
        localeFilters += listOf("en", "zh")
    }

    buildTypes {
        getByName("debug") {
            isPseudoLocalesEnabled = true
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    lint {
        abortOnError = true
        error += listOf("MissingTranslation", "MissingQuantity", "ImpliedQuantity")
        fatal += listOf("ExtraTranslation", "MissingDefaultResource")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

ktlint {
    version = "1.8.0"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.schema.annotations)
    implementation(libs.kotlinx.schema.generator.json)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.org.json)
}
