// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.data

import com.hgtv.star.core.AccountCacheEntry
import com.hgtv.star.core.AccountData

fun Library.accountCache(): AccountCacheEntry? {
    return accountCacheImpl()
}

/**
 * 落盘一份账号快照。
 *
 * 只写内存里那份的**替换**（先换内存、再排队落盘）：界面下一次读到的就是这一份，
 * 不必等磁盘。写入走 `LatestWriteQueue`（同一 key 只保留最后一次），
 * 因此播放中反复刷新也不会把磁盘写爆。
 */
fun Library.cacheAccount(data: AccountData, now: Long = System.currentTimeMillis()) {
    cacheAccountImpl(data, now)
}

/** 清掉账号快照（退出登录、换账号、导入新凭据时调用）。 */
fun Library.clearAccountCache() {
    clearAccountCacheImpl()
}
