package top.bilibili.core

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import top.bilibili.BiliConfigManager
import top.bilibili.BiliData
import top.bilibili.BiliDataWrapper
import top.bilibili.deepCopy

/**
 * BiliData 运行态唯一事务边界，串行执行深快照、候选修改、持久化与一次性安装。
 *
 * 业务调用方只能修改隔离候选；落盘失败时不会触碰当前运行态，也不需要旧快照回滚。
 */
object BiliDataRuntimeCoordinator {
    private val transactionLock = ReentrantLock()

    /**
     * 获取不共享 live mutable 集合的稳定读取快照，适合轮询和扇出在一轮内复用。
     */
    fun snapshot(): BiliDataWrapper = transactionLock.withLock {
        BiliDataWrapper.deepCopyFrom(BiliData)
    }

    /**
     * 串行构建并提交候选数据，只有校验和原子落盘都成功后才安装运行态。
     *
     * @param validate 候选完整性校验，返回错误文本时拒绝提交
     * @param mutate 仅允许修改传入候选，不得捕获或改写 live BiliData
     */
    fun mutateAndPersist(
        validate: (BiliDataWrapper) -> String? = { null },
        mutate: (BiliDataWrapper) -> Unit,
    ): BiliDataMutationResult = transactionLock.withLock {
        val candidate = BiliDataWrapper.deepCopyFrom(BiliData)
        return@withLock try {
            mutate(candidate)
            validate(candidate)?.let { error -> return@withLock BiliDataMutationResult.Rejected(error) }
            if (BiliConfigManager.saveDataSnapshot(candidate, installAfterSave = true)) {
                BiliDataMutationResult.Committed(candidate.deepCopy())
            } else {
                BiliDataMutationResult.PersistenceFailed("BiliData.yml 原子保存失败")
            }
        } catch (error: Exception) {
            BiliDataMutationResult.Rejected(error.message ?: error::class.simpleName.orEmpty())
        }
    }

    /**
     * 在候选事务中同时计算业务返回值，调用方可保持现有 DTO/文案而不接触 live BiliData。
     */
    fun <T> mutateAndPersistResult(
        persistCandidate: (BiliDataWrapper) -> Boolean = { candidate ->
            BiliConfigManager.saveDataSnapshot(candidate, installAfterSave = false)
        },
        mutate: (BiliDataWrapper) -> T,
    ): BiliDataTransactionResult<T> = transactionLock.withLock {
        val candidate = BiliDataWrapper.deepCopyFrom(BiliData)
        return@withLock try {
            val value = mutate(candidate)
            if (persistCandidate(candidate.deepCopy())) {
                BiliConfigManager.installDataRuntimeSnapshot(candidate)
                BiliDataTransactionResult.Committed(value)
            } else {
                BiliDataTransactionResult.PersistenceFailed("BiliData.yml 原子保存失败")
            }
        } catch (error: Exception) {
            BiliDataTransactionResult.Rejected(error.message ?: error::class.simpleName.orEmpty())
        }
    }

    /**
     * 热重载安装同样经过事务锁，避免覆盖并发聊天命令或 WebUI 已提交的新代际。
     */
    fun installSnapshot(snapshot: BiliDataWrapper) = transactionLock.withLock {
        BiliConfigManager.installDataRuntimeSnapshot(snapshot.deepCopy())
    }
}

/** 带业务返回值的 BiliData 事务结果。 */
sealed interface BiliDataTransactionResult<out T> {
    data class Committed<T>(val value: T) : BiliDataTransactionResult<T>
    data class Rejected(val reason: String) : BiliDataTransactionResult<Nothing>
    data class PersistenceFailed(val reason: String) : BiliDataTransactionResult<Nothing>
}

/** BiliData 候选事务的确定性结果。 */
sealed interface BiliDataMutationResult {
    data class Committed(val snapshot: BiliDataWrapper) : BiliDataMutationResult
    data class Rejected(val reason: String) : BiliDataMutationResult
    data class PersistenceFailed(val reason: String) : BiliDataMutationResult

    val committed: Boolean
        get() = this is Committed
}
