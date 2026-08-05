package com.yunx.app.data.network

/**
 * 迅雷网盘常量（依据抓包 + 迅雷网盘API文档，两者互相印证）。
 */
object XunleiConstants {

    /** 登录 / 验证码 / Token 主机 */
    const val AUTH_BASE = "https://xluser-ssl.xunlei.com"

    /** 文件 / 分享 / 下载主机 */
    const val PAN_BASE = "https://api-pan.xunlei.com"

    /** Web 端公开凭据（文档推荐，可正常换 Token） */
    const val CLIENT_ID = "Xp6pAdwyJv9sQuoN"
    const val CLIENT_SECRET = "standard_a@api#"

    /** App UA（官方 app 抓包） */
    const val APP_UA =
        "ANDROID-com.xunlei.downloadprovider/8.31.0.9726 netWorkType/5G appid/40 " +
            "deviceName/Xiaomi_M2004j7ac deviceModel/M2004J7AC OSVersion/12 protocolVersion/301 " +
            "platformVersion/10 sdkVersion/512000 Oauth2Client/0.9 (Linux 4_14_186-perf-gddfs8vbb238b) (JAVA 0)"

    /** 浏览器 UA（Web 端 pan 请求） */
    const val WEB_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

    // ---------- 登录端点 ----------

    /** 验证码盾初始化 */
    const val CAPTCHA_INIT_URL = "$AUTH_BASE/v1/shield/captcha/init"

    /** 账号密码登录（xluser 会话） */
    const val LOGIN_URL = "$AUTH_BASE/xluser.core.login/v3/login"

    /** 发送短信验证码 */
    const val SEND_SMS_URL = "$AUTH_BASE/xluser.core.login/v3/sendsms"

    /** 短信验证码登录 */
    const val SMS_LOGIN_URL = "$AUTH_BASE/xluser.core.login/v3/smslogin"

    /** 换取 access_token（OAuth2） */
    const val TOKEN_URL = "$AUTH_BASE/v1/auth/token"

    // ---------- Pan 端点 ----------

    /** 文件列表 / 详情 / 建目录 */
    const val FILES_URL = "$PAN_BASE/drive/v1/files"

    /** 分享解析（GET ?share_id=&pass_code=&limit=&page_token=&thumbnail_size=） */
    const val SHARE_URL = "$PAN_BASE/drive/v1/share"

    /** 转存（POST） */
    const val RESTORE_URL = "$PAN_BASE/drive/v1/share/restore"

    /** 异步任务轮询（GET /tasks/{taskId}?type=share） */
    const val TASKS_URL = "$PAN_BASE/drive/v1/tasks"

    /** 转存目标目录名 */
    const val TEMP_DIR_NAME = "YunX临时转存"
}