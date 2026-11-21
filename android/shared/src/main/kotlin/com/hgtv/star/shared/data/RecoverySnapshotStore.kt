// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
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
            if (Build.VERSION.SDK_INT >= 29) {
                // 直接文件已经存在时不能再 insert 一个同名 MediaStore 条目，
                // 否则系统会生成 `hgtv-recovery (1).json`。
                val duplicatesRemoved = MediaStoreRecoveryCopy.deleteDuplicates(app, MAIN)
                val dir = directDirs().firstOrNull()
                val paths = listOfNotNull(
                    dir?.let { File(it, MAIN).absolutePath },
                    dir?.let { File(it, BAK).absolutePath },
                ).toTypedArray()
                MediaScannerConnection.scanFile(
                    app,
                    paths,
                    Array(paths.size) { "application/json" },
                    null,
                )
                Log.w(
                    "HGTV-RECOVERY",
                    "public direct success=true mediaScanRequested=${paths.size} " +
                        "duplicatesRemoved=$duplicatesRemoved",
                )
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
        app.filesDir.listFiles()
            ?.filter { it.isFile && isLegacySessionName(it.name) }
            ?.forEach { file -> readText(file)?.let(out::add) }
        legacyDirectDirs().forEach { dir ->
            dir.listFiles()
                ?.filter { it.isFile && isLegacySessionName(it.name) }
                ?.forEach { file -> readText(file)?.let(out::add) }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            out += MediaStoreRecoveryCopy.readLegacySessions(app)
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
        fun deleteMatching(dir: File, removeEmptyDir: Boolean) {
            dir.listFiles()
                ?.filter { it.isFile && isLegacySessionName(it.name) }
                ?.forEach(::deleteFile)
            if (removeEmptyDir && dir.isDirectory && dir.list().isNullOrEmpty()) {
                runCatching { dir.delete() }
            }
        }
        deleteMatching(app.filesDir, removeEmptyDir = false)
        legacyDirectDirs().forEach { dir ->
            deleteMatching(dir, removeEmptyDir = true)
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val ok = MediaStoreRecoveryCopy.deleteLegacySessions(app)
            if (!ok) clean = false
            Log.w("HGTV-RECOVERY", "legacy MediaStore delete success=$ok pattern=hgtv-session*")
        }
        return clean
    }

    private fun isLegacySessionName(name: String): Boolean =
        name == LEGACY_SESSION ||
            (name.startsWith("hgtv-session (") && name.endsWith(").json"))

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

    private fun rows(app: Context, name: String): List<Uri> =
        queryUris(
            app,
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(name, RELATIVE_PATH),
        )

    private fun queryUris(
        app: Context,
        selection: String,
        args: Array<String>,
    ): List<Uri> = runCatching {
        app.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            selection,
            args,
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
        return readUri(app, uri)
    }

    fun readLegacySessions(app: Context): List<String> {
        val selection =
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND " +
                "(${MediaStore.MediaColumns.DISPLAY_NAME}=? OR " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?)"
        return queryUris(
            app,
            selection,
            arrayOf(RELATIVE_PATH, "hgtv-session.json", "hgtv-session (%).json"),
        ).mapNotNull { uri -> readUri(app, uri) }
    }

    private fun readUri(app: Context, uri: Uri): String? {
        return runCatching {
            app.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            }
        }.onFailure {
            Log.w("HGTV-RECOVERY", "MediaStore read failed", it)
        }.getOrNull()
    }

    fun copyMainToBak(app: Context): Boolean {
        val uri = uriFor(app, "hgtv-recovery.json") ?: return false
        val current = runCatching {
            app.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            }
        }.getOrNull() ?: return false
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
        deleteDuplicates(app, "hgtv-recovery.json")
    }

    fun deleteDuplicates(app: Context, name: String): Boolean {
        val selection =
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND " +
                "${MediaStore.MediaColumns.DISPLAY_NAME}<>?"
        return runCatching {
            app.contentResolver.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                selection,
                arrayOf(RELATIVE_PATH, name.substringBeforeLast('.') + " (%).json", name),
            )
        }.onFailure {
            Log.w("HGTV-RECOVERY", "MediaStore duplicate delete failed", it)
        }.isSuccess
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

    fun deleteLegacySessions(app: Context): Boolean {
        val selection =
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND " +
                "(${MediaStore.MediaColumns.DISPLAY_NAME}=? OR " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?)"
        return runCatching {
            app.contentResolver.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                selection,
                arrayOf(RELATIVE_PATH, "hgtv-session.json", "hgtv-session (%).json"),
            )
        }.onFailure {
            Log.w("HGTV-RECOVERY", "MediaStore legacy session delete failed", it)
        }.isSuccess
    }
}
