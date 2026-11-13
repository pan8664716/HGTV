// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * 往定长 LRU 里写一条：写完把最久没用过的那条挤出去。
 *
 * ## 为什么不是 `object : LinkedHashMap<...> { override fun removeEldestEntry(...) }`
 *
 * 那样写最省事，但 Kotlin 会为这个匿名子类额外生成
 * `getOrDefault(Object,Object)` 与 `remove(Object,Object)` 两个桥方法，方法体是
 * `invoke-super java/util/LinkedHashMap.getOrDefault`。而这两个方法**都是 API 24 才加进
 * `java.util.LinkedHashMap` 的**（Java 8 的 `Map` 默认方法）。在 4.4 上，Dalvik 校验期只能把这条
 * `invoke-super` 换成「一执行就抛 `NoSuchMethodError`」的桩。
 *
 * 只要没人调到桥方法就还活着——但这是一颗**不该留的雷**：调不调用取决于 Kotlin 版本与调用点
 * （`getOrPut` 走 `get`/`put`；`remove(k,v)` 这种 Java 8 签名一旦被用到就直接炸）。
 * `map.containsKey(k) && map[k]==v` 这种写法看着无害，编译出来就可能落到 2 参 `remove` 上。
 *
 * 因此这里统一改成：**普通 `LinkedHashMap` + 手动淘汰**。`accessOrder=true` 时迭代顺序就是
 * 「最久未用在前」，挤掉 `keys` 的第一个等于 `removeEldestEntry` 的效果，语义完全一致，
 * 而类里不再有任何指向 API 24 的 `invoke-super`。
 *
 * @param limit 最多留几条；超过就挤掉最久未用的那一批之外的一条。
 */
fun <K, V> LinkedHashMap<K, V>.lruPut(limit: Int, key: K, value: V): V {
    put(key, value)
    if (limit <= 0) {
        clear()
        return value
    }
    // 一次可能多出来不止一条（调用方可能在循环外批量灌），因此用 while 而不是 if。
    while (size > limit) {
        val eldest = keys.iterator()
        if (!eldest.hasNext()) break
        eldest.next()
        eldest.remove()
    }
    return value
}
