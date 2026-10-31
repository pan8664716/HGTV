// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 播放界面左上角那几项信息的**文案**：电视与手机共用这一份。
 *
 * 四项由同一批落盘开关控制（`playerTitle` / `playerEpisode` / `playerClock` /
 * `playerSpeed`），两端显示的内容也必须一样——早先手机上把「网速」那一项画成了倍速、
 * 集数只写「第 3 集」，电视上却是「第 3 / 171 集」。同名开关显示不同内容，用户没法
 * 拿一端的情况描述另一端的问题。
 *
 * 这里只负责**文字**：电视是一行里四个独立控件（各自显隐），手机是一条拼接起来的行，
 * 排版由各端自己决定。
 */
object PlaybackInfoLine {

    /**
     * 集数：`第 3 / 171 集`。
     *
     * 总数未知（`count <= 0`，例如详情还没回来）时退回 `第 3 集`：
     * 写一个「第 3 / 0 集」比不写总数更糟。
     */
    fun episode(index: Int, count: Int): String {
        val one = (index + 1).coerceAtLeast(1)
        return if (count > 0) "第 $one / $count 集" else "第 $one 集"
    }

    /** 当前时间：`HH:mm`，补零。两端的时钟那一项都走这里。 */
    fun clock(hour: Int, minute: Int): String =
        "%02d:%02d".format(hour.coerceIn(0, 23), minute.coerceIn(0, 59))

    /**
     * 播放位置 / 时长的读数：`mm:ss`（分钟不折进小时，与两端原来的写法一致）。
     *
     * 这一句原先在两端各写了四份（电视 `formatTime`、手机播放页 / 推荐流 / 我的页各一个
     * `clock`）。四份的语义本来就相同（毫秒截断到秒、再拆成分与秒），但任何一处漏了
     * `coerceAtLeast(0)` 就会把 `-1` 显示成 `-1:-1`——播放器在位置未知时正是返回 -1。
     * 现在只有这一份，负数统一按 0 读。
     */
    fun time(millis: Long): String {
        val total = millis.coerceAtLeast(0) / 1000
        return "%02d:%02d".format(total / 60, total % 60)
    }
}
