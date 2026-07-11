package top.bilibili.service

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlatformCapabilitySourceRegressionTest {
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    @Test
    fun `platform capability service should expose business intent helpers`() {
        val source = read("src/main/kotlin/top/bilibili/connector/PlatformCapabilityService.kt")

        listOf(
            "suspend fun canSendMessageTo(",
            "suspend fun canSendManagedAdminNotice(",
            "suspend fun canSendImagesTo(",
            "suspend fun canReplyInContact(",
            "suspend fun canAtAllInContact(",
        ).forEach { marker ->
            assertTrue(source.contains(marker), "PlatformCapabilityService should expose $marker")
        }
    }

    @Test
    fun `admin notice helper should belong to service layer`() {
        val utilitySource = read("src/main/kotlin/top/bilibili/utils/General.kt")
        val serviceSource = read("src/main/kotlin/top/bilibili/service/AdminNoticeService.kt")

        assertFalse(utilitySource.contains("actionNotify("))
        assertFalse(utilitySource.contains("MessageGatewayProvider"))
        assertFalse(utilitySource.contains("PlatformCapabilityService"))
        assertTrue(serviceSource.contains("PlatformCapabilityService.canSendManagedAdminNotice"))
        assertTrue(serviceSource.contains("MessageGatewayProvider.require().sendAdminMessage"))
    }

    @Test
    fun `platform neutral boundaries should not expose legacy numeric contact helpers`() {
        val adapterSource = read("src/main/kotlin/top/bilibili/connector/PlatformAdapter.kt")
        val gatewaySource = read("src/main/kotlin/top/bilibili/service/MessageGateway.kt")
        val botSource = read("src/main/kotlin/top/bilibili/core/BiliBiliBot.kt")
        val capabilitySource = read("src/main/kotlin/top/bilibili/connector/PlatformCapabilityService.kt")
        val managerSource = read("src/main/kotlin/top/bilibili/connector/PlatformConnectorManager.kt")

        listOf(adapterSource, gatewaySource, botSource, capabilitySource, managerSource).forEach { source ->
            assertFalse(source.contains("sendGroupMessage(groupId: Long"))
            assertFalse(source.contains("sendPrivateMessage(userId: Long"))
            assertFalse(source.contains("isGroupReachable(groupId: Long"))
            assertFalse(source.contains("canAtAllInGroup(groupId: Long"))
            assertFalse(source.contains("canAtAll(groupId: Long"))
        }
    }

    @Test
    fun `subscription command should validate explicit target through contact capability`() {
        val source = read("src/main/kotlin/top/bilibili/service/SubscriptionCommandService.kt")

        assertTrue(source.contains("PlatformCapabilityService.canSendMessageTo(target)"))
        assertFalse(source.contains("PlatformCapabilityService.isContactReachable(target)"))
    }
}
