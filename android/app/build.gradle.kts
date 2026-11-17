import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// HOG CarLink: own release key outside the project: %USERPROFILE%\.android-keys\carlink-release.properties
val releaseProps = Properties().apply {
    val f = file("${System.getProperty("user.home")}/.android-keys/carlink-release.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// HOG CarLink: seller build (-Pbrand=<id>). Name, number and logo live outside the public repo:
// ../../brands/<brand>/ (brand.properties + res/), or -PbrandDir=<path>.
val brandId: String? = (project.findProperty("brand") as String?)?.takeIf { it.isNotBlank() }
val brandDir: File? = brandId?.let { id ->
    (project.findProperty("brandDir") as String?)?.let { file(it) } ?: rootProject.file("../../brands/$id")
}
val brandProps = Properties().apply {
    val f = brandDir?.resolve("brand.properties")
    if (brandId != null) {
        require(f != null && f.exists()) { "brand '$brandId': no ${f}" }
        f.reader(Charsets.UTF_8).use { load(it) }
    }
}

android {
    namespace = "com.yegorkapomidorka.headswap"
    compileSdk = 37

    defaultConfig {
        applicationId = brandProps.getProperty("applicationId") ?: "com.hog.carlink"
        minSdk = 29
        targetSdk = 37
        versionCode = 110
        versionName = "4.1.1-hog.39"
        // seller build: its own version line (brand.properties), independent of HOG releases
        brandProps.getProperty("versionCode")?.let { versionCode = it.toInt() }
        brandProps.getProperty("versionName")?.let { versionName = it }
        // «По воздуху» (беспровод): с 2.0.0 включён во всех сборках; выключить для проверки: gradlew assembleRelease -Pwlbeta=false
        buildConfigField("boolean", "WIRELESS_BETA", (project.findProperty("wlbeta") != "false").toString())
        // seller build: its id ("" in HOG); the licence (subscription) is checked only there
        buildConfigField("String", "BRAND", "\"${brandId ?: ""}\"")
    }

    buildFeatures {
        buildConfig = true
        aidl = true
        compose = true
    }

    signingConfigs {
        if (releaseProps.isNotEmpty()) {
            create("release") {
                storeFile = file(releaseProps.getProperty("storeFile"))
                storePassword = releaseProps.getProperty("storePassword")
                keyAlias = releaseProps.getProperty("keyAlias")
                keyPassword = releaseProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "WIRELESS_BETA", "true")
            // same key as release: debug -> release upgrade keeps settings (no uninstall)
            signingConfig = signingConfigs.findByName("release")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
        // smoke-тест перед релизом: debug-сборка с другим applicationId, ставится на телефон рядом с release (настройки release не трогаем)
        create("smoke") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".smoke"
            versionNameSuffix = "-smoke"
            matchingFallbacks += listOf("debug")
        }
    }

    sourceSets {
        // debug-транспорт для эмулятора ГУ (DebugTransport); манифест свой: exported BridgeService как в debug и без <permission> TEMPLATE_RENDERER
        getByName("smoke") {
            kotlin.srcDir("src/debug/java")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // Bouncy Castle из Kadb кладёт одинаковые уведомления в несколько модулей; сохраняем их, а не падаем на дубликатах.
        resources.merges += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Правила диагностики подключения общие с tools/logdiag.py: лежат в tools/, в APK копируются как assets/logdiag_rules.json
abstract class CopyLogdiagRules : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val rules: RegularFileProperty

    @get:OutputDirectory
    abstract val outDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val dir = outDir.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        rules.get().asFile.copyTo(File(dir, "logdiag_rules.json"), overwrite = true)
    }
}

val copyLogdiagRules = tasks.register<CopyLogdiagRules>("copyLogdiagRules") {
    rules.set(rootProject.layout.projectDirectory.file("../tools/logdiag_rules.json"))
}

// Seller build: copies of the string files with "HOG CarLink" replaced by the seller's app name.
abstract class BrandStrings : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val mainRes: DirectoryProperty

    @get:Input
    abstract val appName: Property<String>

    @get:OutputDirectory
    abstract val outDir: DirectoryProperty

    @TaskAction
    fun run() {
        val out = outDir.get().asFile
        out.deleteRecursively()
        val res = mainRes.get().asFile
        res.listFiles { f -> f.isDirectory && f.name.startsWith("values") }.orEmpty().forEach { dir ->
            dir.listFiles { f -> f.name.endsWith(".xml") }.orEmpty().forEach { f ->
                val text = f.readText(Charsets.UTF_8)
                if ("HOG CarLink" in text) {
                    val dst = File(out, "${dir.name}/${f.name}")
                    dst.parentFile.mkdirs()
                    dst.writeText(text.replace("HOG CarLink", appName.get()), Charsets.UTF_8)
                }
            }
        }
    }
}

val brandStrings = brandId?.let {
    tasks.register<BrandStrings>("brandStrings") {
        mainRes.set(layout.projectDirectory.dir("src/main/res"))
        appName.set(brandProps.getProperty("appName") ?: error("brand.properties: appName"))
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyLogdiagRules, CopyLogdiagRules::outDir)
        if (brandStrings != null) {
            variant.sources.res?.addGeneratedSourceDirectory(brandStrings, BrandStrings::outDir)
            variant.sources.res?.addStaticSourceDirectory(brandDir!!.resolve("res").path)
        }
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.8.1")
    // HOG CarLink: аудиотека - точная перемотка по главам в Ogg Opus (MediaPlayer прыгает к границе страницы, это секунды)
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    // HOG: кэш аятов плана заучивания (prayer/HifzCache)
    implementation("androidx.media3:media3-datasource:1.8.0")
    implementation("androidx.media3:media3-database:1.8.0")
    // активация Headswap Desktop с самого телефона: пейринг и подключение к своему adbd по беспроводной отладке
    implementation("com.flyfishxu:kadb:2.1.5")
    // Kadb.pair - suspend; зовём из рабочего потока через runBlocking
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    // лаунчер Headswap Desktop: без material3 и иконок, свой дизайн на foundation
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.activity:activity-compose:1.11.0")
    // HOG CarLink: QR-code of the car Wi-Fi (ui/QrScan). Pure Java, Apache-2.0, no transitive deps; camera is framework Camera2
    implementation("com.google.zxing:core:3.5.3")
    // HOG CarLink: вход Google для поддержки через системный Credential Manager (Apache-2.0); Play Services не тянем
    implementation("androidx.credentials:credentials:1.5.0")
    // свой хост Car App Library для навигаторов рабочего стола (AIDL ICarApp/ICarHost, модели Trip/Template), см. carapp/
    implementation("androidx.car.app:app:1.7.0")
    testImplementation("junit:junit:4.13.2")
    // В unit-тестах android.jar — заглушки (isReturnDefaultValues), настоящий org.json нужен LogDiag
    testImplementation("org.json:json:20240303")
}

// Сертификаты head unit AA (assets/aa/<профиль>/cert.pem, key.pem) не в git, в свежем worktree их нет.
// Подтягиваем из основного checkout (родитель git common dir) или из HEADSWAP_AA_CERTS / -Pheadswap.aaCertsDir.
// Release без них не собираем: APK без сертификатов падает у юзера с «Нет сертификата head unit».
val syncAaCerts = tasks.register("syncAaCerts") {
    val dst = file("src/main/assets/aa")
    val root = projectDir
    val override = System.getenv("HEADSWAP_AA_CERTS") ?: (project.findProperty("headswap.aaCertsDir") as String?)
    val release = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }
    doLast {
        val need = listOf("hur", "jvc").flatMap { p -> listOf("$p/cert.pem", "$p/key.pem") }
        fun missing() = need.filter { !File(dst, it).isFile }
        if (missing().isNotEmpty()) {
            val src = override?.let(::File) ?: try {
                val p = ProcessBuilder("git", "rev-parse", "--path-format=absolute", "--git-common-dir")
                    .directory(root).redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText().trim()
                if (p.waitFor() == 0) File(out).parentFile.resolve("android/app/src/main/assets/aa") else null
            } catch (e: Exception) {
                null
            }
            if (src != null && src.canonicalFile != dst.canonicalFile) {
                for (rel in missing()) {
                    val from = File(src, rel)
                    if (from.isFile) {
                        from.copyTo(File(dst, rel), overwrite = true)
                        logger.lifecycle("syncAaCerts: $rel <- $from")
                    }
                }
            }
        }
        val left = missing()
        if (left.isNotEmpty()) {
            // по-английски: консоль Windows в cp1251/cp866 превращает кириллицу в кашу
            val msg = "AA head unit certs missing in $dst: ${left.joinToString()}. " +
                "Put them there or point HEADSWAP_AA_CERTS / -Pheadswap.aaCertsDir to a directory with them"
            if (release) throw GradleException(msg) else logger.warn("syncAaCerts: $msg")
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(syncAaCerts) }
