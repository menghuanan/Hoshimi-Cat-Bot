package top.bilibili.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.bilibili.BiliConfig
import top.bilibili.BiliConfigManager

class LoginServiceAtomicCommitTest {
    /** 持久化失败时不得安装候选，也不得修改当前配置中的 Cookie。 */
    @Test
    fun `failed qr credential persistence should leave runtime config unchanged`() {
        val original = BiliConfig().apply { accountConfig.cookie = "old-cookie" }
        BiliConfigManager.installConfigRuntimeSnapshot(original)
        var installed = false

        val committed = LoginService.commitLoginConfig(
            cookie = "new-cookie",
            persistCandidate = { false },
            installCandidate = { installed = true },
        )

        assertFalse(committed)
        assertFalse(installed)
        assertEquals("old-cookie", BiliConfigManager.config.accountConfig.cookie)
    }

    /** 持久化成功后才安装完整候选配置。 */
    @Test
    fun `successful qr credential persistence should install candidate once`() {
        BiliConfigManager.installConfigRuntimeSnapshot(BiliConfig().apply { accountConfig.cookie = "old-cookie" })
        var persistedCookie: String? = null
        var installedCookie: String? = null

        val committed = LoginService.commitLoginConfig(
            cookie = "new-cookie",
            persistCandidate = { candidate -> persistedCookie = candidate.accountConfig.cookie; true },
            installCandidate = { candidate -> installedCookie = candidate.accountConfig.cookie },
        )

        assertTrue(committed)
        assertEquals("new-cookie", persistedCookie)
        assertEquals("new-cookie", installedCookie)
    }

    /** 失效代际的迟到成功在持久化前被拒绝，不能写入任何候选。 */
    @Test
    fun `stale qr generation should not persist credentials`() {
        val stale = LoginService.ActiveQrLoginState(1L, 1L, 2L, "group:1")
        val current = LoginService.ActiveQrLoginState(2L, 3L, 4L, "group:2")
        LoginService.setActiveLoginForTest(current)
        var persisted = false

        val committed = LoginService.commitLoginConfigForGeneration(
            state = stale,
            cookie = "late-cookie",
            persistCandidate = { persisted = true; true },
        )

        assertFalse(committed)
        assertFalse(persisted)
        LoginService.setActiveLoginForTest(null)
    }
}
