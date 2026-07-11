package top.bilibili.utils

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * 远程资源网络边界策略，只允许解析到公网地址的 HTTP(S) 目标。
 */
object RemoteResourcePolicy {
    const val MAX_RESPONSE_BYTES: Long = 25L * 1024L * 1024L
    const val MAX_REDIRECTS: Int = 5

    /**
     * 解析并验证 URL 协议、凭据和主机，返回后续逐跳下载使用的规范 URI。
     */
    fun validateUri(rawUrl: String): URI {
        val uri = URI(rawUrl)
        require(uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) {
            "仅允许 HTTP(S) 远程资源"
        }
        require(uri.rawUserInfo == null) { "远程资源 URL 禁止包含用户凭据" }
        require(!uri.host.isNullOrBlank()) { "远程资源 URL 缺少有效主机" }
        return uri
    }

    /**
     * 验证一次 DNS 的全部解析结果；混合公网与非公网结果也必须整体拒绝。
     */
    fun validateResolvedAddresses(host: String, addresses: List<InetAddress>) {
        require(addresses.isNotEmpty()) { "远程主机没有 DNS 解析结果: $host" }
        val blocked = addresses.firstOrNull { address -> !isPublicAddress(address) }
        require(blocked == null) { "远程主机解析到非公网地址: $host -> ${blocked?.hostAddress}" }
    }

    /**
     * 判定地址是否属于可直接访问的公网单播范围，显式覆盖 IPv4 映射 IPv6。
     */
    fun isPublicAddress(address: InetAddress): Boolean {
        val bytes = address.address
        if (address is Inet6Address && isIpv4MappedIpv6(bytes)) {
            return isPublicIpv4(bytes.copyOfRange(12, 16))
        }
        return when (address) {
            is Inet4Address -> isPublicIpv4(bytes)
            is Inet6Address -> isPublicIpv6(bytes)
            else -> false
        }
    }

    /** IPv4 公网单播判定，拒绝特殊用途、文档、基准测试和保留网段。 */
    private fun isPublicIpv4(bytes: ByteArray): Boolean {
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        val third = bytes[2].toInt() and 0xff
        return when {
            first == 0 || first == 10 || first == 127 -> false
            first == 100 && second in 64..127 -> false
            first == 169 && second == 254 -> false
            first == 172 && second in 16..31 -> false
            first == 192 && second == 0 && third == 0 -> false
            first == 192 && second == 0 && third == 2 -> false
            first == 192 && second == 88 && third == 99 -> false
            first == 192 && second == 168 -> false
            first == 198 && second in 18..19 -> false
            first == 198 && second == 51 && third == 100 -> false
            first == 203 && second == 0 && third == 113 -> false
            first >= 224 -> false
            else -> true
        }
    }

    /** IPv6 公网单播判定，拒绝未指定、回环、ULA、链路本地、组播和文档地址。 */
    private fun isPublicIpv6(bytes: ByteArray): Boolean {
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        val isUnspecified = bytes.all { it.toInt() == 0 }
        val isLoopback = bytes.take(15).all { it.toInt() == 0 } && bytes[15].toInt() == 1
        val isDocumentation = first == 0x20 && second == 0x01 &&
            (bytes[2].toInt() and 0xff) == 0x0d && (bytes[3].toInt() and 0xff) == 0xb8
        return !isUnspecified && !isLoopback && first !in 0xfc..0xfd &&
            !(first == 0xfe && second in 0x80..0xbf) && first != 0xff && !isDocumentation
    }

    /** IPv4 映射 IPv6 的前 80 位为零、随后 16 位为一。 */
    private fun isIpv4MappedIpv6(bytes: ByteArray): Boolean {
        return bytes.size == 16 && bytes.take(10).all { it.toInt() == 0 } &&
            bytes[10].toInt() == -1 && bytes[11].toInt() == -1
    }
}
