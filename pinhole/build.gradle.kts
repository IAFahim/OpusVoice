plugins { alias(libs.plugins.kotlin.jvm) }

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(libs.bouncycastle)
    implementation(libs.okhttp)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    systemProperty("pinhole.interop.cases", providers.environmentVariable("PINHOLE_INTEROP_CASES").orElse("").get())
    systemProperty(
        "pinhole.ticket",
        providers.gradleProperty("pinholeTicket")
            .orElse(providers.environmentVariable("PINHOLE_TICKET").orElse(""))
            .get(),
    )
    testLogging {
        events("failed", "skipped")
        showStandardStreams = true
    }
}
