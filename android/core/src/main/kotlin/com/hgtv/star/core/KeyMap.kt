// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 播放界面里可自定义的六个键。
 *
 * **只有这六个**：返回键不在其中——它在任何绑定下都必须是「收起浮层 / 退出播放 / 回主界面」，
 * 一旦允许改掉，用户就可能把自己锁在播放界面里（电视上再没有别的出口）。
 *
 * 浏览页面（首页 / 榜单 / 分类 / 搜索 / 简介）的方向键也不参与：那里上下左右就是焦点移动，
 * 是遥控器的基本手感，改成别的动作整个界面就没法用了。因此按键设置的作用域是**播放界面**，
 * 见 `MainActivity.dispatchKeyEvent` 里那一段。
 */
enum class PlayerKey(val storedValue: String, val label: String) {
    UP("up","上键"),
    DOWN("down","下键"),
    LEFT("left","左键"),
    RIGHT("right","右键"),
    MENU("menu","菜单键"),
    OK("ok","确定键");

    companion object {
        /** 磁盘 / 备份里的值反查；不认识的值返回 null（调用方丢弃这一条）。 */
        fun fromStored(value: String?) = entries.firstOrNull { it.storedValue == value }
    }
}

/**
 * 一个键在播放界面里能做的事。
 *
 * [label] 是设置页那一格上的短名，[hint] 是选择弹窗里的一行说明（「档位名 · 做什么」）：
 * 「控制条」和「播放设置」光看名字分不出差别，说明文字才说得清按下去会发生什么。
 *
 * 顺序就是弹窗里的排列顺序：最常用的四项（播放设置 / 选集 / 控制条 / 播放暂停）排在最前，
 * 「无」永远在最后——它是「把这一格空出来」，不是一个常用动作。
 */
enum class PlayerKeyAction(val storedValue: String, val label: String, val hint: String) {
    SETTINGS("settings","播放设置","播放设置 · 清晰度 / 倍速 / 画面模式 / 弹幕 / 定时"),
    EPISODES("episodes","选集","选集 · 打开底部选集面板，挑一集接着看"),
    HUD("hud","控制条","控制条 · 唤出或收起底部控制条（进度与状态项）"),
    PLAY_PAUSE("playpause","播放 / 暂停","播放 / 暂停 · 切换播放状态"),
    SEEK_BACK("seek_back","快退","快退 · 每次 10 秒，连按 5 次后每次 30 秒"),
    SEEK_FORWARD("seek_forward","快进","快进 · 每次 10 秒，连按 5 次后每次 30 秒"),
    PREVIOUS("previous","上一集","上一集 · 直接切到上一集"),
    NEXT("next","下一集","下一集 · 直接切到下一集"),
    NONE("none","无","无 · 这个键在播放界面不响应");

    companion object {
        /** 磁盘 / 备份里的值反查；不认识的值返回 null（调用方丢弃这一条）。 */
        fun fromStored(value: String?) = entries.firstOrNull { it.storedValue == value }
    }
}

/**
 * 播放界面的按键绑定：六个键各自做什么。
 *
 * 只存**改过的**键（[bindings] 里没有的键一律回落到出厂绑定），因此：
 * 出厂状态就是一个空表；「恢复默认」= 清空这张表；旧版本读到空串时行为与以前完全一致。
 *
 * 绑定本身不做互斥：两个键绑同一个动作是允许的（例如上键与菜单键都开播放设置，
 * 那本来就是出厂的样子）。也不强制「必须留一个键能打开设置」——返回键不参与自定义，
 * 任何绑定下都退得出播放界面，用户不会真的被锁住。
 */
data class KeyMap(private val bindings: Map<PlayerKey,PlayerKeyAction> = emptyMap()) {
    /** 这个键当前的动作：改过的用改过的，没改过的回落到出厂绑定。 */
    fun action(key: PlayerKey): PlayerKeyAction = bindings[key] ?: DEFAULT_BINDINGS.getValue(key)

    /** 改一个键的动作，其余键保持不动。 */
    fun customize(key: PlayerKey, action: PlayerKeyAction) = KeyMap(bindings + (key to action))

    /** 六个键是不是全都是出厂绑定（设置页那一行文案用它）。 */
    val isDefault: Boolean get() = customized == 0

    /**
     * 有几个键改成了与出厂绑定不同的动作（0 = 全默认）。
     *
     * 按「当前动作与出厂绑定是否一致」现算，而不是数 [bindings] 有几条：用户把某个键改出去
     * 又改回出厂动作时，磁盘上那一条虽然还在，手感上就是默认的，读数也该是默认的。
     */
    val customized: Int get() = PlayerKey.entries.count { action(it) != DEFAULT_BINDINGS.getValue(it) }

    /**
     * 落盘 / 进备份的形状：`up:episodes,left:none`，**只写改过的键**，全默认时是空串。
     *
     * 只写差异有两处好处：磁盘上的值短，而且以后新增键位时不会因为「旧文件里少了这一项」
     * 而读出一个错的动作（缺的键本来就该按出厂绑定走）。顺序固定按 [PlayerKey.entries]，
     * 因此同样的绑定总是编码出同一个字符串（备份比对、测试断言都靠这一点）。
     */
    fun encode(): String = PlayerKey.entries.mapNotNull { key ->
        val custom = bindings[key] ?: return@mapNotNull null
        if (custom == DEFAULT_BINDINGS.getValue(key)) null else "${key.storedValue}:${custom.storedValue}"
    }.joinToString(",")

    companion object {
        /**
         * 出厂绑定：与 1.0.8 及更早版本播放界面的行为**逐键一致**
         *（上 / 菜单 = 播放设置、下 = 选集、左 = 快退、右 = 快进、确定 = 播放 / 暂停）。
         * 改这张表等于改所有老用户的手感，除非需求明确要求，否则不要动。
         */
        val DEFAULT_BINDINGS = mapOf(
            PlayerKey.UP to PlayerKeyAction.SETTINGS,
            PlayerKey.DOWN to PlayerKeyAction.EPISODES,
            PlayerKey.LEFT to PlayerKeyAction.SEEK_BACK,
            PlayerKey.RIGHT to PlayerKeyAction.SEEK_FORWARD,
            PlayerKey.MENU to PlayerKeyAction.SETTINGS,
            PlayerKey.OK to PlayerKeyAction.PLAY_PAUSE
        )

        val DEFAULT = KeyMap()

        /**
         * 反解 [encode] 的产物（也用来读旧版本 / 手改过的备份）。
         *
         * 逐条校验、坏值只丢自己那一条：不认识的键名、不认识的动作名、空段、重复段都不影响
         * 其余键——备份文件是用户手里的东西，宁可少认一条也不要整份恢复失败。
         * 空串 / null 表示「全默认」。
         */
        fun decode(value: String?): KeyMap {
            if(value.isNullOrBlank()) return DEFAULT
            val bindings = linkedMapOf<PlayerKey,PlayerKeyAction>()
            value.split(',').forEach { entry ->
                val key = PlayerKey.fromStored(entry.substringBefore(':',"").trim())
                val action = PlayerKeyAction.fromStored(entry.substringAfter(':',"").trim())
                if(key!=null && action!=null) bindings[key]=action
            }
            return KeyMap(bindings)
        }
    }
}
