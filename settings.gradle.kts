// 本文件主要工作流程：
// 1. 在工程初始化最早阶段声明插件解析仓库，保证后续构建脚本能稳定解析插件依赖。
// 2. 固定根工程名称，保证构建产物、IDE 导入结果和发布识别名称保持一致。
pluginManagement {
    repositories {
        // 步骤 1：先检查本地仓库。
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
        // 步骤 2：Kotlin EAP 的 dl.bintray.com 仓库已于 2021 年停止服务，不再参与插件解析。
        // 当前构建固定使用 Kotlin 2.0.0 正式版，只保留上方稳定仓库，避免过期地址拖慢或阻断干净环境构建。
    }
}
rootProject.name = "hoshimi-cat-bot"
