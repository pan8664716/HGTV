// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.hgtv.star.core.LibraryBackup
import com.hgtv.star.core.LibraryDisk
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Phone / TV 共用的卸载重装恢复入口。
 *
 * 启动时先申请权限、读取公共快照并恢复；权限被拒时本次只写私有快照。
 * 如果用户之后才授权，旧公共快照会被丢弃，当前本机数据会覆盖公共快照。
 * 公共快照统一为动态解析的 `Download/HGTV/hgtv-recovery.json`，直接文件优先，
 * Android 10+ 由 MediaStore 补登记或兜底。
 */
object RecoverySnapshotManager {
    const val MAX_BYTES = 8 * 1024 * 1024
    private const val STATE_PREFS = "recovery-state-v1"
    private const val KEY_INITIALIZED = "initialized"
    private const val KEY_SKIPPED_PUBLIC = "skipped-public"
    private const val KEY_LEGACY_MIGRATION_DONE = "legacy-migration-done"
    private const val KEY_LEGACY_CLEANUP_PENDING = "legacy-cleanup-pending"
    private const val LIBRARY_FILES = "library"

    private val prefFiles = listOf(
        "native-session-v1",
        "native-library-v1",
        "native-theme-v1",
        "phone-display-v1",
        "phone-danmaku-v1",
        "first-run-ui-v1",
        "native-account-cache-v1",
    )

    private val lock = Any()
    private val migrationLock = Any()
    private val writeLock = Any()
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "hgtv-recovery").apply { isDaemon = true }
    }
    private var scheduled: ScheduledFuture<*>? = null
    @Volatile private var initialized = false
    @Volatile private var restoring = false

    fun isInitialized(): Boolean = initialized

    /**
     * 启动恢复。必须在任何 Library / SessionBootstrap 初始化之前调用。
     * 返回是否真的从公共旧快照恢复了数据。
     */
    fun restoreAtLaunch(context: Context, publicPermissionGranted: Boolean): Boolean {
        if (LocalReset.resetting) return false
        val app = context.applicationContext
        val store = RecoverySnapshotStore(app)
        val state = statePrefs(app)
        val firstDecision = !state.getBoolean(KEY_INITIALIZED, false)
        val skippedPublic = state.getBoolean(KEY_SKIPPED_PUBLIC, false)
        var restored = false

        if (firstDecision) {
            if (publicPermissionGranted && RecoveryPermissions.isStorageMounted()) {
                restored = readBest(store.readPublicCandidates())?.let { snapshot ->
                    applySnapshot(app, snapshot)
                    true
                } ?: false
            }
            state.edit()
                .putBoolean(KEY_INITIALIZED, true)
                .putBoolean(KEY_SKIPPED_PUBLIC, !publicPermissionGranted)
                .commit()
        }

        // 必须先恢复已有的完整快照，再执行旧版迁移。
        // 反过来会在重装后的首次启动把空快照写到公共目录，覆盖真正的旧快照。
        migrateLegacyIfNeeded(app, state, publicPermissionGranted)

        if (!firstDecision && skippedPublic && publicPermissionGranted) {
            // 本次安装已经以「无公共权限」启动过：旧公共快照不再可信，
            // 当前数据才是用户此刻看到的数据。
            store.deletePublic()
            state.edit().putBoolean(KEY_SKIPPED_PUBLIC, false).commit()
        }

        initialized = true
        schedule(app, 0L)
        Log.w(
            "HGTV-RECOVERY",
            "launch restore granted=$publicPermissionGranted restored=$restored " +
                "skippedPublic=${state.getBoolean(KEY_SKIPPED_PUBLIC, false)}",
        )
        return restored
    }

    /** 权限回调、回到前台或设置页授权后调用。 */
    fun onPermissionChanged(context: Context, granted: Boolean) {
        // 首次恢复尚未完成时由 restoreAtLaunch 统一处理，避免权限回调抢先写入空快照。
        if (!initialized) return
        val app = context.applicationContext
        val state = statePrefs(app)
        Log.w(
            "HGTV-RECOVERY",
            "permission changed granted=$granted " +
                "migrationDone=${state.getBoolean(KEY_LEGACY_MIGRATION_DONE, false)} " +
                "cleanupPending=${state.getBoolean(KEY_LEGACY_CLEANUP_PENDING, false)}",
        )
        migrateLegacyIfNeeded(app, state, granted)
        if (granted && state.getBoolean(KEY_SKIPPED_PUBLIC, false)) {
            RecoverySnapshotStore(app).deletePublic()
            state.edit().putBoolean(KEY_SKIPPED_PUBLIC, false).commit()
        }
        schedule(app, 0L)
    }

    /** 从系统设置返回时补一次状态检查；权限没有变化就不重复写快照。 */
    fun onForeground(context: Context) {
        val app = context.applicationContext
        val granted = RecoveryPermissions.hasPublicAccess(app)
        if (granted) {
            val state = statePrefs(app)
            if (!state.getBoolean(KEY_LEGACY_MIGRATION_DONE, false) ||
                state.getBoolean(KEY_LEGACY_CLEANUP_PENDING, false) ||
                state.getBoolean(KEY_SKIPPED_PUBLIC, false)
            ) {
                onPermissionChanged(app, true)
            }
        }
    }

    /** 用户数据变化后的防抖保存；调用方不需要等待磁盘。 */
    fun schedule(context: Context, delayMillis: Long = 2_000L) {
        val app = context.applicationContext
        if (!initialized || restoring || LocalReset.resetting) return
        synchronized(lock) {
            scheduled?.cancel(false)
            scheduled = executor.schedule(
                { writeNow(app) },
                delayMillis.coerceAtLeast(0L),
                TimeUnit.MILLISECONDS,
            )
        }
    }

    /** 手动导出使用同一份完整快照。 */
    fun exportText(context: Context): String? =
        capture(context.applicationContext)?.let(RecoverySnapshotCodec::encode)

    /** 手动导入：覆盖当前本机数据，调用方随后必须重启进程。 */
    fun importText(context: Context, text: String): Boolean = runCatching {
        val app = context.applicationContext
        if (runCatching { JSONObject(text).optString("format") == "hgtv.library" }
                .getOrDefault(false)
        ) {
            return@runCatching importLegacyLibrary(app, text)
        }
        val snapshot = RecoverySnapshotCodec.decode(text)
        applySnapshot(app, snapshot)
        statePrefs(app).edit()
            .putBoolean(KEY_INITIALIZED, true)
            .putBoolean(KEY_SKIPPED_PUBLIC, false)
            .commit()
        initialized = true
        val store=RecoverySnapshotStore(app)
        store.writePrivate(text)
        if(RecoveryPermissions.hasPublicAccess(app)) store.writePublic(text)
        true
    }.getOrElse {
        Log.w("HGTV-RECOVERY", "manual import failed", it)
        false
    }

    private fun importLegacyLibrary(app: Context, text: String): Boolean {
        val backup = LibraryBackup.decode(text)
        val library = AppRuntime.library(app)
        check(library.isLoaded) { "旧备份导入前本机书库尚未加载" }
        library.restore(backup, restoreSettings = true)
        library.flushBlocking(3_000L)
        statePrefs(app).edit()
            .putBoolean(KEY_INITIALIZED, true)
            .putBoolean(KEY_SKIPPED_PUBLIC, false)
            .commit()
        initialized = true
        writeNow(app)
        return true
    }

    /** 应用内清空数据：公共、私有快照和恢复决策一起删除。 */
    fun deleteAll(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            scheduled?.cancel(false)
            scheduled = null
        }
        val store = RecoverySnapshotStore(app)
        store.deletePrivate()
        store.deletePublic()
        LegacyRecoveryMigration.deleteLegacyFiles(store)
        statePrefs(app).edit().clear().commit()
        initialized = false
    }

    private fun writeNow(app: Context): Boolean = synchronized(writeLock) {
        if (restoring || LocalReset.resetting) {
            Log.w("HGTV-RECOVERY", "snapshot write skipped restoring=$restoring resetting=${LocalReset.resetting}")
            return false
        }
        val text = capture(app)?.let(RecoverySnapshotCodec::encode)
        if (text == null) {
            Log.w("HGTV-RECOVERY", "snapshot capture returned null")
            return false
        }
        val store = RecoverySnapshotStore(app)
        val privateOk = store.writePrivate(text)
        val state = statePrefs(app)
        val publicRequested = RecoveryPermissions.hasPublicAccess(app) &&
            !state.getBoolean(KEY_SKIPPED_PUBLIC, false)
        val publicOk = if (publicRequested) store.writePublic(text) else false
        Log.w(
            "HGTV-RECOVERY",
            "snapshot write bytes=${text.toByteArray(Charsets.UTF_8).size} " +
                "private=$privateOk publicRequested=$publicRequested public=$publicOk",
        )
        return privateOk || publicOk
    }

    private fun migrateLegacyIfNeeded(
        app: Context,
        state: SharedPreferences,
        publicPermissionGranted: Boolean,
    ): Unit = synchronized(migrationLock) {
        Log.w(
            "HGTV-RECOVERY",
            "legacy migration check granted=$publicPermissionGranted " +
                "mounted=${RecoveryPermissions.isStorageMounted()} " +
                "done=${state.getBoolean(KEY_LEGACY_MIGRATION_DONE, false)} " +
                "cleanupPending=${state.getBoolean(KEY_LEGACY_CLEANUP_PENDING, false)}",
        )
        if (!publicPermissionGranted || !RecoveryPermissions.isStorageMounted()) return
        val store = RecoverySnapshotStore(app)
        if (!state.getBoolean(KEY_LEGACY_MIGRATION_DONE, false)) {
            val merged = LegacyRecoveryMigration.merge(app, store)
            // 旧数据已经合并进偏好；即使新快照写失败，也按产品规则继续清理旧文件。
            val snapshotOk = writeNow(app)
            val cleanupOk = LegacyRecoveryMigration.deleteLegacyFiles(store)
            state.edit()
                .putBoolean(KEY_LEGACY_MIGRATION_DONE, true)
                .putBoolean(KEY_LEGACY_CLEANUP_PENDING, !cleanupOk)
                .commit()
            Log.w(
                "HGTV-RECOVERY",
                "legacy migration finished merged=$merged snapshot=$snapshotOk cleanup=$cleanupOk",
            )
        } else if (state.getBoolean(KEY_LEGACY_CLEANUP_PENDING, false)) {
            val cleanupOk = LegacyRecoveryMigration.deleteLegacyFiles(store)
            if (cleanupOk) {
                state.edit().putBoolean(KEY_LEGACY_CLEANUP_PENDING, false).commit()
            }
            Log.w("HGTV-RECOVERY", "legacy cleanup retry success=$cleanupOk")
        }
    }

    private fun capture(app: Context): RecoverySnapshot? = runCatching {
        runCatching {
            AppRuntime.library(app).takeIf { it.isLoaded }?.flushBlocking(3_000L)
        }
        val library = AppRuntime.library(app).takeIf { it.isLoaded }
        val libraryState = library?.snapshot()
        val prefs = buildMap<String, Map<String, Any>> {
            prefFiles.forEach { name ->
                val values = app.getSharedPreferences(name, Context.MODE_PRIVATE).all
                if (values.isNotEmpty()) {
                    put(
                        name,
                        values.filterValues(::supportedValue)
                            .entries
                            .associate { it.key to checkNotNull(it.value) },
                    )
                }
            }
        }
        val capturedPrefs = if (libraryState != null) {
            prefs.toMutableMap().apply {
                val values = get("native-library-v1").orEmpty().toMutableMap()
                values["progress"] = LibraryDisk.history(libraryState.history).toString()
                put("native-library-v1", values)
            }
        } else {
            prefs
        }
        val files = if (libraryState != null) {
            emptyMap()
        } else {
            buildMap {
                val root = File(app.filesDir, LIBRARY_FILES)
                collectFiles(root, root, this)
            }
        }
        val snapshot = RecoverySnapshot(
            createdAt = System.currentTimeMillis(),
            appVersionCode = appVersionCode(app),
            prefs = capturedPrefs,
            files = files,
        )
        require(RecoverySnapshotCodec.encode(snapshot).toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
            "恢复快照超过 8 MB"
        }
        snapshot
    }.onFailure {
        Log.w("HGTV-RECOVERY", "capture failed", it)
    }.getOrNull()

    private fun applySnapshot(app: Context, snapshot: RecoverySnapshot) {
        restoring = true
        try {
            snapshot.prefs.forEach { (name, values) ->
                val edit = app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
                values.forEach { (key, value) -> putValue(edit, key, value) }
                check(edit.commit()) { "恢复偏好失败: $name" }
            }
            val root = File(app.filesDir, LIBRARY_FILES)
            root.deleteRecursively()
            snapshot.files.forEach { (path, value) ->
                val target = File(app.filesDir, path)
                require(target.canonicalPath.startsWith(app.filesDir.canonicalPath + File.separator))
                target.parentFile?.mkdirs()
                target.writeText(value, Charsets.UTF_8)
            }
        } finally {
            restoring = false
        }
    }

    private fun readBest(candidates: List<String>): RecoverySnapshot? {
        var newer = false
        var best: RecoverySnapshot? = null
        candidates.forEach { text ->
            try {
                val snapshot = RecoverySnapshotCodec.decode(text)
                if (best == null || snapshot.createdAt > best!!.createdAt) best = snapshot
            } catch (_: NewerRecoverySnapshotException) {
                newer = true
            } catch (_: Exception) {
                // 继续尝试 .bak / 其他公共副本。
            }
        }
        if (newer) Log.w("HGTV-RECOVERY", "public snapshot is newer than this app")
        return best
    }

    private fun collectFiles(
        root: File,
        current: File,
        out: MutableMap<String, String>,
    ) {
        val children = current.listFiles().orEmpty().sortedBy { it.name }
        children.forEach { file ->
            if (file.isDirectory) {
                collectFiles(root, file, out)
            } else if (file.isFile) {
                val relative = file.relativeTo(root).invariantSeparatorsPath
                val path = "$LIBRARY_FILES/$relative"
                runCatching { out[path] = file.readText(Charsets.UTF_8) }
            }
        }
    }

    private fun supportedValue(value: Any?): Boolean =
        value is String || value is Int || value is Long || value is Float ||
            value is Boolean || value is Set<*>

    private fun putValue(edit: SharedPreferences.Editor, key: String, value: Any) {
        when (value) {
            is String -> edit.putString(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is Boolean -> edit.putBoolean(key, value)
            is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
            else -> error("不支持的偏好类型")
        }
    }

    private fun statePrefs(app: Context): SharedPreferences =
        app.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)

    private fun appVersionCode(app: Context): Int = runCatching {
        @Suppress("DEPRECATION")
        app.packageManager.getPackageInfo(app.packageName, 0).versionCode
    }.getOrDefault(Build.VERSION_CODES.KITKAT)
}
