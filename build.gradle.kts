plugins {
	id("java")
	id("org.jetbrains.kotlin.jvm") version "2.2.21"
	id("org.jetbrains.intellij.platform") version "2.9.0"
}

group = "cn.wuzhizhan.idea.mybatis"
version = "0.0.2"

repositories {
	flatDir {
		dirs("libs")
	}
	mavenCentral()
	intellijPlatform {
		defaultRepositories()
	}
}


// Configure IntelliJ Platform Gradle Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {

	intellijPlatform {
		implementation("org.mybatis.generator:mybatis-generator-core:1.4.2")
		testImplementation("org.junit.jupiter:junit-jupiter-engine:5.14.0")
		testImplementation("org.junit.jupiter:junit-jupiter-api:5.14.0")
		create("IU", "2025.2.5")
		testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
		// Add necessary plugin dependencies for compilation here, example:
		bundledPlugin("com.intellij.java")
		bundledPlugin("com.intellij.database")
		bundledPlugin("com.intellij.spring")
	}
}

intellijPlatform {
	pluginConfiguration {
		ideaVersion {
			sinceBuild = "251"
		}
		changeNotes = """
            Initial version
        """.trimIndent()
	}
}

tasks {
	// Set the JVM compatibility versions
	withType<JavaCompile> {
		sourceCompatibility = "21"
		targetCompatibility = "21"
	}

	// 禁用buildSearchableOptions任务，因为它在buildPlugin过程中失败
	buildSearchableOptions {
		enabled = false
	}
}

kotlin {
	compilerOptions {
		jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
	}
}
