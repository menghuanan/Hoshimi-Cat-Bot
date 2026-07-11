package top.bilibili.utils

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RemoteResourcePolicyTest {
    /** 公网 IPv4 可通过，私网、回环、链路本地和文档地址必须拒绝。 */
    @Test
    fun `address policy should only allow public unicast addresses`() {
        assertTrue(RemoteResourcePolicy.isPublicAddress(InetAddress.getByName("8.8.8.8")))
        listOf("127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.1.1", "169.254.1.1", "203.0.113.1", "::1", "fc00::1", "fe80::1")
            .forEach { address -> assertFalse(RemoteResourcePolicy.isPublicAddress(InetAddress.getByName(address)), address) }
    }

    /** DNS 混合结果包含一个非公网地址时必须整体拒绝，不能让连接器随机命中危险目标。 */
    @Test
    fun `mixed dns results should be rejected`() {
        assertFailsWith<IllegalArgumentException> {
            RemoteResourcePolicy.validateResolvedAddresses(
                "example.test",
                listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("127.0.0.1")),
            )
        }
    }

    /** URL 入口仅允许无用户凭据的 HTTP(S) 目标。 */
    @Test
    fun `uri validation should reject unsupported schemes and credentials`() {
        assertFailsWith<IllegalArgumentException> { RemoteResourcePolicy.validateUri("file:///tmp/a.png") }
        assertFailsWith<IllegalArgumentException> { RemoteResourcePolicy.validateUri("https://user:pass@example.com/a.png") }
    }
}
