import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Chave de release própria (pasta signing/, fora do git). Sem ela,
// o release usa a chave de debug para continuar compilando.
val releaseKeys = Properties().apply {
    val f = rootProject.file("signing/keystore.properties")
    if (f.exists()) f.reader(Charsets.UTF_8).use { load(it) }
}

android {
    namespace = "app.index.files"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "app.index.files"
        minSdk = 26
        targetSdk = 37
        versionCode = 35
        versionName = "0.25.0"
    }

    signingConfigs {
        if (!releaseKeys.isEmpty) {
            create("release") {
                storeFile = rootProject.file("signing/" + releaseKeys.getProperty("storeFile"))
                storePassword = releaseKeys.getProperty("storePassword")
                keyAlias = releaseKeys.getProperty("keyAlias")
                keyPassword = releaseKeys.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        // Só português (padrão): descarta as ~90 traduções que o AppCompat traz.
        localeFilters += listOf("pt")
    }

    packaging {
        resources {
            // Metadados que não são usados em runtime.
            excludes += listOf("META-INF/*.version", "META-INF/**/*.kotlin_module", "kotlin/**", "DebugProbesKt.bin")
        }
    }

    // Bloco de metadados de dependências (só serve à Play Store; ocupa espaço no APK).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.recyclerview)
    implementation(libs.play.services.auth)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.mlkit.doc.scanner)
}
