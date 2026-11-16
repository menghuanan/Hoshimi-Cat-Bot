package top.bilibili.webui.service

import kotlinx.serialization.Serializable
import top.bilibili.core.BiliBiliBot
import top.bilibili.service.PlatformGroupDirectoryService

/** 管理面仅映射只读目录及操作结果，应用配置和官方接口响应不进入浏览器。 */
class WebUiGroupDirectoryFacade(private val directory: PlatformGroupDirectoryService = BiliBiliBot.groupDirectory) {
    /** 当前活动 AppID 已由服务过滤；前端只使用代际 token 判断选择是否仍有效。 */
    fun read(): WebUiGroupDirectoryDto {
        val snapshot = directory.snapshot()
        return WebUiGroupDirectoryDto(
            snapshot.enabled, snapshot.context?.generation.orEmpty(), snapshot.context?.connected == true,
            snapshot.nameStatus.name, snapshot.stateStatus.name, snapshot.storageError,
            snapshot.groups.map { WebUiGroupOptionDto(it.subject, it.id, it.name, it.alias, it.joined, it.acceptsProactive, it.observedAt, it.refreshedAt) },
        )
    }

    /** 刷新只是排队，不占用 HTTP 请求等待全部群的外部接口。 */
    fun refresh(generation: String) { directory.requestRefresh(generation) }

    /** 备注先经受管存储提交，再反馈浏览器保存成功。 */
    fun rename(request: WebUiGroupAliasRequest) { directory.rename(request.generation, request.openId, request.alias) }
}

/** 群列表 DTO 明确表示连接、权限与持久化状态，空列表不会掩盖错误。 */
@Serializable
data class WebUiGroupDirectoryDto(
    val enabled: Boolean,
    val generation: String,
    val connected: Boolean,
    val nameStatus: String,
    val stateStatus: String,
    val storageError: String?,
    val items: List<WebUiGroupOptionDto>,
)

/** 展示名与 OpenID 分离，joined=false 仅影响新选择，不自动删除用户已有订阅。 */
@Serializable
data class WebUiGroupOptionDto(
    val subject: String, val openId: String, val name: String, val alias: String,
    val joined: Boolean, val acceptsProactive: Boolean?, val observedAt: Long, val refreshedAt: Long,
)

/** 刷新必须绑定当前页面观察到的活动连接代际。 */
@Serializable
data class WebUiGroupRefreshRequest(val generation: String)

/** 备注是用户维护的显示文本，不修改官方群名称或订阅目标。 */
@Serializable
data class WebUiGroupAliasRequest(val generation: String, val openId: String, val alias: String)
