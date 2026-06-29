plugins {
    alias(libs.plugins.kotlin.jvm)
}

// The domain module is pure Kotlin/JVM. It has NO Android dependencies,
// which keeps the business rules framework-agnostic, fast to build, and
// fully unit-testable on the JVM (Clean Architecture: the domain is the
// innermost, most stable layer and depends on nothing outward).

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.javax.inject)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test> {
    useJUnit()
}
