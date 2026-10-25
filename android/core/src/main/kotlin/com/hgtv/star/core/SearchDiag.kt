// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 上一次搜索到底发生了什么。
 *
 * 为什么需要：搜索失败在界面上永远只有一句「没有找到相关剧集」，而这句**可能意味着四件完全不同的事**：
 *
 * * 关键词确实没有匹配（服务端正常回了 0 条）；
 * * 这台设备没在服务端注册过（App 接口回 HTTP 200 + 空响应体）；
 * * 接口回了非 0 的业务码（限流 / 参数 / 风控）；
 * * 网络本身没通（DNS / 超时 / HTTP 5xx）。
 *
 * 这四种在用户那边看起来一模一样，客服只能反复问「换个词试试」。因此这里把每一次搜索的
 * **请求参数、返回码、结果条数、失败类型**记一份，由 [explain] 翻成一句结论，
 * 调试面板直接显示这句结论——而不是把页面上的文案抄一遍。
 *
 * 只保留最后一次（[last]）：面板是「现在为什么不好用」，不是历史报表；历史仍在 `DebugLog` 里。
 */
object SearchDiag {
    /** 一次搜索的现场。字段都是不可变的，读取方拿到的永远是一份一致的快照。 */
    data class Outcome(
        val at: Long,
        val keyword: String,
        val page: Int,
        val source: SearchSource,
        val resultCount: Int,
        /** 失败类型（空 = 成功）。用于判读，不是给用户看的原文。 */
        val failure: String,
        /** 接口细节：业务码 / HTTP 状态 / 异常链，已经压成一行。 */
        val detail: String,
        /** 发起这次搜索时本机是否已注册（App 搜索的前提）。 */
        val registered: Boolean,
        /** App 搜索接口这一进程里是否还活着（false = 空响应判死）。 */
        val alive: Boolean?,
        /**
         * 成功但有瑕疵时的补充说明（当前只有官网搜索用）。
         *
         * 典型场景：10 个物理页里有 3 页重试后仍回 502——整页不算失败（其余 7 页照常出内容），
         * 但「少掉的那 3 页」必须让用户看见，否则面板上只有「有结果 70 条」和一行 502。
         */
        val note: String = ""
    )

    @Volatile var last: Outcome? = null

    /** 记录一次搜索。调用方在**结果或异常落地的那一刻**调一次。 */
    fun record(
        keyword: String,
        page: Int,
        source: SearchSource,
        resultCount: Int,
        failure: String = "",
        detail: String = "",
        registered: Boolean = false,
        alive: Boolean? = null,
        note: String = ""
    ) {
        last=Outcome(
            at=System.currentTimeMillis(),
            keyword=keyword.take(40),page=page,source=source,
            resultCount=resultCount,failure=failure,detail=detail.take(200),
            registered=registered,alive=alive,note=note.take(120)
        )
    }

    /**
     * 把现场翻成一句结论。
     *
     * 判读顺序是刻意的：**先看有没有失败**（失败时的 0 条不代表「没有匹配」），
     * 再看「0 条」的成因。因此这句结论不会把「设备没注册」说成「没有找到相关剧集」。
     */
    fun explain(outcome: Outcome? = last): String {
        val o=outcome ?: return "还没有搜索过"
        val where="${o.source.label} · 第${o.page}页"
        if(o.failure.isNotEmpty()) {
            val reason=when(o.failure) {
                FAILURE_DEVICE -> "设备未注册（接口只回空响应）"
                FAILURE_EXPIRED -> "搜索游标过期（回到第 1 页重搜即可）"
                FAILURE_CODE -> "接口返回非 0 业务码${if(o.detail.isEmpty()) "" else " · ${o.detail}"}"
                FAILURE_TAB -> "该类型没有搜索 tab（${o.detail}）"
                FAILURE_FORMAT -> "接口返回格式异常（${o.detail}）"
                FAILURE_NETWORK -> "网络失败（${o.detail}）"
                FAILURE_HTTP -> "官网返回 HTTP ${o.detail}"
                FAILURE_PARSE -> "官网页面解析失败（${o.detail}）"
                else -> o.detail.ifEmpty { o.failure }
            }
            return "$where 搜索失败 · $reason"
        }
        // 部分失败（官网搜索某些物理页重试后仍 502）：结果照常给，但要说明少了哪部分。
        if(o.note.isNotEmpty()) return "$where 有结果 ${o.resultCount} 条 · ${o.note}"
        if(o.resultCount>0) return "$where 有结果 ${o.resultCount} 条"
        // 0 条且没有失败：这时候才轮到「确实没有匹配」，但要先排掉两个「接口没真正回答」的情形。
        if(o.source==SearchSource.APP && !o.registered) return "$where 0 条 · 设备未注册，接口可能没真正回答"
        if(o.source==SearchSource.APP && o.alive==false) return "$where 0 条 · App 搜索接口已判死（空响应）"
        if(o.source==SearchSource.WEB) return "$where 0 条 · 官网查无此剧（关键词没有匹配）"
        return "$where 0 条 · 服务端正常回答，关键词没有匹配"
    }

    /**
     * 把异常翻成「失败类型 + 细节」。
     *
     * 判读按异常类型走，**不看 message 里的中文**：界面文案会改，异常类型不会。
     * 官网那条路的 HTTP 码藏在 `IOException("内容请求失败（HTTP 500）")` 里，只能从 message 里取，
     * 因此这里单独用正则捞一次数字。
     */
    fun classify(problem: Throwable): Pair<String,String> = when(problem) {
        is DeviceNotRegisteredException -> FAILURE_DEVICE to "空响应体"
        is SearchSessionExpiredException -> FAILURE_EXPIRED to "游标缺失"
        is SearchApiException -> problem.reason to problem.message.orEmpty().take(120)
        is java.net.UnknownHostException -> FAILURE_NETWORK to "DNS 解析失败"
        is java.net.SocketTimeoutException -> FAILURE_NETWORK to "连接超时"
        is java.net.ConnectException -> FAILURE_NETWORK to "连接被拒绝"
        is java.io.IOException -> {
            val http=Regex("HTTP (\\d{3})").find(problem.message.orEmpty())?.groupValues?.getOrNull(1)
            if(http!=null) FAILURE_HTTP to http else FAILURE_NETWORK to chain(problem)
        }
        else -> FAILURE_FORMAT to chain(problem)
    }

    /** 异常链压成一行：只留类名与 message，够判读又不撑爆面板。 */
    private fun chain(problem: Throwable): String {
        val parts=mutableListOf<String>(); var current: Throwable?=problem; var guard=0
        while(current!=null && guard++<3) {
            val name=current.javaClass.simpleName
            val message=current.message?.replace('\n',' ')?.take(80).orEmpty()
            parts+=if(message.isEmpty()) name else "$name: $message"
            current=current.cause
        }
        return parts.joinToString(" ← ").take(160)
    }

    const val FAILURE_DEVICE="device"
    const val FAILURE_EXPIRED="expired"
    const val FAILURE_CODE="code"
    const val FAILURE_TAB="tab"
    const val FAILURE_FORMAT="format"
    const val FAILURE_NETWORK="network"
    const val FAILURE_HTTP="http"
    const val FAILURE_PARSE="parse"
}
