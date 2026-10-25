// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.data

import com.hgtv.star.Diagnostics

import android.content.Context
import android.os.Environment
import android.util.Log
import com.hgtv.star.core.LatestWriteQueue
import java.io.File
import org.json.JSONObject

/**
 * 设备身份与登录会话的**镜像**（同一份 JSON，沙盒 `files/`、`/sdcard/.hgtv/`、`Android/data/<包名>/` 各存一份）。
 *
 * ## 为什么要有它
 *
 * 设备身份（`device_id` / `iid`）与账号会话（`sessionid` 那一串 Cookie、`x-tt-token`）原本只存在
 * App 私有的 `SharedPreferences` 里，而**卸载会把私有数据一起删掉**。于是重装之后本机是
 * 一台全新设备：`device_id` / `iid` 重新注册拿到新的一对，`cdid` / `openudid` 也是新随机值。
 * 平台看到「陌生设备 + 老账号」，风控就会再要一次上行短信确认本人——用户得重新走一轮发短信。
 * 真机上的红果不这样：它把这些东西放在**外部存储**，重装先捞回来，于是平台仍认这台设备。
 *
 * ## 放哪儿、能活多久
 *
 * 写入时**每个能写的位置都写一份**，读取时**取最新的一份**（老版本写在哪个位置，新版本都认得出来）：
 *
 * 1. `files/`（沙盒）—— **不需要任何权限**。用户拒绝外部存储权限时（用户反馈的那台平板就是
 *    这种情况：注册一次成功，退出再进又成了未注册设备），它是唯一一定写得进去的那一份；
 * 2. `/sdcard/.hgtv/`（自建隐藏目录）—— **卸载不删**，只要用户没手动清存储就一直在。
 *    这一处是「卸载重装仍能恢复」的主力：靠 `targetSdk 29` + `requestLegacyExternalStorage`
 *    拿到的传统存储权限直接写公共目录（见 manifest 与 `MainActivity.requestMirrorStorage`）。
 * 3. `Android/data/<包名>/files/`（[Context.getExternalFilesDir]）—— 不需要权限，
 *    标准 Android 在卸载时由 vold 清理，但很多真机（尤其国产 ROM）会把它残留下来。
 *
 * 三份互为备份：任何一份写不进去，另外两份照常落盘；启动时只要有任意一份还在，设备身份
 * 与登录会话就认得回来。补齐缺失的那几份发生在启动读盘之后（见 [preload]）。
 *
 * ## 写入时机
 *
 * 每次设备身份 / 设备档案 / 账号会话有变化就整份重写（同一 key 只保留最后一次，见
 * [LatestWriteQueue]），所以外部那份**永远是最新的**：不会出现「救回来的是旧会话」。
 * 读只在「本机缺这一项」时才发生（重装后的第一次调用），正常运行不碰磁盘。
 *
 * ## 边界
 *
 * 这个文件是**明文凭据**。Android 9 及以下，任何拿到存储权限的应用都能读到它——
 * 与真机红果同一层级的取舍；用户「清除数据 / 恢复出厂」或换机器时它也一起没了。
 */
internal class SessionMirror(private val app: Context) {
    private companion object {
        const val FILE_NAME="hgtv-session.json"
        /** 镜像格式版本：结构变了就抬它，老文件直接不认（宁可让用户重登，也不误读）。 */
        const val VERSION=1
        /** 自建目录名（隐藏目录，卸载不删）。 */
        const val LEGACY_DIR=".hgtv"
    }
    private val lock=Any()
    /** 镜像内容：键与 `sessionPrefs` 的键**同名**，读回来直接塞回去，不再做一层映射。 */
    private val state=JSONObject()
    /**
     * 是否已经读过盘。
     *
     * **主线程只读内存**：读盘（`/sdcard` 上的 FUSE 文件）只在 [preload] / [reload] 里做，
     * 这两个入口都只允许在后台线程调用。主线程上任何一次 `isFile` / `readText` 都可能
     * 被 FUSE 卡住几秒，正是线上那条 `onRequestPermissionsResult` ANR 的成因。
     */
    @Volatile private var loaded=false
    /**
     * 还没读盘之前先记下的写入。
     *
     * 读盘之前不知道盘上已有哪些键，此时直接整份重写会把旧身份 / 旧会话抹掉，
     * 因此先记在这里，[preload] 读完盘之后再合并落盘。
     */
    private val pending=linkedMapOf<String,Any?>()
    /**
     * 读盘之前**明确要求删除**的键（退出登录 / 清除账号）。
     *
     * 与 [pending] 分开：`put(key, null)` 在读盘之前多半只是「本机内存里还没有」，
     * 不能当成删除；而退出登录是真正的删除意图，读盘后必须把盘上那份也抹掉，
     * 否则下次启动又会把刚退掉的会话救回来。
     */
    private val removed=mutableSetOf<String>()
    /** 已进入「清除本机数据」流程：之后任何一次 [put] 都直接丢弃（见 [discard]）。 */
    @Volatile private var discarded=false
    /** 内存里这份快照的时间戳（`saved_at`），见 [repairCopies]。0 = 还没有任何内容。 */
    private var savedAt=0L
    /** 本次进程是否因为「刚清除过数据」而跳过镜像（见 [LocalReset.markPendingReset]）。 */
    @Volatile private var ignore=false
    private val writes=LatestWriteQueue<String, () -> Any?>(name="hgtv-mirror-write", write=::commit)

    /** 现在**有没有**能写的位置（真写一个探针再删掉：`isDirectory` 为真不等于写得进去）。 */
    fun available(): Boolean = dirs().any { dir ->
        runCatching {
            if(!dir.isDirectory && !dir.mkdirs()) return@runCatching false
            // 真写一个探针再删掉：`isDirectory` 为真不等于写得进去（权限、只读挂载）。
            File(dir,".hgtv-probe").let { probe -> probe.writeText("1"); probe.delete() }
            true
        }.getOrDefault(false)
    }

    /**
     * 候选目录，**三处都写**（用户要求：沙盒、`.hgtv`、`Android/data/<包名>`）。
     *
     * 1. `files/`（沙盒）：**不需要任何权限**，因此它是唯一「一定写得进去」的那一份——
     *    用户拒绝外部存储权限时，设备身份与登录会话就只靠它活下来；
     * 2. `/sdcard/.hgtv/`：能活过卸载，是重装后要恢复的那一份（需要存储权限）；
     * 3. `Android/data/<包名>/files/`：不需要权限，多数 ROM 卸载不删，第二份兜底。
     *
     * 读的时候三份一起看，取 `saved_at` 最新的那份：三份正常情况下内容一致，
     * 差别只出现在「某一份写不进去 / 删不掉」的时候，那时最新的一份才是对的。
     */
    private fun dirs(): List<File> = buildList {
        // 沙盒排第一：它没有权限门槛，读盘时也最先能读到。
        runCatching { app.filesDir }.getOrNull()?.let { add(it) }
        runCatching { File(Environment.getExternalStorageDirectory(),LEGACY_DIR) }.getOrNull()?.let { add(it) }
        runCatching { app.getExternalFilesDir(null) }.getOrNull()?.let { add(it) }
    }

    /** 镜像里的一项；本机没有这一项、或还没读过盘时返回 null。**不触发读盘。** */
    fun string(key: String): String? = synchronized(lock) {
        if(!loaded) return@synchronized null
        state.opt(key)?.let { if(it is String) it else it.toString() }
    }

    /** 是否已经读过盘（主线程据此决定「现在能不能读镜像」）。 */
    fun isLoaded(): Boolean = loaded

    /**
     * 读一次盘。**只允许在后台线程调用**（见 [loaded] 的说明）。
     *
     * 启动时由 `Library.loadAsync` 的工作线程调一次；读完之前 [string] 一律返回 null，
     * 主线程因此不会被外部存储拖住。读完之后再把 [pending] 里那几项合并进来落盘。
     */
    fun preload() {
        // 先快速判一次；真正的 I/O 在锁外做，主线程调 [string] 不会被它挡住。
        if(!synchronized(lock) { !discarded && !loaded }) return
        val text=readDisk()
        synchronized(lock) {
            if(discarded || loaded) return
            // 即使一个文件都没找到也算「读过」：否则 [put] 会一直排进 [pending]、
            // 新设备第一次启动就永远不落盘，卸载重装反而恢复不了。
            loaded=true
            if(!ignore) text?.let { adopt(it) }
            applyPendingLocked()
        }
        // 三处冗余：某一份缺失（例如这次才拿到存储权限、或上次写入时那一处不可写），
        // 或者那一份还是旧快照（上次写到它时失败 / 被系统回滚）时，把内存里这份补写过去。
        // **在锁外做**：`isFile` / `readText` 在 `/sdcard` 上可能被 FUSE 卡住几秒，
        // 持锁读会让主线程的 [string] / [put] 一起等（那正是要避免的 ANR）。
        repairCopies()
    }

    /**
     * 重新读盘一次（权限刚拿到 / 启动时读不到、之后才读得到）。
     *
     * 与 [preload] 的区别：读盘期间 [loaded] 保持为 true，主线程仍能读到上一次那份内存快照，
     * 不会为了等一次 FUSE 读而阻塞。
     */
    fun reload() {
        if(synchronized(lock) { discarded }) return
        val text=readDisk()
        synchronized(lock) {
            if(discarded) return
            text?.let { adopt(it) }
            loaded=true
            applyPendingLocked()
        }
        // 同上：补齐缺失 / 过期的那几份（权限刚拿到时 `.hgtv` 才第一次写得进去）。
        repairCopies()
    }

    /**
     * 真正的磁盘读取（**无锁**，只允许后台线程调）。
     *
     * 不持 [lock] 是刻意的：`/sdcard` 是 FUSE，`isFile` / `readText` 在系统扫描存储时可以卡几秒；
     * 持锁读会让恰好调用 [string] / [put] 的主线程一起等，等于把 ANR 换个地方复现。
     */
    private fun readDisk(): String? {
        if(ignore) return null
        // 刚清除过数据：不认旧镜像，并再试着把那份删不掉的旧文件清掉。
        // 只有确认所有候选位置都没有旧文件了，标记才消失——否则下一次启动又会被它救回来。
        val marker=runCatching { File(app.filesDir,LocalReset.RESET_MARKER) }.getOrNull()
        if(marker?.isFile==true) {
            dirs().forEach { dir -> runCatching { File(dir,FILE_NAME).delete() } }
            val stale=dirs().any { dir -> File(dir,FILE_NAME).isFile }
            if(!stale) runCatching { marker.delete() }
            synchronized(lock) { ignore=true }
            return null
        }
        // 多份镜像里取 **saved_at 最新** 的那一份，而不是目录顺序里的第一份。
        //
        // 正常情况下几份是同时写的、时间相同，取谁都一样；差别出现在「清除本地数据」之后：
        // `/sdcard/.hgtv` 可能因为权限被拒删不掉，而本机已经生成了全新的一份。
        // 按目录顺序读会把那份旧的捡回来（等于恢复出厂没生效），按时间读则新的永远赢。
        var best: String?=null
        var bestAt=Long.MIN_VALUE
        for(dir in dirs()) {
            val text=runCatching { File(dir,FILE_NAME).takeIf { it.isFile }?.readText() }.getOrNull() ?: continue
            val json=runCatching { JSONObject(text) }.getOrNull() ?: continue
            if(json.optInt("version",0)!=VERSION) continue
            if(json.optJSONObject("values")==null) continue
            val at=json.optLong("saved_at",0L)
            if(best==null || at>bestAt) { best=text; bestAt=at }
        }
        return best
    }

    /**
     * 落一项（`null` = 从镜像里删掉这一项）。整份重写：设备身份与会话都是小对象、一次几 KB，
     * 用它换来「外部那份永远与内存一致」，不必去写合并逻辑。
     *
     * 读盘之前只把**非空值**记进 [pending]，等 [preload] 合并；见那里的说明。
     */
    fun put(key: String,value: Any?) {
        if(discarded) return
        synchronized(lock) {
            // 还没读盘：先记下来，等 [preload] 合并。此时整份重写会抹掉盘上的旧键。
            //
            // **删除项与空值都不排队**：读盘之前说「删掉这一项 / 这一项是空的」很可能只是
            // 「本机内存里还没有」，而盘上那份正是重装后要恢复的会话
            // （例如新装后 `account()` 先跑了一遍，`account-expires` 还是 0）。
            // 排队会把刚捞回来的会话用空值盖掉；读盘之后如果确实要删，那时的调用照常生效。
            if(!loaded) {
                // 读盘之前写空值 = 「本机还没有这一项」，不是删除：忽略即可。
                val keep=when(value) {
                    null -> false
                    is String -> value.isNotBlank()
                    is Number -> value.toLong()!=0L
                    else -> true
                }
                if(keep) pending[key]=value
                return
            }
            if(value==null) state.remove(key) else state.put(key,value)
            val text=encodeLocked()
            // 序列化在锁内、磁盘 IO 在 [writes] 的线程（[commit] 只碰这个字符串）。
            runCatching { writes.submit(mapOf("mirror" to { text })) }
                .onFailure { Log.w("HGTV","Session mirror queue closed; change kept in memory only"); Diagnostics.warn("HGTV-MIRROR","queue closed; change kept in memory only") }
        }
    }

    /**
     * 明确删除一项（退出登录 / 清除账号）。
     *
     * 与 `put(key, null)` 的区别：这一条在**读盘之前**也会被记住，读盘后照样生效。
     */
    fun remove(key: String) {
        if(discarded) return
        synchronized(lock) {
            if(!loaded) { removed+=key; pending.remove(key); return }
            state.remove(key)
            val text=encodeLocked()
            runCatching { writes.submit(mapOf("mirror" to { text })) }
                .onFailure { Log.w("HGTV","Session mirror queue closed; change kept in memory only"); Diagnostics.warn("HGTV-MIRROR","queue closed; change kept in memory only") }
        }
    }

    /** 读盘之后把读盘之前的写入合并进来；有改动就整份落盘一次。 */
    private fun applyPendingLocked() {
        if(pending.isEmpty() && removed.isEmpty()) return
        removed.forEach { key -> state.remove(key) }
        removed.clear()
        pending.forEach { (key,value) -> if(value==null) state.remove(key) else state.put(key,value) }
        pending.clear()
        val text=encodeLocked()
        runCatching { writes.submit(mapOf("mirror" to { text })) }
            .onFailure { Log.w("HGTV","Session mirror queue closed; change kept in memory only"); Diagnostics.warn("HGTV-MIRROR","queue closed; change kept in memory only") }
    }

    fun flush() { runCatching { writes.flush() } }
    fun close() { runCatching { writes.close() } }
    /** 清除本机数据前调用：丢掉待写快照并停掉写线程，之后不再把镜像写回去。 */
    fun discard() {
        discarded = true
        runCatching { writes.discard() }
    }

    /** 解析一份镜像文本并收进内存；版本对不上返回 null（宁可让用户重登，也不误读）。 */
    private fun adopt(text: String): List<String>? {
        val json=runCatching { JSONObject(text) }.getOrNull() ?: return null
        if(json.optInt("version",0)!=VERSION) return null
        val values=json.optJSONObject("values") ?: return null
        // 记住这份快照的时间戳：后面 [repairCopies] 用它判断另外两份是不是旧的。
        savedAt=json.optLong("saved_at",0L)
        val keys=mutableListOf<String>()
        val iterator=values.keys()
        while(iterator.hasNext()) {
            val key=iterator.next()
            keys+=key
            state.put(key,values.opt(key))
        }
        return keys
    }
    /** 锁内取一份不可变快照（写线程只碰返回值）。 */
    private fun encodeLocked(): String {
        val values=JSONObject()
        val keys=state.keys()
        while(keys.hasNext()) { val key=keys.next(); values.put(key,state.opt(key)) }
        val now=System.currentTimeMillis()
        savedAt=now
        return JSONObject().put("version",VERSION).put("saved_at",now)
            .put("values",values).toString()
    }
    /**
     * 三处里有没有**缺这一份、或这一份是旧快照**的；有就把内存里这份补写过去。
     *
     * 判据是 `saved_at`，不是「文件在不在」：上一次写入时某一处不可写（没权限 / 只读挂载），
     * 那一份会停在一个更早的时间戳上；只看文件存在会认为「三份都在」，于是永远不修，
     * 等哪天沙盒被清掉、只剩那一份旧文件时，救回来的就是一套过期的身份。
     *
     * 比较的是内存里这份的时间戳（[savedAt]），因此正常情况下三份一致，一个字节都不写。
     * **只能在后台线程调用**（会读三个小文件）。
     */
    private fun repairCopies() {
        if(ignore) return
        val at=synchronized(lock) { savedAt }
        if(at<=0L) return
        val stale=dirs().any { dir ->
            val file=File(dir,FILE_NAME)
            if(!file.isFile) true
            else runCatching { JSONObject(file.readText()).optLong("saved_at",0L) }.getOrDefault(-1L)!=at
        }
        if(!stale) return
        // 补写的内容与内存完全一致；`encodeLocked` 会把 `saved_at` 抬到「现在」，
        // 于是这次写成功的每一份都对齐到同一个时间戳，下一次启动不再判定为过期。
        val copy=synchronized(lock) { if(discarded) null else encodeLocked() } ?: return
        runCatching { writes.submit(mapOf("mirror" to { copy })) }
    }

    /**
     * 立刻把内存里这份镜像写到**所有**候选位置。
     *
     * 用在「外部存储权限刚拿到」的那一刻：在此之前 `.hgtv` / `Android/data` 那两份写不进去，
     * 而 [commit] 只要有一处写成功就返回 true（不会重试失败的那几处）。
     * 不补这一次，用户授权后要等到下一次身份变化才会在外部存储里留下副本。
     */
    fun persistNow() {
        if(synchronized(lock) { discarded }) return
        val text=synchronized(lock) { encodeLocked() }
        runCatching { writes.submit(mapOf("mirror" to { text })) }
            .onFailure { Log.w("HGTV","Session mirror queue closed; cannot persist now") }
    }

    /** 写盘：每个可写目录都写一份（能活过卸载的那一份最重要，其余当兜底）。 */
    private fun commit(batch: Map<String, () -> Any?>): Boolean {
        val text=batch.values.lastOrNull()?.invoke() as? String ?: return true
        var wrote=false
        val written=HashSet<String>()
        dirs().forEach { dir ->
            val ok=runCatching {
                if(!dir.isDirectory && !dir.mkdirs()) {
                    // 目录建不出来（权限不足 / 只读挂载）：把原因说清楚。
                    // 电视上没法调试，`logcat -s HGTV` 里这一行就是唯一线索。
                    Log.w("HGTV","Session mirror cannot create $dir")
                    Diagnostics.warn("HGTV-MIRROR","cannot create $dir")
                    return@runCatching false
                }
                // 先写临时文件再改名：进程在写一半时被杀（电视盒子被拔电是常态），
                // 留下的半个 JSON 解析不出来，等于这一份白存了。改名在同一目录内是原子的。
                val target=File(dir,FILE_NAME)
                val temp=File(dir,"$FILE_NAME.tmp")
                temp.writeText(text)
                if(!temp.renameTo(target)) {
                    // 少数 ROM 上 renameTo 会因目标已存在而失败：退回覆盖写。
                    target.writeText(text)
                    temp.delete()
                }
                true
            }.onFailure {
                Log.w("HGTV","Session mirror cannot write $dir",it)
                // 镜像写不进去 = 卸载重装会丢登录（用户一定会报「重装后要重新登录」）。
                // 这条要带上目录：是 /sdcard/.hgtv 还是 Android/data，修法完全不同。
                Diagnostics.warn("HGTV-MIRROR","cannot write $dir",it)
            }.getOrDefault(false)
            if(ok) { wrote=true; written += dir.absolutePath }
        }
        if(!wrote) {
            Log.w("HGTV","Session mirror unwritable; identity and session stay app-private")
            Diagnostics.warn("HGTV-MIRROR","unwritable; identity and session stay app-private")
        }
        // 「刚清除过」的标记：新镜像已经覆盖了所有**写得进去**的位置，且没有剩下任何
        // 写不进去的旧文件（那才是会盖掉新身份的那一份），才撤掉它。
        if(wrote) {
            val marker=File(app.filesDir,LocalReset.RESET_MARKER)
            if(marker.isFile) {
                val stale=dirs().any { dir -> dir.absolutePath !in written && File(dir,FILE_NAME).isFile }
                if(!stale) runCatching { marker.delete() }
            }
        }
        return wrote
    }
}
