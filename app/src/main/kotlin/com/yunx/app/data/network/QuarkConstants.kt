package com.yunx.app.data.network

/**
 * 夸克网盘登录与 API 相关常量（依据 kk.md）。
 */
object QuarkConstants {

    /** 夸克 PC 客户端 User-Agent，所有请求必须携带 */
    const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
    "Chrome/130.0.0.0 Safari/537.36 QuarkPC/6.0.8.649"

    /** WebView 登录页（PC 环境） */
    const val LOGIN_URL = "https://pan.quark.cn/?fr=pc&platform=pc"

    /** 提取 Cookie 的域名 */
    const val COOKIE_DOMAIN = "https://pan.quark.cn"

    /** 验证登录状态的接口 */
    const val ACCOUNT_INFO_URL = "https://pan.quark.cn/account/info"

    /** 关键 Cookie 字段，缺失则视为未登录 */
    fun isValidCookie(cookie: String?): Boolean =
        cookie != null && cookie.contains("__pus=") && cookie.contains("__puus=")
}