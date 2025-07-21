import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.util.Properties

buildscript {
    repositories {
        google()
        mavenCentral()
        // Shitpack repo which contains our tools and dependencies
        maven("https://jitpack.io")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        // Cloudstream gradle plugin which makes everything work and builds plugins
        classpath("com.github.recloudstream:gradle:-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) = extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: BaseExtension.() -> Unit) = extensions.getByName<BaseExtension>("android").configuration()

// Function to get secrets from environment variables or provide defaults
fun getSecret(key: String): String {
    return System.getenv(key) ?: when (key) {
        "MOVIEBOX_SECRET_KEY_DEFAULT" -> "default_key_1"
        "MOVIEBOX_SECRET_KEY_ALT" -> "default_key_2"
        "CASTLE_SUFFIX" -> "default_suffix"
        "SIMKL_API" -> "default_simkl_api"
        "MAL_API" -> "default_mal_api"
        else -> "default_value"
    }
}

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        // when running through github workflow, GITHUB_REPOSITORY should contain current repository name
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "user/repo")
    }

    android {
        namespace = "com.example"

        defaultConfig {
            minSdk = 21
            compileSdkVersion(35)
            targetSdk = 35

             // Inject secrets into BuildConfig
            buildConfigField("String", "MOVIEBOX_SECRET_KEY_DEFAULT", "\"${getSecret("MOVIEBOX_SECRET_KEY_DEFAULT")}\"")
            buildConfigField("String", "MOVIEBOX_SECRET_KEY_ALT", "\"${getSecret("MOVIEBOX_SECRET_KEY_ALT")}\"")
            buildConfigField("String", "CASTLE_SUFFIX", "\"${getSecret("CASTLE_SUFFIX")}\"")
            buildConfigField("String", "SIMKL_API", "\"${getSecret("SIMKL_API")}\"")
            buildConfigField("String", "MAL_API", "\"${getSecret("MAL_API")}\"")
            buildConfigField("String", "LIBRARY_PACKAGE_NAME", "\"com.nehal\"")

            
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }

        tasks.withType<KotlinJvmCompile> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_1_8) // Required
                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions"
                )
            }
        }
    }

    dependencies {
        val cloudstream by configurations
        val implementation by configurations

        // Stubs for all cloudstream classes
        cloudstream("com.lagradost:cloudstream3:pre-release")

        implementation(kotlin("stdlib"))
        implementation("com.github.Blatzar:NiceHttp:0.4.13")
        implementation("org.jsoup:jsoup:1.18.3")
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.0")
        implementation("com.squareup.okhttp3:okhttp:4.12.0")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
        implementation("org.mozilla:rhino:1.8.0")
        implementation("com.google.code.gson:gson:2.11.0")

        // These dependencies can include any of those which are added by the app,
        // but you don't need to include any of them if you don't need them.
        // https://github.com/recloudstream/cloudstream/blob/master/app/build.gradle.kts
        implementation(kotlin("stdlib")) // Adds Standard Kotlin Features
        implementation("com.github.Blatzar:NiceHttp:0.4.11") // HTTP Lib
        implementation("org.jsoup:jsoup:1.18.3") // HTML Parser
        // IMPORTANT: Do not bump Jackson above 2.13.1, as newer versions will
        // break compatibility on older Android devices.
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1") // JSON Parser
    }
}

task<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}