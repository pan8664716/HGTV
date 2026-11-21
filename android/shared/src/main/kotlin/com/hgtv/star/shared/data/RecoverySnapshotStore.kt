// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 快照文件存储。
 *
 * 私有副本始终存在；有存储权限时再写公共 Download 与 MediaStore 副本。
 * 公共副本保持明文，供卸载重装后的新 UID 直接读取。
 */
internal class RecoverySnapshotStore(private val app: Context) {
    private companion object {
        const val MAIN = "hgtv-recovery.json"
        const val BAK = "hgtv-recovery.json.bak"
        const val TMP = "hgtv-recovery.json.tmp"
        const val LEGACY_SESSION = "hgtv-session.json"
        const val FOLDER = "HGTV"
    }

    private val privateDir = File(app.filesDir, "recovery")

    fun writePrivate(text: String): Boolean {
        val ok = writeAtomic(privateDir, MAIN, BAK, TMP, text)
        Log.w("HGTV-RECOVERY", "private snapshot write success=$ok path=${File(privateDir, MAIN)}")
        return ok
    }

    fun writePublic(text: String): Boolean {
        val direct = directDirs().any { dir ->
            writeAtomic(dir, MAIN, BAK, TMP, text)
        }
        if (direct) {
            // 直接写入成功后仍确认 MediaStore 能看到同一个逻辑文件；
            // 已经存在则更新内容，不存在则补登记。
            if (Build.VERSION.SDK_INT >= 29) {
                val backup = MediaStoreRecoveryCopy.copyMainToBak(app)
                val indexed = MediaStoreRecoveryCopy.write(app, MAIN, text)
                Log.w("HGTV-RECOVERY", "public direct success=true mediaBackup=$backup mediaIndexed=$indexed")
            }
            Log.w("HGTV-RECOVERY", "public snapshot write success=true path=${directDirs().firstOrNull()}/$MAIN")
            return true
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val backup = MediaStoreRecoveryCopy.copyMainToBak(app)
            val media = MediaStoreRecoveryCopy.write(app, MAIN, text)
            Log.w("HGTV-RECOVERY", "public direct success=false mediaBackup=$backup mediaWrite=$media")
            return media
        }
        Log.w("HGTV-RECOVERY", "public snapshot write success=false direct unavailable")
        return false
    }

    fun readPrivateCandidates(): List<String> =
        readCandidates(privateDir)

    fun readPublicCandidates(): List<String> {
        val out = ArrayList<String>()
        directDirs().forEach { out += readCandidates(it) }
        if (Build.VERSION.SDK_INT >= 29) {
            MediaStoreRecoveryCopy.read(app, MAIN)?.let(out::add)
            MediaStoreRecoveryCopy.read(app, BAK)?.let(out::add)
        }
        return out.distinct()
    }

    fun readLegacySessionCandidates(): List<String> {
        val out = ArrayList<String>()
        runCatching { File(app.filesDir, LEGACY_SESSION) }
            .getOrNull()?.let { file -> readText(file)?.let(out::add) }
        legacyDirectDirs().forEach { dir ->
            readText(File(dir, LEGACY_SESSION))?.let(out::add)
        }
        if (Build.VERSION.SDK_INT >= 29) {
            MediaStoreRecoveryCopy.read(app, LEGACY_SESSION)?.let(out::add)
        }
        return out.filter { it.isNotBlank() }.distinct()
    }

    fun deletePublic() {
        directDirs().forEach { dir ->
            listOf(MAIN, BAK, TMP).forEach { name ->
                runCatching { File(dir, name).delete() }
            }
        }
        if (Build.VERSION.SDK_INT >= 29) MediaStoreRecoveryCopy.deleteAll(app)
    }

    fun deletePrivate() {
        runCatching { privateDir.deleteRecursively() }
    }

    /** 删除旧会话镜像；返回 false 表示至少有一项删除失败或仍存在。 */
    fun deleteLegacySessionFiles(): Boolean {
        var clean = true
        fun deleteFile(file: File) {
            val exists = file.exists()
            val ok = !exists || runCatching { file.delete() }.getOrDefault(false)
            if (!ok) clean = false
            Log.w("HGTV-RECOVERY", "legacy delete exists=$exists success=$ok path=$file")
        }
        deleteFile(File(app.filesDir, LEGACY_SESSION))
        legacyDirectDirs().forEach { dir ->
            deleteFile(File(dir, LEGACY_SESSION))
            if (dir.isDirectory && dir.list().isNullOrEmpty()) {
                runCatching { dir.delete() }
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val ok = MediaStoreRecoveryCopy.delete(app, LEGACY_SESSION)
            if (!ok) clean = false
            Log.w("HGTV-RECOVERY", "legacy MediaStore delete success=$ok name=$LEGACY_SESSION")
        }
        return clean
    }

    private fun directDirs(): List<File> = buildList {
        runCatching {
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                FOLDER,
            )
        }.getOrNull()?.let(::add)
    }.distinctBy { it.absolutePath }

    private fun legacyDirectDirs(): List<File> = buildList {
        runCatching { File(Environment.getExternalStorageDirectory(), ".hgtv") }
            .getOrNull()?.let(::add)
        runCatching {
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                ".hgtv",
            )
        }.getOrNull()?.let(::add)
        runCatching { app.getExternalFilesDir(null) }
            .getOrNull()?.let(::add)
        runCatching {
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                FOLDER,
            )
        }.getOrNull()?.let(::add)
    }.distinctBy { it.absolutePath }

    private fun readCandidates(dir: File): List<String> {
        if (!dir.isDirectory) return emptyList()
        return listOf(MAIN, BAK).mapNotNull { name ->
            runCatching {
                File(dir, name).takeIf { it.isFile }?.readText(Charsets.UTF_8)
            }.getOrNull()
        }
    }

    private fun readText(file: File): String? = runCatching {
        file.takeIf { it.isFile }?.readText(Charsets.UTF_8)
    }.getOrNull()

    private fun writeAtomic(
        dir: File,
        mainName: String,
        bakName: String,
        tmpName: String,
        text: String,
    ): Boolean = runCatching {
        if (!dir.isDirectory && !dir.mkdirs()) return@runCatching false
        val bytes = text.toByteArray(Charsets.UTF_8)
        val temp = File(dir, tmpName)
        FileOutputStream(temp).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        val main = File(dir, mainName)
        val backup = File(dir, bakName)
        if (main.isFile) {
            backup.delete()
            if (!main.renameTo(backup)) {
                runCatching { backup.writeText(main.readText(Charsets.UTF_8), Charsets.UTF_8) }
            }
        }
        val moved = temp.renameTo(main) || (main.delete() && temp.renameTo(main))
        if (!moved) {
            FileOutputStream(main).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            temp.delete()
        }
        main.length() == bytes.size.toLong() &&
            runCatching { main.readText(Charsets.UTF_8) == text }.getOrDefault(false)
    }.getOrElse {
        Log.w("HGTV-RECOVERY", "cannot write $dir/$mainName", it)
        false
    }
}

/** MediaStore 只能隔离在 API 29 才加载的类里。 */
@android.annotation.TargetApi(Build.VERSION_CODES.Q)
private object MediaStoreRecoveryCopy {
    private const val RELATIVE_PATH = "Download/HGTV/"

    private fun uriFor(app: Context, name: String): Uri? =
        rows(app, name).firstOrNull()

    private fun rows(app: Context, name: String): List<Uri> = runCatching {
        val selection =
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        app.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            selection,
            arrayOf(name, RELATIVE_PATH),
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            cursor.getLong(id),
                        ),
                    )
                }
            }
        }.orEmpty()
    }.getOrElse {
        Log.w("HGTV-RECOVERY", "MediaStore query failed", it)
        emptyList()
    }

    fun read(app: Context, name: String): String? {
        val uri = uriFor(app, name) ?: return null
        return runCatching {
            app.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            }
        }.onFailure {
            Log.w("HGTV-RECOVERY", "MediaStore read failed", it)
        }.getOrNull()
    }

    fun copyMainToBak(app: Context): Boolean {
        val current = read(app, "hgtv-recovery.json") ?: return false
        return write(app, "hgtv-recovery.json.bak", current)
    }

    fun write(app: Context, name: String, text: String): Boolean {
        val resolver = app.contentResolver
        val existing = uriFor(app, name)
        if (existing != null) return writeUri(resolver, existing, text, clearPending = false)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val created = runCatching {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return false
        if (writeUri(resolver, created, text, clearPending = true)) return true
        runCatching { resolver.delete(created, null, null) }
        return false
    }

    private fun writeUri(
        resolver: android.content.ContentResolver,
        uri: Uri,
        text: String,
        clearPending: Boolean,
    ): Boolean = runCatching {
        resolver.openOutputStream(uri, "wt")?.use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.flush()
        } ?: error("MediaStore output unavailable")
        val values = ContentValues().apply {
            if (clearPending) put(MediaStore.MediaColumns.IS_PENDING, 0)
            put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000L)
        }
        resolver.update(uri, values, null, null)
        true
    }.onFailure {
        Log.w("HGTV-RECOVERY", "MediaStore write failed", it)
        if (clearPending) runCatching {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
        }
    }.getOrDefault(false)

    fun deleteAll(app: Context) {
        delete(app, "hgtv-recovery.json")
        delete(app, "hgtv-recovery.json.bak")
    }

    fun delete(app: Context, name: String): Boolean {
        val selection =
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?"
        return runCatching {
            app.contentResolver.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                selection,
                arrayOf(RELATIVE_PATH, name),
            )
        }.onFailure {
            Log.w("HGTV-RECOVERY", "MediaStore delete failed", it)
        }.isSuccess
    }
}
