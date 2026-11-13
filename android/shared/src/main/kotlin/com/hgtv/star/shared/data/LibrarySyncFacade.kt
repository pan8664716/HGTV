// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.data

/** 导出局域网全量同步允许迁移的设备身份与登录会话。 */
fun Library.exportSyncSession(): Map<String,String> {
    return exportSyncSessionImpl()
}

/**
 * 用另一台设备的完整会话覆盖本机白名单字段。
 *
 * 同步语义是「让两台设备尽量一致」，因此来源没有的登录项也要在本机清掉；
 * 这是确认框明确提示的覆盖操作，不沿用镜像恢复那种「只补缺失项」的策略。
 */
fun Library.importSyncSession(
    values: Map<String,String>,
    selectedKeys: Set<String> = LibrarySyncSessionKeys.ALL,
) {
    importSyncSessionImpl(values, selectedKeys)
}
