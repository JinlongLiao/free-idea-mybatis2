plugins {
	id("java")
	id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "cn.wuzhizhan.idea.mybatis"
version = "0.0.3"

repositories {
	mavenCentral()
	intellijPlatform {
		defaultRepositories()
	}
}


// Configure IntelliJ Platform Gradle Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
	implementation("org.mybatis.generator:mybatis-generator-core:2.0.0")
	testImplementation("org.junit.jupiter:junit-jupiter:5.14.0")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.14.0")

	intellijPlatform {
		intellijIdea("2026.3")
		bundledPlugin("com.intellij.java")
		bundledPlugin("com.intellij.database")
		bundledPlugin("com.intellij.spring")
		bundledModule("intellij.spring.core.analysis.jvm")
		// IDEA 2026.3 将 XML PSI 和 DOM API 移到了独立的内置插件。
		bundledPlugin("com.intellij.xml")
	}
}

intellijPlatform {
	// 构建插件 ZIP 时跳过设置搜索索引及其打包任务。
	buildSearchableOptions = false

	pluginConfiguration {
		ideaVersion {
			sinceBuild = "263"
		}
	}
}

java {
	toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.test {
	useJUnitPlatform()
}
