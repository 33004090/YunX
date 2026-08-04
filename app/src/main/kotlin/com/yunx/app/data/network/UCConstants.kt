package com.yunx.app.data.network

/**
 * UC 网盘登录与 API 相关常量（依据 uckk.md）。
 * 与夸克网盘共用 API 结构，仅域名、参数、UA 不同。
 */
object UCConstants {

    /** UC 网盘客户端 User-Agent */
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "uc-cloud-drive/1.6.1 Chrome/100.0.4896.160 Electron/18.3.5.16-b62cf9c50d Safari/537.36 Channel/ucpan_other_ch"

    /** WebView 登录页 */
    const val LOGIN_URL = "https://drive.uc.cn/"

    /** 提取 Cookie 的域名 */
    const val COOKIE_DOMAIN = "https://drive.uc.cn"

    /** 验证登录状态的接口 */
    const val ACCOUNT_INFO_URL = "https://drive.uc.cn/account/info"

    /** 业务 API 基础域名 */
    const val API_BASE = "https://pc-api.uc.cn"

    /** 获取分享 Token（与夸克路径相同，仅域名/pr 不同） */
    const val SHARE_TOKEN_URL = "$API_BASE/1/clouddrive/share/sharepage/token?pr=UCBrowser&fr=pc"

    /** 获取分享文件列表（UC 用 v2/detail） */
    const val SHARE_DETAIL_URL = "$API_BASE/1/clouddrive/share/sharepage/v2/detail?pr=UCBrowser&fr=pc"

    /** 获取下载直链 */
    const val DOWNLOAD_URL = "$API_BASE/1/clouddrive/file/download?pr=UCBrowser&fr=pc"

    /** 根目录 fid */
    const val DEFAULT_PDIR_FID = "0"

    /** 个人网盘文件列表 / 创建目录 */
    const val FILE_URL = "$API_BASE/1/clouddrive/file?pr=UCBrowser&fr=pc"

    /** 转存分享文件 */
    const val SAVE_URL = "$API_BASE/1/clouddrive/share/sharepage/save?pr=UCBrowser&fr=pc"

    /** 异步任务查询 */
    const val TASK_URL = "$API_BASE/1/clouddrive/task?pr=UCBrowser&fr=pc"

    /** 临时转存目录名 */
    const val TEMP_DIR_NAME = "YunX临时转存"

    /** 关键 Cookie 字段，缺失则视为未登录（UC 与夸克共用） */
    fun isValidCookie(cookie: String?): Boolean =
        cookie != null && cookie.contains("__pus=") && cookie.contains("__puus=")
}