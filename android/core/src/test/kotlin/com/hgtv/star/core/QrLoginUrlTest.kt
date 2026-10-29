// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 出码 / 轮询的 URL 拼装。
 *
 * 这组用例存在的唯一理由：**这个位置出过一次只在真机上才暴露的事故**。
 *
 * OkHttp 3 → 4 迁移时，`HttpUrl.parse(HOST+path)` 被机械改写成
 * `HOST+path.toHttpUrlOrNull()`，括号丢了。`path` 是相对路径（`/passport/web/get_qrcode/`），
 * `toHttpUrlOrNull()` 返回 null，`HOST + null` 拼出
 * `https://api5-normal-lq.fqnovel.comnull`。
 *
 * 它编译通过、类型正确、单测（当时没有）也发现不了——只有真机点「扫码登录」才会
 * 打到一个不存在的主机上，用户看到的是「无法显示二维码」。
 */
class QrLoginUrlTest {

    private val login = QrLogin(clock = { 0L })

    @Test fun `出码地址是绝对地址且带查询参数`() {
        val url = login.endpoint(
            "/passport/web/get_qrcode/",
            listOf("next" to "https://api5-normal-lq.fqnovel.com/", "aid" to "513755", "language" to "zh"),
        )
        assertNotNull("地址必须解析成功", url)
        assertEquals("https", url!!.scheme)
        assertEquals(QrLogin.HOST.removePrefix("https://"), url.host)
        assertEquals("/passport/web/get_qrcode/", url.encodedPath)
        assertEquals("513755", url.queryParameter("aid"))
        assertEquals("zh", url.queryParameter("language"))
        // `next` 里带的是完整 URL，必须被编码成一个参数值而不是拆成几个。
        assertEquals("https://api5-normal-lq.fqnovel.com/", url.queryParameter("next"))
        // 曾经拼成 `...fqnovel.comnull`：这里把「主机被污染」这件事直接钉死。
        assertEquals("主机名不能带多余后缀", "api5-normal-lq.fqnovel.com", url.host)
    }

    @Test fun `轮询地址带 token`() {
        val url = login.endpoint("/passport/web/check_qrconnect/", listOf("token" to "abc_lq", "aid" to "513755"))
        assertNotNull(url)
        assertEquals("/passport/web/check_qrconnect/", url!!.encodedPath)
        assertEquals("abc_lq", url.queryParameter("token"))
    }

    @Test fun `参数为空时仍然给出正确路径`() {
        val url = login.endpoint("/passport/web/get_qrcode/", emptyList())
        assertNotNull(url)
        assertEquals("/passport/web/get_qrcode/", url!!.encodedPath)
        assertEquals(0, url.querySize)
    }

    @Test fun `空路径不会拼出一个合法地址`() {
        // 防御性：真出现空路径时宁可返回 null（界面显示「接口地址无效」），
        // 也不要把 `HOST + ""` 当成首页去请求。
        assertNull(login.endpoint("", emptyList()))
    }
}
