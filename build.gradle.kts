plugins {
    id("java")
    alias(libs.plugins.kotlin)
    alias(libs.plugins.intellijPlatform)
}

group = "com.wlzn"
version = "1.0.3"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmDefault = org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY
    }
}

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

configurations {
    implementation {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }
}

dependencies {
    implementation(libs.aliyun.oss)
    implementation(libs.aws.s3) {
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation(libs.aws.url.connection)
    testImplementation("junit:junit:4.13.2")
    intellijPlatform {
        val localPath = providers.gradleProperty("platformLocalPath").orNull
        if (localPath != null) local(localPath) else intellijIdea(providers.gradleProperty("platformVersion"))
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
        changeNotes = """
            <ul>
                <li>优化文件、Bucket 和传输任务菜单：使用 IDEA 原生动作菜单、现有图标、操作分组及高级操作子菜单。</li>
                <li>新增左侧 Bucket / 多级目录树，按需分页加载并与右侧文件列表、面包屑联动。</li>
                <li>重新整理原生工具栏和深浅主题布局，传输任务支持进度条、状态过滤及行内操作。</li>
                <li>支持同一账号自动访问多个区域，Region / Endpoint 可留空。</li>
                <li>修复配置校验失败后 OK 无法再次点击，以及 OSS 新上传空分片列表导致空指针的问题。</li>
                <li>沿用 WLZN 系列插件固定图标，完善传输异常诊断。</li>
            </ul>
        """.trimIndent()
    }
    buildSearchableOptions = false
    pluginVerification {
        ides {
            val localPath = providers.gradleProperty("platformLocalPath").orNull
            if (localPath != null) local(file(localPath)) else recommended()
        }
    }
}

tasks.wrapper { gradleVersion = providers.gradleProperty("gradleVersion").get() }
