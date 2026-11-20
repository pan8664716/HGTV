// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.phone

/**
 * 正式播放页导航入口。
 *
 * Manifest 为这个入口固定不透明主题，窗口从创建时就不是 TRANSLUCENT；
 * 播放页内部即使有透明弹幕 SurfaceView，也不会露出推荐页或简介页。
 */
class PhonePlayerOpaqueActivity : PhonePlayerActivity()
