package io.github.aoguai.sesameag.util

import io.github.aoguai.sesameag.hook.AccountSlotRegistry
import io.github.aoguai.sesameag.hook.ApplicationHook
import io.github.aoguai.sesameag.hook.RuntimeIdentityGuard
import io.github.aoguai.sesameag.service.patch.SafeRootShell
import io.github.aoguai.sesameag.util.maps.UserMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 统一工作流执行权限门禁。
 *
 * `hasRoot/hasGrantedRoot` 表示“当前进程已由受支持的 libxposed 运行时注入或实时 Root 可用”。
 * `isExecutionAllowed` 只校验注入状态、运行账号与使用协议，不要求命令服务（Root/Shizuku 执行器）在线。
 *
 * 执行器只服务于界面探针与诊断读日志：业务任务全部通过宿主应用的 RPC 完成，不依赖 Shell。
 * 因此执行器不可用时业务照常运行，仅依赖 Shell 的能力降级。
 */
object WorkflowRootGuard {
    private const val TAG = "WorkflowRootGuard"
    private const val CHECK_CACHE_WINDOW_MS = 3_000L
    private val checkMutex = Mutex()
    private val rootShell = SafeRootShell()

    @Volatile
    private var lastCheckAtMs: Long = 0L

    @Volatile
    private var lastGranted: Boolean = false

    @Volatile
    private var lastLoggedState: Boolean? = null

    @Volatile
    private var lastLoggedExecutorState: Boolean? = null

    fun isExecutionAllowed(): Boolean {
        if (!RuntimeIdentityGuard.isTrustedForExecution() || resolveHookAccessSource() == null) return false
        val userId = UserMap.currentUid?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        logExecutorState()
        return AccountSlotRegistry.isExecutableUser(userId)
    }

    /** 命令服务是否已就绪。业务不再依赖它，仅供需要 Shell 的功能与诊断参考。 */
    fun isExecutorReady(): Boolean =
        CommandUtil.serviceStatus.value is CommandUtil.ServiceStatus.Active

    private fun logExecutorState() {
        val ready = isExecutorReady()
        if (lastLoggedExecutorState == ready) {
            return
        }
        lastLoggedExecutorState = ready
        if (ready) {
            Log.record(TAG, "✅ 命令服务已就绪，需要 Shell 的功能可正常使用")
        } else {
            Log.record(TAG, "ℹ️ 命令服务未就绪，本次以降级模式运行（业务任务不受影响，依赖 Shell 的功能不可用）")
        }
    }

    fun hasGrantedRoot(): Boolean = resolveHookAccessSource() != null || lastGranted

    suspend fun hasRoot(forceRefresh: Boolean = false, reason: String? = null): Boolean {
        val now = System.currentTimeMillis()
        resolveHookAccessSource()?.let { hookSource ->
            lastCheckAtMs = now
            lastGranted = true
            logState(true, reason)
            Log.record(TAG, "✅ 当前进程已完成 $hookSource 注入，允许启动工作流")
            return true
        }

        if (!forceRefresh && now - lastCheckAtMs < CHECK_CACHE_WINDOW_MS) {
            return lastGranted
        }

        return checkMutex.withLock {
            val lockedNow = System.currentTimeMillis()
            resolveHookAccessSource()?.let { hookSource ->
                lastCheckAtMs = lockedNow
                lastGranted = true
                logState(true, reason)
                Log.record(TAG, "✅ 当前进程已完成 $hookSource 注入，允许启动工作流")
                return@withLock true
            }
            if (!forceRefresh && lockedNow - lastCheckAtMs < CHECK_CACHE_WINDOW_MS) {
                return@withLock lastGranted
            }

            val granted = try {
                resolveRootAvailability(lockedNow)
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "检测执行权限失败", t)
                false
            }

            lastCheckAtMs = lockedNow
            lastGranted = granted
            logState(granted, reason)
            granted
        }
    }

    fun invalidate() {
        lastCheckAtMs = 0L
        lastGranted = false
    }

    private suspend fun resolveRootAvailability(nowMs: Long): Boolean {
        if (ApplicationHook.classLoader != null) {
            val frameworkInfo = try {
                ApplicationHook.resolveCurrentFrameworkInfo()
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "当前进程框架识别失败", t)
                null
            }
            if (frameworkInfo != null) {
                Log.record(TAG, "🧩 当前进程框架识别: ${frameworkInfo.displayName}")
                if (ApplicationHook.hasSupportedLibXposedRuntime() &&
                    frameworkInfo.category == ModuleStatus.FrameworkCategory.LSPOSED
                ) {
                    Log.record(TAG, "✅ 检测到当前进程由 ${frameworkInfo.displayName} 注入，允许启动工作流")
                    return true
                }
                Log.record(TAG, "⚠️ 当前进程框架不在 libxposed API 102 支持范围内，继续进行实时 Root 探测")
            }
        } else {
            Log.record(TAG, "⚠️ 当前进程 classLoader 尚未就绪，继续进行实时 Root 探测")
        }

        val hasRoot = try {
            rootShell.isAvailable()
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "实时 Root 探测失败", t)
            false
        }
        Log.record(TAG, "🧪 实时 Root 探测结果: granted=$hasRoot at=$nowMs")
        return hasRoot
    }

    private fun resolveHookAccessSource(): String? {
        ApplicationHook.classLoader ?: return null
        val frameworkInfo = try {
            ApplicationHook.resolveCurrentFrameworkInfo()
        } catch (_: Throwable) {
            return null
        }
        return frameworkInfo.displayName.takeIf {
            ApplicationHook.hasSupportedLibXposedRuntime() &&
                isAllowedHookFramework(frameworkInfo.category)
        }
    }

    private fun isAllowedHookFramework(category: ModuleStatus.FrameworkCategory): Boolean {
        return category == ModuleStatus.FrameworkCategory.LSPOSED
    }

    private fun logState(granted: Boolean, reason: String?) {
        if (lastLoggedState == granted) {
            return
        }
        lastLoggedState = granted

        val suffix = reason?.takeIf { it.isNotBlank() }?.let { " [$it]" }.orEmpty()
        if (granted) {
            Log.record(TAG, "✅ 已检测到可用执行权限，允许启动工作流$suffix")
        } else {
            Log.record(TAG, "⛔ 未检测到可用执行权限，工作流与配置不会生效$suffix")
        }
    }
}

