// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

/**
 * @param profile 这一次注册用的设备档案；默认 [ContentRepository.registerProfile]。
 * @param withIdentity 注册请求里要不要带上本机已有的 `device_id` / `iid`。
 *
 * 「换新身份」时界面层会把**候选档案**（还没被平台接受的那一份）传进来，并把这一项设为
 * `false`：平台按 `device_id` 认设备，请求里带着旧身份就只会**原样返回同一对**数字，
 * 新档案配旧身份，还是对不上号，换新等于没换。不带身份才会真的新建一台设备。
 *
 * **但只去掉 `device_id` 是不够的**（2026-11 实测）：注册主机 `log.snssdk.com` 在
 * `SessionInterceptor` 的会话 host 名单里，它会给这条请求补上
 * `Cookie: install_id=…; sessionid=…` 与 `X-Tt-Token`。服务端按这串 Cookie 认设备，
 * 于是 cdid / openudid / clientudid 全换成新的也照样回 `new_user:0` 与原 `device_id`。
 * 因此 `withIdentity = false` 时额外打一个匿名标记头，让拦截器整条跳过身份补齐。
 */
fun ContentRepository.registerDevice(
    profile: Map<String,String>? = null,
    withIdentity: Boolean = true,
): DeviceRegister.Registered? =
    registerDeviceImpl(profile,withIdentity)

/**
 * 匿名注册一台备用设备，不改动当前主设备身份与 Cookie。
 *
 * 仅供 `playbackBackupDevices` 对应的持久备用池初始化使用。
 */
fun ContentRepository.registerAnonymousDevice(profile: Map<String,String>): DeviceRegister.Registered? =
    registerAnonymousDeviceImpl(profile)

/**
 * 当前注册下发的 Cookie 快照（给界面层落盘用）。
 *
 * 为什么要出去：这份 Cookie 原本只活在内存里，**进程一重启就没了**，于是每个新进程的
 * 第一轮请求都是「没有 `install_id` / `ttreq` 的裸设备」，平台按陌生设备处理（风控更严）。
 * 界面层拿到它写进 `native-session-v1`（键 `registered-cookies`），下次启动再用
 * [restoreDeviceCookies] 灌回来。
 */
fun ContentRepository.deviceCookiesSnapshot(): Map<String,String> =
    deviceCookiesSnapshotImpl()

/** 当前主身份 Cookie 快照；登录后是账号设备 Cookie，未登录时是主设备 Cookie。 */
fun ContentRepository.primaryCookiesSnapshot(): Map<String,String> =
    primaryCookiesSnapshotImpl()

/** 把上次落盘的注册 Cookie 灌回来（启动时调一次即可）。 */
fun ContentRepository.restoreDeviceCookies(cookies: Map<String,String>) =
    restoreDeviceCookiesImpl(cookies)

/**
 * 后台拉取 TNC。
 *
 * TNC 支持匿名 bootstrap：没有 `device_id` / `iid` 时仍可先拿到域名和 HTTP DNS 配置；
 * 设备注册完成后再次刷新，带注册 Cookie 的响应会补充地区信息。
 */
fun ContentRepository.refreshTncAsync(force: Boolean=false): Boolean =
    refreshTncAsyncImpl(force)
