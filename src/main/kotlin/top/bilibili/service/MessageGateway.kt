package top.bilibili.service

import top.bilibili.connector.OutgoingPart
import top.bilibili.connector.PlatformContact

/**
 * 抽象统一消息发送能力，让上层代码不再区分具体平台适配器。
 */
interface MessageGateway {
    /**
     * 统一的平台联系人发送入口；新逻辑应优先使用它。
     */
    suspend fun sendMessage(contact: PlatformContact, message: List<OutgoingPart>): Boolean

    /**
     * 统一走 capability guard 的发送入口；guard 阻断时只停止当前发送路径。
     */
    suspend fun sendMessageGuarded(contact: PlatformContact, message: List<OutgoingPart>): Boolean {
        return sendMessage(contact, message)
    }

    /**
     * 为管理员通知提供统一入口，避免业务代码自行解析管理员联系人。
     */
    suspend fun sendAdminMessage(message: String): Boolean
}
