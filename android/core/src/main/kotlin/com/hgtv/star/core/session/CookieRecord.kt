// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core.session

/**
 * 一条 `Set-Cookie` 解析后的结果。只保留会话用得到的三项：`name` / `value` / 过期时刻。
 *
 * **刻意丢掉 `Domain` / `Path`**：本工程的 cookie 是扁平串（`AccountCredentials.cookie`），
 * 所有 host 共用一份。这与原版 `share_cookie_host_list` 的效果一致，也是「心跳拿不到
 * `sessionid`」这个最常见掉线原因的根治办法——按 host 分组存时，
 * `security.snssdk.com` 取不到 `api5-*.fqnovel.com` 下那份 `sessionid`。
 */
data class CookieRecord(
    val name: String,
    val value: String,
    /** 绝对毫秒；`0` 表示会话 cookie。 */
    val expiresAt: Long = 0L,
    /** 服务端要求删除这一条（`Max-Age=0` / 负值 / `Expires` 已过）。 */
    val deletion: Boolean = false
) {
    val isExpired: Boolean get() = expiresAt in 1 until System.currentTimeMillis()

    /** 拼进 `Cookie:` 头的那一段。 */
    val pair: String get() = "$name=$value"

    companion object {
        private val DATE_FORMATS = listOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEEE, dd-MMM-yy HH:mm:ss zzz",
            "EEE MMM d HH:mm:ss yyyy"
        )

        /**
         * 解析一条 `Set-Cookie`；不是 cookie 行返回 null。
         *
         * 注意 `sid_guard` 的值里带 `%7C`、`Expires` 的值里带逗号，
         * 因此**只按 `;` 切分**，不在值里再切。
         */
        fun parse(raw: String?): CookieRecord? {
            val text = raw?.trim().orEmpty()
            if(text.isEmpty()) return null
            val parts = text.split(';')
            val first = parts[0].trim()
            val eq = first.indexOf('=')
            if(eq<=0) return null
            val name = first.substring(0,eq).trim()
            if(name.isEmpty()) return null
            val value = first.substring(eq+1).trim()
            var expiresAt = 0L
            var maxAgeSeen = false
            var deleteByMaxAge = false
            var deleteByExpires = false
            for(index in 1 until parts.size) {
                val attr = parts[index].trim()
                val at = attr.indexOf('=')
                val key = (if(at>=0) attr.substring(0,at) else attr).trim().lowercase()
                val v = if(at>=0) attr.substring(at+1).trim() else ""
                when(key) {
                    "max-age" -> {
                        val seconds = v.toLongOrNull() ?: continue
                        maxAgeSeen = true
                        if(seconds<=0L) deleteByMaxAge = true
                        else expiresAt = System.currentTimeMillis()+seconds*1000L
                    }
                    // RFC 6265：`Max-Age` 优先于 `Expires`
                    "expires" -> if(!maxAgeSeen) {
                        val at2 = parseDate(v)
                        if(at2!=null) {
                            if(at2<=System.currentTimeMillis()) deleteByExpires = true else expiresAt = at2
                        }
                    }
                }
            }
            return CookieRecord(name,value,expiresAt,deleteByMaxAge || deleteByExpires)
        }

        fun parseDate(raw: String): Long? {
            for(pattern in DATE_FORMATS) {
                val parsed = runCatching {
                    java.text.SimpleDateFormat(pattern,java.util.Locale.US).apply {
                        timeZone = java.util.TimeZone.getTimeZone("GMT")
                        isLenient = false
                    }.parse(raw)
                }.getOrNull()
                if(parsed!=null) return parsed.time
            }
            return null
        }

        /** `Cookie:` 头 → `name -> value`（同名保留最后一个）。 */
        fun headerToMap(header: String): LinkedHashMap<String,String> {
            val out = LinkedHashMap<String,String>()
            for(segment in header.split(';')) {
                val part = segment.trim()
                val at = part.indexOf('=')
                if(at>0) out[part.substring(0,at).trim()] = part.substring(at+1).trim()
            }
            return out
        }

        fun mapToHeader(map: Map<String,String>): String =
            map.entries.joinToString("; ") { (name,value) -> "$name=$value" }

        /**
         * 把一批 `Set-Cookie` 合并进现有 cookie 串。
         *
         * * 同名覆盖（最后写入胜出）；
         * * `Max-Age=0` / `Expires` 已过 → **移除**，而不是写空值；
         * * 其它属性（`Path` / `Domain` / `HttpOnly` / `Secure` / `SameSite`）忽略。
         */
        fun merge(header: String,setCookies: List<String>): String {
            if(setCookies.isEmpty()) return header
            val map = headerToMap(header)
            var changed = false
            for(raw in setCookies) {
                val rec = parse(raw) ?: continue
                if(rec.deletion) {
                    if(map.remove(rec.name)!=null) changed = true
                    continue
                }
                if(map[rec.name]!=rec.value) {
                    map[rec.name] = rec.value
                    changed = true
                }
            }
            return if(changed) mapToHeader(map) else header
        }

        /** 从 cookie 串里取一项（`store-region` 这类）。 */
        fun value(header: String,name: String): String = headerToMap(header)[name].orEmpty()
    }
}
