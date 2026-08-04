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

    /** 解析/下载 API 强制 User-Agent（kkdo.md） */
    const val API_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/130.0.0.0 Safari/537.36 QuarkPC/6.0.8.649"

    /** 业务 API 基础域名 */
    const val API_BASE = "https://drive-pc.quark.cn"

    /** 获取分享 Token */
    const val SHARE_TOKEN_URL = "$API_BASE/1/clouddrive/share/sharepage/token?pr=ucpro&fr=pc"

    /** 验证分享提取码 */
    const val SHARE_PASSWORD_URL = "$API_BASE/1/clouddrive/share/password?pr=ucpro&fr=pc"

    /** 获取分享文件列表 */
    const val SHARE_DETAIL_URL = "$API_BASE/1/clouddrive/share/sharepage/detail?pr=ucpro&fr=pc"

    /** 获取下载直链 */
    const val DOWNLOAD_URL = "$API_BASE/1/clouddrive/file/download?pr=ucpro&fr=pc&sys=win32&ve=3.23.2"

    /** 根目录 fid */
    const val DEFAULT_PDIR_FID = "0"

    /** 个人网盘文件列表 / 创建目录 */
    const val FILE_URL = "$API_BASE/1/clouddrive/file?pr=ucpro&fr=pc"

    /** 转存分享文件 */
    const val SAVE_URL = "$API_BASE/1/clouddrive/share/sharepage/save?pr=ucpro&fr=pc"

    /** 异步任务查询 */
    const val TASK_URL = "$API_BASE/1/clouddrive/task?pr=ucpro&fr=pc"

    /** 临时转存目录名 */
    const val TEMP_DIR_NAME = "YunX临时转存"

    /** 关键 Cookie 字段，缺失则视为未登录 */
    fun isValidCookie(cookie: String?): Boolean =
        cookie != null && cookie.contains("__pus=") && cookie.contains("__puus=")
}