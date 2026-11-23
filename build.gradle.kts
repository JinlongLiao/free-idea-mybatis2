plugins {
	id("java")
	id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "io.github.jinlongliao.mybatis.workbench"
version = "0.1.1"

repositories {
	mavenCentral()
	intellijPlatform {
		defaultRepositories()
	}
}


val estimatedPlanTestDriver = configurations.create("estimatedPlanTestDriver") {
	isCanBeConsumed = false
	isTransitive = false
}

val sqliteMigrationTestDriver = configurations.create("sqliteMigrationTestDriver") {
	isCanBeConsumed = false
	isTransitive = false
}

// 核心复用相同源码，独立编译时只使用公开 Java 依赖。
val databaseCore = sourceSets.create("databaseCore") {
	java.srcDir("src/main/java")
	java.include(
		"io/github/jinlongliao/mybatis/workbench/database/**",
		"io/github/jinlongliao/mybatis/workbench/model/DbType.java",
		"io/github/jinlongliao/mybatis/workbench/model/GenerationTable.java",
		"org/mybatis/generator/api/intellij/**",
	)
	resources.srcDir("src/main/resources")
	resources.include("messages/WorkbenchBundle*.properties", "database/redis/read-stream-page.lua")
}

val databaseCoreImplementation = configurations.named(databaseCore.implementationConfigurationName)
configurations.implementation {
	extendsFrom(databaseCoreImplementation.get())
}

// Configure IntelliJ Platform Gradle Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
	implementation("org.mybatis.generator:mybatis-generator-core:2.0.0")
	implementation("org.apache.maven.resolver:maven-resolver-supplier:1.9.27") {
		exclude(group = "org.slf4j", module = "slf4j-api")
	}
	implementation("org.apache.maven:maven-settings-builder:3.9.12")
	databaseCoreImplementation("redis.clients:jedis:7.5.3") {
		exclude(group = "org.slf4j", module = "slf4j-api")
	}
	databaseCoreImplementation("org.mongodb:mongodb-driver-sync:5.14.0")
	databaseCoreImplementation("com.google.code.gson:gson:2.13.2")
	databaseCoreImplementation("org.apache.commons:commons-csv:1.14.1")
	// 独立宿主需要日志 API，IDE 插件继续使用宿主提供的版本。
	add(databaseCore.compileOnlyConfigurationName, "org.slf4j:slf4j-api:2.0.18")
	add(databaseCore.runtimeOnlyConfigurationName, "org.slf4j:slf4j-api:2.0.18")
	testImplementation("org.junit.jupiter:junit-jupiter:5.14.0")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.14.0")
	add(estimatedPlanTestDriver.name, "com.h2database:h2:2.5.252")
	add(sqliteMigrationTestDriver.name, "org.xerial:sqlite-jdbc:3.54.1.0")

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

val platform262Only = providers.gradleProperty("workbenchPlatform262Only").map(String::toBoolean).orElse(false)

intellijPlatform {
	// 构建插件 ZIP 时跳过设置搜索索引及其打包任务。
	buildSearchableOptions = false

	pluginConfiguration {
		ideaVersion {
			sinceBuild = "262"
			if (platform262Only.get()) {
				untilBuild = "262.*"
			}
		}
	}
}

// 262 的 XML PSI 已在平台中。专用 ZIP 保留 262 兼容范围，并移除只在 263 存在的插件依赖。
if (platform262Only.get()) {
	tasks.patchPluginXml {
		doLast {
			val descriptor = outputFile.get().asFile
			val xml = descriptor.readText()
			val xmlPluginDependency = """<depends optional="true" config-file="mybatis-xml.xml">com.intellij.xml</depends>"""
			check(xmlPluginDependency in xml) { "未找到需要从 262 插件包移除的独立 XML 插件依赖" }
			descriptor.writeText(xml.replace(xmlPluginDependency, ""))
		}
	}
	tasks.buildPlugin {
		archiveClassifier.set("platform-262")
	}
}

java {
	toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.test {
	useJUnitPlatform()
	inputs.files(estimatedPlanTestDriver)
	systemProperty("workbench.estimatedPlanTestDriver", estimatedPlanTestDriver.singleFile.absolutePath)
	inputs.files(sqliteMigrationTestDriver)
	systemProperty("workbench.sqliteMigrationTestDriver", sqliteMigrationTestDriver.singleFile.absolutePath)
}

val databaseCoreJar = tasks.register<Jar>("databaseCoreJar") {
	description = "构建供其他宿主使用的数据库核心 JAR"
	group = "build"
	archiveClassifier.set("database-core")
	from(databaseCore.output) {
		// IntelliJ Gradle 插件会向源集资源注入描述符，独立 JAR 只保留核心。
		exclude("META-INF/plugin.xml")
	}
}

tasks.register<Zip>("databaseCoreDistribution") {
	description = "打包独立宿主所需的数据库核心及运行依赖"
	group = "build"
	archiveClassifier.set("database-core")
	from(databaseCoreJar)
	into("lib") {
		from(configurations.named(databaseCore.runtimeClasspathConfigurationName))
	}
}
