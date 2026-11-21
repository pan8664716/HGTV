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
 * 私有副本始终存在；有存储权限时再写公共 Documents / Downloads 与 MediaStore 副本。
 * 公共副本保持明文，供卸载重装后的新 UID 直接读取。
 */
internal class RecoverySnapshotStore(private val app: Context) {
    private companion object {
        const val MAIN = "hgtv-recovery.json"
        const val BAK = "hgtv-recovery.json.bak"
        const val TMP = "hgtv-recovery.json.tmp"
        const val FOLDER = "HGTV"
    }

    private val privateDir = File(app.filesDir, "recovery")

    fun writePrivate(text: String): Boolean =
        writeAtomic(privateDir, MAIN, BAK, TMP, text)

    fun writePublic(text: String): Boolean {
        var wrote = false
        directDirs().forEach { dir ->
            if (writeAtomic(dir, MAIN, BAK, TMP, text)) wrote = true
        }
        if (Build.VERSION.SDK_INT >= 29) {
            if (MediaStoreRecoveryCopy.copyMainToBak(app)) wrote = true
            if (MediaStoreRecoveryCopy.write(app, MAIN, text)) wrote = true
        }
        return wrote
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

    private fun directDirs(): List<File> = buildList {
        runCatching {
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                FOLDER,
            )
        }.getOrNull()?.let(::add)
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
        val selection =
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        runCatching {
            app.contentResolver.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                selection,
                arrayOf(RELATIVE_PATH, "hgtv-recovery%"),
            )
        }.onFailure {
            Log.w("HGTV-RECOVERY", "MediaStore delete failed", it)
        }
    }
}
