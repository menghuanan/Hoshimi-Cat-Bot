package top.bilibili.service

import top.bilibili.Bangumi
import top.bilibili.api.followPgc
import top.bilibili.api.getEpisodeInfo
import top.bilibili.api.getMediaInfo
import top.bilibili.api.getSeasonInfo
import top.bilibili.utils.normalizeContactSubject
import top.bilibili.core.BiliDataRuntimeCoordinator

val pgcRegex = """^((?:ss)|(?:md)|(?:ep))(\d{4,10})$""".toRegex()

/**
 * 统一封装番剧订阅与取消订阅流程，避免命令层分别处理 ss、md、ep 三种入口。
 */
object PgcService {
    /**
     * 解析番剧标识并分发到对应订阅实现，保持命令层只需传入原始 ID。
     */
    suspend fun followPgc(id: String, subject: String): String {
        val regex = pgcRegex.find(id) ?: return "ID 格式错误 例(ss11111, md22222, ep33333)"
        val normalizedSubject = normalizeContactSubject(subject) ?: subject

        val type = regex.destructured.component1()
        val parsedId = regex.destructured.component2().toLong()

        return when (type) {
            "ss" -> followPgcBySsid(parsedId, normalizedSubject)
            "md" -> followPgcByMdid(parsedId, normalizedSubject)
            "ep" -> followPgcByEpid(parsedId, normalizedSubject)
            else -> "额(⊙﹏⊙)"
        }
    }

    /**
     * 通过 season id 订阅番剧，并在首次命中时补齐本地番剧元数据。
     */
    suspend fun followPgcBySsid(ssid: Long, subject: String): String {
        client.followPgc(ssid) ?: return "追番失败"
        val existing = BiliDataRuntimeCoordinator.snapshot().bangumi[ssid]
        val season = if (existing == null) client.getSeasonInfo(ssid) ?: return "获取番剧信息失败，如果是港澳台番剧请用 media id (md11111) 订阅" else null
        val title = existing?.title ?: requireNotNull(season).title
        val result = BiliDataRuntimeCoordinator.mutateAndPersist { candidate ->
            candidate.bangumi.getOrPut(ssid) { Bangumi(requireNotNull(season).title, season.seasonId, season.mediaId, type(season.type)) }.contacts.add(subject)
        }
        return if (result.committed) "追番成功 [$title]" else "保存失败，追番未生效，请稍后重试"
    }

    /**
     * 通过 media id 订阅番剧，先解析出 season id 再复用统一落库逻辑。
     */
    suspend fun followPgcByMdid(mdid: Long, subject: String): String {
        val season = client.getMediaInfo(mdid) ?: return "获取番剧信息失败"
        val ssid = season.media.seasonId
        client.followPgc(ssid) ?: return "追番失败"
        val result = BiliDataRuntimeCoordinator.mutateAndPersist { candidate ->
            candidate.bangumi.getOrPut(ssid) { Bangumi(season.media.title, ssid, season.media.mediaId, season.media.typeName) }.contacts.add(subject)
        }
        return if (result.committed) "追番成功 [${season.media.title}]" else "保存失败，追番未生效，请稍后重试"
    }

    /**
     * 通过 episode id 订阅番剧，兼容用户直接粘贴 ep 链接的场景。
     */
    suspend fun followPgcByEpid(epid: Long, subject: String): String {
        val season = client.getEpisodeInfo(epid) ?: return "获取番剧信息失败，如果是港澳台番剧请用 media id (md11111) 订阅"
        client.followPgc(season.seasonId) ?: return "追番失败"
        val result = BiliDataRuntimeCoordinator.mutateAndPersist { candidate ->
            candidate.bangumi.getOrPut(season.seasonId) { Bangumi(season.title, season.seasonId, season.mediaId, type(season.type)) }.contacts.add(subject)
        }
        return if (result.committed) "追番成功 [${season.title}]" else "保存失败，追番未生效，请稍后重试"
    }

    /**
     * 解析番剧标识并删除指定会话的订阅绑定，在空订阅时同步回收条目。
     */
    suspend fun delPgc(id: String, subject: String): String {
        val regex = pgcRegex.find(id) ?: return "ID 格式错误 例(ss11111, md22222, ep33333)"
        val normalizedSubject = normalizeContactSubject(subject) ?: subject

        val type = regex.destructured.component1()
        val parsedId = regex.destructured.component2().toLong()

        return when (type) {
            "ss" -> removeBySsid(parsedId, normalizedSubject)
            "md" -> {
                val pgc = BiliDataRuntimeCoordinator.snapshot().bangumi.filter { it.value.mediaId == parsedId }.values
                if (pgc.isEmpty()) return "没有这个番剧哦"
                if (normalizedSubject in pgc.first().contacts) {
                    val seasonId = pgc.first().seasonId
                    val result = BiliDataRuntimeCoordinator.mutateAndPersist { candidate ->
                        candidate.bangumi[seasonId]?.contacts?.remove(normalizedSubject)
                        if (candidate.bangumi[seasonId]?.contacts.isNullOrEmpty()) candidate.bangumi.remove(seasonId)
                    }
                    if (result.committed) "删除成功" else "保存失败，订阅未变更，请稍后重试"
                } else {
                    "没有订阅这个番剧哦"
                }
            }
            "ep" -> {
                val season = client.getEpisodeInfo(parsedId) ?: return "获取番剧信息失败"
                removeBySsid(season.seasonId, normalizedSubject)
            }
            else -> "额(⊙﹏⊙)"
        }
    }

    private fun removeBySsid(ssid: Long, subject: String): String {
        val pgc = BiliDataRuntimeCoordinator.snapshot().bangumi[ssid] ?: return "没有这个番剧哦"
        if (subject in pgc.contacts) {
            val result = BiliDataRuntimeCoordinator.mutateAndPersist { candidate ->
                candidate.bangumi[ssid]?.contacts?.remove(subject)
                if (candidate.bangumi[ssid]?.contacts.isNullOrEmpty()) candidate.bangumi.remove(ssid)
            }
            return if (result.committed) "删除成功" else "保存失败，订阅未变更，请稍后重试"
        }
        return "没有订阅这个番剧哦"
    }

    /**
     * 将番剧类型编号收口成人类可读文本，避免展示层散落魔法数字。
     */
    fun type(type: Int) = when (type) {
        1 -> "番剧"
        2 -> "电影"
        3 -> "纪录片"
        4 -> "国创"
        5 -> "电视剧"
        7 -> "综艺"
        else -> "未知"
    }
}
