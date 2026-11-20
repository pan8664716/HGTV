// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.shared.nav

/**
 * 页面清单：两端「有哪些页面、每个页面要什么参数」的唯一来源。
 *
 * ## 它解决什么
 *
 * 改造前，同一件事在两端各写一遍：
 *
 * | | 电视 | 手机 |
 * | --- | --- | --- |
 * | 页面有哪些 | `screen` 上的四个字符串字面量 | 六个 `Activity` 类 |
 * | 详情页要什么 | `openDetail(Series, resume, autoplay)` | 两个 `putExtra` 键 |
 * | 播放页要什么 | `playEpisode(...)` 的四个参数 | 五个 `putExtra` 键 |
 *
 * 任何一边增删参数，另一边都不会被提醒——这正是「改一处忘一处」的典型形态。
 * 现在清单只在这里，两端的适配器（`TvNavigator` / `PhoneNavigator`）对它做**穷尽匹配**：
 * 新增一个页面时两个适配器都会编译失败，编译器替人记住另一端。
 *
 * ## 它不是导航框架
 *
 * 这里**只有页面身份与参数**：没有栈、没有转场、没有生命周期。
 * 电视的「切 `screen` 重建视图树」与手机的「`startActivity` + 系统栈」是两套真实的容器模型，
 * 各自留在自己的适配器里。把容器也抽象掉，就要在两端之间造一个谁的语义都不完全合适的
 * 中间层——这一层刻意不做这件事。
 *
 * ## 什么时候走这里
 *
 * * 跨页跳转：业务代码说「去详情页」，而不是「`startActivity` 到 `PhoneDetailActivity`」；
 * * 只给 id 的入口：进程恢复、将来的深链 / 通知 / 跨端跳转；
 * * 埋点与诊断：[screenName] 是稳定的页面名，两端一致。
 *
 * 电视端那些「手里已经拿着完整 [com.hgtv.star.core.Series] 对象」的跳转**不走这里**：
 * 那个对象带着列表接口才有的 `notice`（「预计11月6日上线」），只传 id 会把它丢掉，
 * 详情页的预告文案会退化。这条差异见 `docs/多端重构-差异矩阵与迁移.md`。
 */
sealed interface Route {

    /**
     * 稳定的页面名：埋点、诊断、`onSaveInstanceState` 都用它。
     *
     * **不要**把它当成电视端那个 `screen` 变量：后者是「当前视图树是哪一棵」的内部状态，
     * 取值里多一个 `catalog`，两者不是一回事（映射在 `TvNavigator` 里）。
     */
    val screenName: String

    /** 内容浏览的根页面：电视是 `catalog`，手机是 `PhoneMainActivity`（各自带自己的 tab 容器）。 */
    data object Home : Route {
        override val screenName = "home"
    }

    /** 推荐流：电视是独立的一层（整屏播放），手机是根页面的默认 tab。 */
    data object Recommend : Route {
        override val screenName = "recommend"
    }

    /**
     * 详情页。
     *
     * [title] 只用于「内容还没到」时先把标题写出来；为空时两端都显示「加载中…」。
     */
    data class Detail(val seriesId: String, val title: String = "") : Route {
        override val screenName = "detail"
    }

    /**
     * 播放页。
     *
     * 四个可选字段都对应手机端真实存在的 `Intent` 附加项，**不传就等同于没设过**——
     * 这一点比字段本身重要，见 [episode]。
     *
     * @property episode 要播第几集（从 0 起）。`null` = 按本机进度续播。
     *   **不能**用 `0` 表示「没指定」：手机端读的是 `getIntExtra(EXTRA_EPISODE, -1)`，
     *   传 0 会被当成「用户指定第 1 集」，续播当场失效。这是这条路由最容易写错的一处。
     * @property positionMs 从这一集的第几毫秒开始；`null` = 由历史合并结果决定。
     * @property fromRecommend 是否从推荐流进来：决定播放页的控制层长什么样
     *   （推荐流进来的没有「选集」按钮）。
     * @property fullscreen 推荐流的「观看完整短剧」用它进横屏全屏。
     * @property noTransition 关掉系统转场。推荐流交接时新窗口还没画出视频，
     *   那 200ms 的入场动画就是用户看到的黑屏。
     */
    data class Player(
        val seriesId: String,
        val title: String = "",
        val episode: Int? = null,
        val positionMs: Long? = null,
        val fromRecommend: Boolean = false,
        val fullscreen: Boolean = false,
        val noTransition: Boolean = false,
    ) : Route {
        override val screenName = "player"
    }

    /** 搜索：电视是 `catalog` 里的一个标签，手机是独立 `Activity`。 */
    data object Search : Route {
        override val screenName = "search"
    }

    /** 设置：电视是 `catalog` 里的一个标签，手机是独立 `Activity`。 */
    data object Settings : Route {
        override val screenName = "settings"
    }

    /** 账号（登录 / 收藏 / 历史）：电视是 `catalog` 的「我的」标签，手机是独立 `Activity`。 */
    data object Account : Route {
        override val screenName = "account"
    }
}
