import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.gradle.plugin.publish)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.dokka)
    alias(libs.plugins.dokka.javadoc)
    alias(libs.plugins.ktfmt)
    `java-gradle-plugin`
    signing
}

group = "io.github.archivesteak"

version = "0.6.1"

kotlin { jvmToolchain(17) }

ktfmt { kotlinLangStyle() }

// Configure Kotlin compiler options
tasks.withType<KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.addAll(listOf("-opt-in=kotlin.RequiresOptIn", "-Xcontext-receivers"))
    }
}

dependencies {
    // Coroutines
    implementation(libs.kotlinx.coroutines.core)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // HTTP Client
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)

    // SVG to Compose (with automatic transitive dependency resolution)
    implementation(libs.svg.to.compose)

    // Gradle API
    compileOnly(libs.gradle.api)
    compileOnly(libs.kotlin.gradle.plugin)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(gradleTestKit())
}

// Configure Gradle Plugin Portal publication
gradlePlugin {
    website = "https://github.com/archivesteak/SymbolCraft"
    vcsUrl = "https://github.com/archivesteak/SymbolCraft"

    plugins {
        create("symbolcraft") {
            id = "io.github.archivesteak.symbolcraft"
            implementationClass = "io.github.archivesteak.symbolcraft.plugin.SymbolCraftPlugin"
            displayName = "SymbolCraft - Multi-Library Icon Generator"
            description =
                "Generate icons on-demand from multiple libraries (Material Symbols, Bootstrap Icons, etc.) for Compose Multiplatform with smart caching."
            tags =
                listOf("KMP", "Compose-Multiplatform", "material", "icons", "symbols", "generator")
        }
    }
}

// Configure Vanniktech Maven Publish
mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()
    coordinates(group.toString(), "symbolcraft", version.toString())

    pom {
        name.set("SymbolCraft")
        description.set(
            "Generate icons on-demand from multiple libraries (Material Symbols, Bootstrap Icons, etc.) for Compose Multiplatform with smart caching."
        )
        inceptionYear.set("2025")
        url.set("https://github.com/archivesteak/SymbolCraft")

        licenses {
            license {
                name.set("Apache License 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }

        developers {
            developer {
                id.set("archivesteak")
                name.set("archivesteak")
                url.set("https://github.com/archivesteak")
                email.set("archivesteak@gmail.com")
            }
        }

        scm {
            url.set("https://github.com/archivesteak/SymbolCraft")
            connection.set("scm:git:git://github.com/archivesteak/SymbolCraft.git")
            developerConnection.set("scm:git:ssh://git@github.com/archivesteak/SymbolCraft.git")
        }
    }
}

// Configure GitHub Packages publication (no Sonatype namespace verification required)
publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            // Owner/repo can be overridden with -Pgpr.repository=owner/repo;
            // GITHUB_REPOSITORY is set automatically in GitHub Actions.
            val repository =
                (project.findProperty("gpr.repository") as String?)
                    ?: System.getenv("GITHUB_REPOSITORY")
                    ?: "archivesteak/SymbolCraft"
            url = uri("https://maven.pkg.github.com/$repository")
            credentials {
                username =
                    (project.findProperty("gpr.user") as String?) ?: System.getenv("GITHUB_ACTOR")
                password =
                    (project.findProperty("gpr.key") as String?) ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

signing {
    val signingKey = project.findProperty("signingKey") as String? ?: System.getenv("SIGNING_KEY")
    val signingPassword =
        project.findProperty("signingPassword") as String? ?: System.getenv("SIGNING_PASSWORD")

    // Always set isRequired to false
    isRequired = false

    if (!signingKey.isNullOrBlank() && !signingPassword.isNullOrBlank()) {
        useInMemoryPgpKeys(signingKey, signingPassword)

        // Configure signing after all publications are created
        afterEvaluate { sign(publishing.publications) }
    }
}

// Configure test framework
tasks.test {
    useJUnitPlatform()

    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }

    addTestListener(
        object : org.gradle.api.tasks.testing.TestListener {
            override fun beforeSuite(suite: org.gradle.api.tasks.testing.TestDescriptor) = Unit

            override fun beforeTest(test: org.gradle.api.tasks.testing.TestDescriptor) = Unit

            override fun afterTest(
                test: org.gradle.api.tasks.testing.TestDescriptor,
                result: org.gradle.api.tasks.testing.TestResult,
            ) = Unit

            override fun afterSuite(
                suite: org.gradle.api.tasks.testing.TestDescriptor,
                result: org.gradle.api.tasks.testing.TestResult,
            ) {
                if (suite.parent == null) {
                    println(
                        "\nTest Summary: ${result.resultType} | Total: ${result.testCount}, " +
                            "Passed: ${result.successfulTestCount}, Failed: ${result.failedTestCount}, " +
                            "Skipped: ${result.skippedTestCount}"
                    )
                }
            }
        }
    )
}

// Generate sources and javadoc JARs
java {
    withSourcesJar()
    withJavadocJar()
}

tasks.named("check") { dependsOn("ktfmtCheck") }

// Configure javadocJar to use Dokka V2 output
tasks.named<Jar>("javadocJar") {
    dependsOn("dokkaGeneratePublicationJavadoc")
    from(layout.buildDirectory.dir("dokka/javadoc"))
}

// Configure JAR manifest
tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version,
            "Implementation-Vendor" to "archivesteak",
            "Built-By" to System.getProperty("user.name"),
            "Built-JDK" to System.getProperty("java.version"),
            "Built-Gradle" to gradle.gradleVersion,
        )
    }
}
