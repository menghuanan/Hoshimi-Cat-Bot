package top.bilibili.service

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoginServiceCoordinatorSourceRegressionTest {
    /** 命令适配器必须复用共享协调器，不得继续持有第二套 B 站取码和轮询状态机。 */
    @Test
    fun `login command should delegate qr lifecycle to shared coordinator`() {
        val source = File("src/main/kotlin/top/bilibili/service/LoginService.kt").readText(Charsets.UTF_8)

        assertTrue(source.contains("QrLoginCoordinator.shared.start"))
        assertTrue(source.contains("QrLoginCoordinator.shared.awaitTerminal"))
        assertTrue(
            source.contains("QrLoginCoordinator.shared.cancel(started.snapshot.sessionId)"),
            "login command should release its shared session when delivery or command orchestration throws",
        )
        assertFalse(source.contains("client.getLoginQrcode()"))
        assertFalse(source.contains("client.loginInfo("))
    }
}
