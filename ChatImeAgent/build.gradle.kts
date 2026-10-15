plugins {
    java
}

group = "com.movtery.chatime"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

dependencies {
    implementation("org.ow2.asm:asm:9.8")
    implementation("org.ow2.asm:asm-commons:9.8")
}

tasks.jar {
    archiveFileName.set("chat-ime-agent.jar")
    manifest {
        attributes(
            "Premain-Class" to "com.movtery.chatime.agent.ChatImeAgent",
            "Can-Redefine-Classes" to "false",
            "Can-Retransform-Classes" to "false"
        )
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({
        configurations.runtimeClasspath.files.map { dependency ->
            if (dependency.isDirectory) dependency else zipTree(dependency)
        }
    })
}
