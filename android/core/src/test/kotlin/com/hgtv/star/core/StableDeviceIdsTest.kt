// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备标识派生的协议测试：钉住「同一个系统标识符永远派生出同一个值」。
 *
 * 这是「卸载重装仍被平台认成同一台设备」的全部依据，所以它必须是**纯函数**、
 * 不依赖 JVM / 语言环境 / 调用次数——同一个种子跑一百次、换一台机器跑，结果都要一样。
 */
class StableDeviceIdsTest {
    private val androidId="a1b2c3d4e5f60718"

    @Test fun derivationIsDeterministicAndShapedLikeThePlatformExpects() {
        val seed=StableDeviceIds.seed(androidId,null,null)
        val openudid=StableDeviceIds.openudid(seed)
        // `openudid` 是 16 位 hex（与平台 query 里的形态一致）。
        assertEquals(16,openudid.length)
        assertTrue("必须是 hex：$openudid",openudid.matches(Regex("[0-9a-f]{16}")))
        // `cdid` / `clientudid` 是带横杠的 UUID（与原先 randomUUID 的形状一致）。
        val cdid=StableDeviceIds.cdid(seed)
        assertTrue("必须是 UUID 形态：$cdid",cdid.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
        val clientudid=StableDeviceIds.clientudid(seed)
        assertTrue(clientudid.matches(Regex("[0-9a-f-]{36}")))
        // 三者互不相同（同一个种子里换名字）。
        assertNotEquals(cdid,clientudid)
        assertNotEquals(openudid,cdid.replace("-","").take(16))
        // 反复调用、换实例都是同一个值。
        repeat(3) {
            assertEquals(openudid,StableDeviceIds.openudid(StableDeviceIds.seed(androidId,null,null)))
            assertEquals(cdid,StableDeviceIds.cdid(StableDeviceIds.seed(androidId,null,null)))
        }
    }

    @Test fun differentDevicesGetDifferentIdentities() {
        val one=StableDeviceIds.seed("a1b2c3d4e5f60718",null,null)
        val two=StableDeviceIds.seed("ffeeddccbbaa9988",null,null)
        assertNotEquals(StableDeviceIds.openudid(one),StableDeviceIds.openudid(two))
        assertNotEquals(StableDeviceIds.cdid(one),StableDeviceIds.cdid(two))
    }

    @Test fun seedPrefersAndroidIdThenSerialThenFingerprint() {
        // `ANDROID_ID` 最稳：有它就不看别的（8.0 起它重装不变）。
        assertEquals("id-1",StableDeviceIds.seed("id-1","serial-1","fp-1"))
        // 读不到 `ANDROID_ID` 时退到 `Build.SERIAL`（8.0 之前可读）。
        assertEquals("serial-1",StableDeviceIds.seed(null,"serial-1","fp-1"))
        assertEquals("serial-1",StableDeviceIds.seed("  ","serial-1","fp-1"))
        // 两个都没有才退到 `FINGERPRINT`（同型号设备会相同，但至少不是随机）。
        assertEquals("fp-1",StableDeviceIds.seed(null,null,"fp-1"))
    }

    @Test fun placeholderAndUnknownIdentifiersAreTreatedAsMissing() {
        // 早期大量设备共用的假 `ANDROID_ID`：当成「没有」，退到下一项。
        assertEquals("serial-1",StableDeviceIds.seed(StableDeviceIds.PLACEHOLDER_ANDROID_ID,"serial-1",null))
        // 系统读不到时给的字面量同样跳过（大小写都认）。
        assertEquals("fp-1",StableDeviceIds.seed("unknown",null,"fp-1"))
        assertEquals("fp-1",StableDeviceIds.seed("UNKNOWN",null,"fp-1"))
    }

    @Test fun noIdentifierAtAllFallsBackToARandomSeed() {
        // 三个都拿不到：只能退回旧行为（每次安装都是新设备），但不能崩、也不能给出空值。
        val one=StableDeviceIds.seed(null,null,null)
        val two=StableDeviceIds.seed("","","unknown")
        assertNotEquals(one,two)
        assertTrue(StableDeviceIds.openudid(one).length==16)
    }

    @Test fun stampTracksTheSeedWithoutLeakingIt() {
        val seed=StableDeviceIds.seed(androidId,null,null)
        val stamp=StableDeviceIds.stamp(seed)
        assertEquals(16,stamp.length)
        // 只用来判「标识符有没有变」：同一个种子同指纹，换了种子就不同。
        assertEquals(stamp,StableDeviceIds.stamp(StableDeviceIds.seed(androidId,null,null)))
        assertNotEquals(stamp,StableDeviceIds.stamp(StableDeviceIds.seed("another-id",null,null)))
        assertTrue("指纹里不能带明文标识符：$stamp",!stamp.contains(androidId.take(8)))
    }
}
