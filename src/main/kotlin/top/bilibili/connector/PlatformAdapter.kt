package top.bilibili.connector

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull

interface PlatformAdapter {
    val eventFlow: Flow<PlatformInboundMessage>

    /**
     * 启动底层平台连接与事件分发，供 manager 在完成初始化后显式接通适配器生命周期。
     */
    fun start()

    /**
     * 热切换候选必须在提交前证明真实可用，默认基于平台中立运行态等待连接完成。
     */
    suspend fun awaitReadyForReload(timeoutMillis: Long): Boolean {
        val effectiveTimeout = timeoutMillis.coerceAtLeast(1L)
        return withTimeoutOrNull(effectiveTimeout) {
            while (!runtimeStatus().connected) {
                delay(100L)
            }
            true
        } == true
    }

    /**
     * 统一提供可挂起的停机入口，确保传输层关闭可沿用已有 suspend 生命周期。
     */
    suspend fun stop()

    /**
     * 显式声明当前适配器实现支持的能力集合，供统一 guard 先做实现级筛选。
     */
    fun declaredCapabilities(): Set<PlatformCapability>

    /**
     * 统一返回请求级能力判断结果，避免业务层继续散落 capability 分支。
     */
    suspend fun guardCapability(request: CapabilityRequest): CapabilityGuardResult {
        return CapabilityGuard.evaluate(this, request)
    }

    /**
     * 统一的平台发送入口；业务层只通过平台联系人表达目标。
     */
    suspend fun sendMessage(contact: PlatformContact, message: List<OutgoingPart>): Boolean

    /**
     * 按业务语义判断联系人当前是否具备“可发送消息”的条件，默认沿用可达性结果。
     */
    suspend fun canSendMessage(contact: PlatformContact): Boolean {
        return isContactReachable(contact)
    }

    /**
     * 按业务语义判断当前联系人是否可直接发送指定图片集合，默认沿用基础发送能力。
     */
    suspend fun canSendImages(contact: PlatformContact, images: List<ImageSource>): Boolean {
        return canSendMessage(contact)
    }

    /**
     * 按业务语义判断当前联系人是否支持回复消息，默认沿用基础发送能力。
     */
    suspend fun canReply(contact: PlatformContact): Boolean {
        return canSendMessage(contact)
    }

    /**
     * 暴露适配器当前运行态，供监控、守护与能力判断统一读取连接健康度。
     */
    fun runtimeStatus(): PlatformRuntimeStatus

    /**
     * 统一暴露平台 transport 的运行时资源快照，默认返回空快照避免老实现被迫立刻补齐所有观测字段。
     */
    fun runtimeObservability(): PlatformObservabilitySnapshot {
        return PlatformObservabilitySnapshot.empty("platform adapter does not expose transport observability yet")
    }

    /**
     * 统一判断联系人是否可达，供命令与推送逻辑做显式降级。
     */
    suspend fun isContactReachable(contact: PlatformContact): Boolean

    /**
     * 统一判断某联系人上下文是否支持 @全体。
     */
    suspend fun canAtAll(contact: PlatformContact): Boolean

}
