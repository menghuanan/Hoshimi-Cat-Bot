package top.bilibili.webui.model

import kotlinx.serialization.Serializable

/**
 * 字段能力描述是后续编辑能力的前置契约；Phase 2 先用它表达只读、脱敏和系统维护边界。
 */
@Serializable
enum class WebUiFieldCapability {
    READ_ONLY,
    MASKED,
    SYSTEM_MANAGED,
}

/**
 * WebUI 配置字段 DTO 只承载展示所需元数据，不暴露底层配置对象本身。
 */
@Serializable
data class WebUiConfigFieldDto(
    val key: String,
    val label: String,
    val value: String,
    val capability: WebUiFieldCapability,
    val editable: Boolean,
)

/**
 * 每个配置文件单独返回自己的快照 DTO，明确保持文件归属边界。
 */
@Serializable
data class WebUiConfigFileDto(
    val sourceFile: String,
    val title: String,
    val fields: List<WebUiConfigFieldDto>,
)
