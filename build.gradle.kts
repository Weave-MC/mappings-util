plugins {
    kotlin("plugin.serialization")
    kotlin("jvm")
    id("org.jetbrains.dokka")
    `maven-publish`
}

repositories {
    mavenCentral()
}

group = "com.grappenmaker"
version = "0.2.0"

kotlin {
    jvmToolchain(8)
}

dependencies {
    api("org.ow2.asm:asm:9.4")
    api("org.ow2.asm:asm-commons:9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.1")
}

publishing {
    repositories {
        maven {
            name = "WeaveMC"
            url = uri("https://repo.weavemc.dev/releases")
            credentials(PasswordCredentials::class)
            authentication {
                create<BasicAuthentication>("basic")
            }
        }
    }
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = "com.grappenmaker"
            artifactId = "mappings-util"
            version = project.version as String
        }
    }
}