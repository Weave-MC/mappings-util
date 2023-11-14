plugins {
    kotlin("jvm")
    id("org.jetbrains.dokka")
    `maven-publish`
}

repositories {
    mavenCentral()
}

group = "com.grappenmaker"
version = "0.1"

kotlin {
    jvmToolchain(8)
    explicitApi()
}

dependencies {
    api("org.ow2.asm:asm:9.4")
    api("org.ow2.asm:asm-commons:9.4")
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
            groupId = "net.weavemc"
            artifactId = "mappings-util"
            version = project.version as String
        }
    }
}