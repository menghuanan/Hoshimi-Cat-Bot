package top.bilibili.connector

enum class PlatformCapability {
    SEND_MESSAGE,
    SEND_IMAGES,
    REPLY,
    AT_ALL,
    LINK_RESOLVE,
    // 群目录查询的实现能力；应用白名单权限由查询结果进一步表达。
    GROUP_DIRECTORY,
}

data class CapabilityRequest(
    val capability: PlatformCapability,
    val contact: PlatformContact? = null,
    val images: List<ImageSource> = emptyList(),
)
