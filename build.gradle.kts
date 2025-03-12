plugins {
    groovy // Enables Groovy support for writing QuPath extensions
    id("com.gradleup.shadow") version "8.3.5" // Bundles dependencies into a fat JAR
    id("qupath-conventions") // Applies QuPath's Gradle extension conventions

}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}


// TODO: Configure your extension here (please change the defaults!)
qupathExtension {
    name = "qupath-extension-template"
    group = "io.github.qupath"
    version = "0.1.0-SNAPSHOT"
    description = "A simple QuPath extension"
    automaticModule = "io.github.qupath.extension.template"
}
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "mysql" && requested.name == "mysql-connector-java") {
            useVersion("8.0.33") // Force upgrade
            because("Fixing CVE-2023-22102 vulnerability")
        }
    }
}

// TODO: Define your dependencies here
dependencies {

    // Main dependencies for most QuPath extensions
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)
    shadow(libs.qupath.fxtras)
    // Apache Commons Math dependency (for EuclideanDistance)
    shadow("org.apache.commons:commons-math3:3.6.1")
    implementation("mysql:mysql-connector-java:8.0.33")
    runtimeOnly("mysql:mysql-connector-java:8.0.33")
    // If you aren't using Groovy, this can be removed
    shadow(libs.bundles.groovy)

    // For testing
    testImplementation(libs.bundles.qupath)
    testImplementation(libs.junit)

}
