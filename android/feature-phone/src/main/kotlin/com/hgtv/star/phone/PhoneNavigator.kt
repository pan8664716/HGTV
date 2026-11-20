// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.hgtv.star.shared.nav.Navigator
import com.hgtv.star.shared.nav.Route

/**
 * 手机端的路由落地：把 [Route] 翻译成一个 `Intent`，交给系统返回栈。
 *
 * ## 为什么值得把这段收进来
 *
 * 改造前，「进详情页」这句话在九个文件里各写一遍，每次都要重复两个 `putExtra`：
 *
 * ```
 * PhoneDetailActivity / PhoneCardMenu / PhoneRankPage / PhoneCategoryPage
 * / PhoneMinePage / PhoneSearchActivity / PhonePlayerActivity / PhoneFeedView ×2
 * ```
 *
 * 「进播放页」更麻烦：五个附加项散在四处，其中 `EXTRA_EPISODE` 与
 * `EXTRA_RECOMMEND` 只在部分路径上传——少传一个，播放页的表现就不一样
 * （续播变成从头播、选集按钮消失）。这类「少写一行就变行为」的代码不该复制九份。
 *
 * 现在只有一个地方回答「详情页要什么、播放页要什么」。
 *
 * ## 与电视端的分工
 *
 * 这里只做「把参数装进 `Intent`」。真正的差异（窗口、转场、返回栈）由系统与本模块的
 * `Activity` 自己负责，导航器不掺和。
 */
class PhoneNavigator(private val context: Context) : Navigator {

    override fun open(route: Route): Boolean {
        val intent = when (route) {
            // 推荐流就是根页面的默认 tab，两者落到同一个 Activity。
            // `CLEAR_TOP | SINGLE_TOP` = 回到已经存在的那个实例，并把它上面的页面全部结束，
            // 也就是「回首页」；不带这两个标志会再压一个实例上去。
            Route.Home, Route.Recommend -> Intent(context, PhoneMainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

            is Route.Detail -> Intent(context, PhoneDetailActivity::class.java)
                .putExtra(PhoneDetailActivity.EXTRA_SERIES_ID, route.seriesId)
                .putExtra(PhoneDetailActivity.EXTRA_TITLE, route.title)

            is Route.Player -> playerIntent(route)

            Route.Search -> Intent(context, PhoneSearchActivity::class.java)

            // 设置页本身分四栏，路由只表达「去设置」，落到第一栏（播放交互）。
            // 需要指定栏目的调用方仍走 `PhoneSettingsActivity.open(context, page)`。
            Route.Settings -> Intent(context, PhoneSettingsActivity::class.java)
                .putExtra(PhoneSettingsActivity.EXTRA_PAGE, PhoneSettingsPage.PLAYBACK.name)

            Route.Account -> Intent(context, PhoneAccountActivity::class.java)
        }
        // 从 View / Service 这类非 Activity 的 Context 发起时必须带 NEW_TASK，
        // 否则 4.4 上直接抛 `AndroidRuntimeException: Calling startActivity() from
        // outside of an Activity context requires the FLAG_ACTIVITY_NEW_TASK flag`。
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        // 手机端每个页面都是一个 Activity，参数只在 `Intent` 里，不存在「打不开」：
        // 页面自己会去取数据，取不到就画自己的错误态。
        return true
    }

    /**
     * 播放页的 `Intent`。
     *
     * **每一个附加项都只在真的需要时才写**，因为播放页读它们用的是「带默认值的读取」：
     *
     * * `getIntExtra(EXTRA_EPISODE, -1)` —— 写 0 等于「用户指定第 1 集」，续播失效；
     * * `getBooleanExtra(EXTRA_RECOMMEND, false)` / `EXTRA_FULLSCREEN` —— 写 false 与不写等价，
     *   但不写能让 `dumpsys` 里的 Intent 少两个噪音键。
     */
    private fun playerIntent(route: Route.Player): Intent =
        Intent(context, PhonePlayerOpaqueActivity::class.java).apply {
            putExtra(PhonePlayerActivity.EXTRA_SERIES_ID, route.seriesId)
            putExtra(PhonePlayerActivity.EXTRA_TITLE, route.title)
            route.episode?.let { putExtra(PhonePlayerActivity.EXTRA_EPISODE, it) }
            route.positionMs?.let { putExtra(PhonePlayerActivity.EXTRA_POSITION_MS, it) }
            if (route.fromRecommend) putExtra(PhonePlayerActivity.EXTRA_RECOMMEND, true)
            if (route.fullscreen) putExtra(PhonePlayerActivity.EXTRA_FULLSCREEN, true)
            // 推荐流交接专用：不要系统转场，见 [Route.Player.noTransition]。
            if (route.noTransition) addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
}

/**
 * 一句 `context.phoneNav().open(Route.Detail(id, title))` 就能跳页。
 *
 * 导航器无状态（只持有一个 `Context`），按需新建即可，不必在 Activity 里存字段。
 */
fun Context.phoneNav(): Navigator = PhoneNavigator(this)
