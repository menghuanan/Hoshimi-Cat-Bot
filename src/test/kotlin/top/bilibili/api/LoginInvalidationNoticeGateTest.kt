package top.bilibili.api

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoginInvalidationNoticeGateTest {
    @Test
    fun `ordinary successful api calls should not rearm login invalidation notice`() {
        // 首次登录失效需要通知管理员，之后同一进程内的普通成功接口不能重新打开通知门闩。
        resetLoginInvalidationNoticeForTest()

        assertTrue(shouldNotifyLoginInvalidation())
        assertFalse(shouldNotifyLoginInvalidation())

        markBiliApiRequestSucceeded()

        assertFalse(shouldNotifyLoginInvalidation())
    }

    @Test
    fun `explicit login success should rearm future login invalidation notice`() {
        // /login 成功代表账号状态确实恢复，后续再次失效应当重新提醒一次。
        resetLoginInvalidationNoticeForTest()

        assertTrue(shouldNotifyLoginInvalidation())
        assertFalse(shouldNotifyLoginInvalidation())

        markBiliLoginSucceeded()

        assertTrue(shouldNotifyLoginInvalidation())
    }
}
