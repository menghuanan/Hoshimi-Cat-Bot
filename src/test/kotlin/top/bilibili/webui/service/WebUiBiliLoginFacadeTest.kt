package top.bilibili.webui.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import top.bilibili.service.QrLoginCancelResult
import top.bilibili.service.QrLoginPhase
import top.bilibili.service.QrLoginSessionSnapshot
import top.bilibili.service.QrLoginStartResult
import top.bilibili.webui.model.WebUiBiliLoginPhase

class WebUiBiliLoginFacadeTest {
    /** 创建 DTO 只携带 Base64 图片和脱敏会话状态，不透传二维码 URL。 */
    @Test
    fun `created web login should expose base64 only on initial response`() = kotlinx.coroutines.runBlocking {
        val snapshot = QrLoginSessionSnapshot("session-1", QrLoginPhase.WAITING_FOR_SCAN, 181_000L, "等待扫码")
        val facade = WebUiBiliLoginFacade(
            startLogin = { QrLoginStartResult.Started(snapshot, byteArrayOf(1, 2, 3), "https://secret.example/qr") },
            readSnapshot = { snapshot.copy(phase = QrLoginPhase.WAITING_FOR_CONFIRMATION) },
            cancelLogin = { QrLoginCancelResult.CANCELLED },
        )

        val created = assertIs<WebUiBiliLoginStartOutcome.Created>(facade.start())
        assertEquals("AQID", created.session.qrImageBase64)
        assertEquals(WebUiBiliLoginPhase.WAITING_FOR_SCAN, created.session.phase)

        val polled = facade.read("session-1")
        assertEquals(WebUiBiliLoginPhase.WAITING_FOR_CONFIRMATION, polled?.phase)
        assertNull(polled?.qrImageBase64)
    }

    /** 全局占用和取消结果必须保持结构化语义，供路由稳定映射 409 与 200。 */
    @Test
    fun `web login facade should preserve conflict and cancel outcomes`() = kotlinx.coroutines.runBlocking {
        val facade = WebUiBiliLoginFacade(
            startLogin = { QrLoginStartResult.Conflict(QrLoginPhase.WAITING_FOR_SCAN, 73L) },
            readSnapshot = { null },
            cancelLogin = { QrLoginCancelResult.COMMITTING },
        )

        val conflict = assertIs<WebUiBiliLoginStartOutcome.Conflict>(facade.start())
        assertEquals(WebUiBiliLoginPhase.WAITING_FOR_SCAN, conflict.phase)
        assertEquals(73L, conflict.remainingSeconds)
        assertEquals(WebUiBiliLoginCancelOutcome.COMMITTING, facade.cancel("session-1"))
    }

    /** 提交态冲突必须保留明确 phase 且不返回伪造的二维码租约剩余时间。 */
    @Test
    fun `committing conflict should omit retry seconds`() = kotlinx.coroutines.runBlocking {
        val facade = WebUiBiliLoginFacade(
            startLogin = { QrLoginStartResult.Conflict(QrLoginPhase.COMMITTING, null) },
            readSnapshot = { null },
            cancelLogin = { QrLoginCancelResult.COMMITTING },
        )

        val conflict = assertIs<WebUiBiliLoginStartOutcome.Conflict>(facade.start())

        assertEquals(WebUiBiliLoginPhase.COMMITTING, conflict.phase)
        assertNull(conflict.remainingSeconds)
    }
}
