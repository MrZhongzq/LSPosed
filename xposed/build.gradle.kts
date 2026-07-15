val versionCodeProvider: Provider<String> by rootProject.extra
val versionNameProvider: Provider<String> by rootProject.extra

plugins {
    alias(libs.plugins.agp.lib)
    alias(libs.plugins.kotlin)
    alias(libs.plugins.ktfmt)
}

ktfmt { kotlinLangStyle() }

android {
    namespace = "org.matrix.vector.impl"

    androidResources { enable = false }

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "FRAMEWORK_NAME", """"${rootProject.name}"""")
        buildConfigField("String", "VERSION_NAME", """"${versionCodeProvider.get()}"""")
        buildConfigField("long", "VERSION_CODE", versionCodeProvider.get())
    }

    sourceSets { named("main") { java.srcDirs("src/main/kotlin", "libxposed/api/src/main/java") } }
}

dependencies {
    implementation(projects.external.axml)
    implementation(projects.hiddenapi.bridge)
    implementation(projects.services.daemonService)
    compileOnly(libs.androidx.annotation)
    // Pure libxposed/api 102.0.0 source references @SinceApi / @InternalApi from the
    // separate io.github.libxposed:annotation artifact (the old 100+101 hybrid had
    // these stripped). It is compile-time only, so compileOnly is enough.
    compileOnly("io.github.libxposed:annotation:1.0.0")
    compileOnly(projects.hiddenapi.stubs)
}
