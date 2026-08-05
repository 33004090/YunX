package com.yunx.app.data.network

import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.random.Random

/** 迅雷分享解析结果 */
data class XunleiShareResult(
    val title: String,
    val files: List<ShareFile>,
    val passCodeToken: String,
    val shareId: String
)

/** 迅雷登录中间结果 */
data class XunleiLoginStep(
    val needSms: Boolean = false,     // 是否需要短信验证
    val smsCreditKey: String = "",    // sendsms 返回的 creditkey
    val smsToken: String = "",        // sendsms 返回的 token
    val sessionKey: String = "",      // 登录成功的会话（loginKey）
    val sessionId: String = "",       // 登录成功的 sessionID（换 token 用 signin_token）
    val nickname: String = "",
    val userID: String = "",
    val message: String = ""
)

/**
 * 迅雷 API 封装（OkHttp）：
 * - 登录：captcha/init → v3/login（密码，可能触发短信）→ sendsms → smslogin → v1/auth/token
 * - Pan：文件列表 / 分享解析 / 转存 / 直链（Bearer 认证，无需 x-signature，抓包确认）
 */
class XunleiApi(
    private val client: OkHttpClient = QuarkApi.createUnsafeClient()
) {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** captcha_invalid 时刷新出的新 captcha_token（后续请求优先使用） */
    @Volatile
    private var refreshedCaptcha: String? = null

    /** 当前用户 ID（从 access_token JWT 解析，captcha init 的 meta 需要） */
    @Volatile
    private var currentUserId: String = ""

    // ---------- 登录 ----------

    /** 1. 验证码盾初始化，返回 captcha_token（换 token 与 pan 请求需带 X-Captcha-Token）。
     * 官方抓包：client_id 用 app 凭据 Xp6vsxz_7IYVw2BB */
    suspend fun initCaptcha(
        deviceId: String,
        username: String,
        action: String = "POST:/auth/signin/token"
    ): String? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("action", action)
            .put("captcha_token", "")
            .put("client_id", XunleiConstants.APP_CLIENT_ID)
            .put("device_id", deviceId)
            .put("meta", JSONObject().put("username", username))
            .put("redirect_uri", "xlaccsdk01://xunlei.com/callback?state=harbor")
            .toString()
        val request = Request.Builder()
            .url(XunleiConstants.CAPTCHA_INIT_URL)
            .header("User-Agent", XunleiConstants.APP_UA)
            .header("Accept", "application/json;charset=UTF-8")
            .header("Content-Type", "application/json")
            .header("X-Client-Id", XunleiConstants.APP_CLIENT_ID)
            .header("X-Device-Id", deviceId)
            .header("X-Client-Version", "8.31.0.9726")
            .post(body.toRequestBody(jsonMediaType))
            .build()
        runCatching {
            client.newCall(request).execute().use { resp ->
                val json = JSONObject(resp.body?.string() ?: "{}")
                json.optString("captcha_token").takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    /** 2. 账号密码登录（官方首次登录：creditkey=""，sdk UA）。
     * 新客户端第一次登录必然返回 review_panel(1007) → 走短信验证；不要填任何 creditkey/captcha。 */
    suspend fun loginWithPassword(
        username: String,
        password: String,
        deviceId: String,
        checkCode: String = ""
    ): XunleiLoginStep = withContext(Dispatchers.IO) {
        val body = baseLoginBody(deviceId, "25.0.5.25", "513006")
            .put("userName", username)
            .put("passWord", password)
            .put("verifyKey", "")
            .put("verifyCode", checkCode)
            .put("isMd5Pwd", "0")
            .toString()
        // 官方抓包 v3/login 头：sdk UA + content-type，无 Cookie，无 x-device-id/x-client-id
        val request = Request.Builder()
            .url(XunleiConstants.LOGIN_URL)
            .header("User-Agent", "android-ok-http-client/xl-acc-sdk/version-5.1.3.513006")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(jsonMediaType))
            .build()
        client.newCall(request).execute().use { resp ->
            val json = JSONObject(resp.body?.string() ?: "{}")
            parseLoginResponse(json)
        }
    }

    /** 3a. 发送短信验证码（官方：UA=xl-acc-sdk/version-5.0.12.512000，creditkey=""，无 Cookie） */
    suspend fun sendSms(mobile: String, deviceId: String): XunleiLoginStep = withContext(Dispatchers.IO) {
        val body = baseLoginBody(deviceId, "8.31.0.9726", "231500")
            .put("mobile", mobile)
            .put("register", "0")
            .toString()
        val request = Request.Builder()
            .url(XunleiConstants.SEND_SMS_URL)
            .header("User-Agent", "android-ok-http-client/xl-acc-sdk/version-5.0.12.512000")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(jsonMediaType))
            .build()
        client.newCall(request).execute().use { resp ->
            val json = JSONObject(resp.body?.string() ?: "{}")
            XunleiLoginStep(
                needSms = true,
                smsCreditKey = json.optString("creditkey"),
                smsToken = json.optString("token"),
                message = json.optString("errorDesc").ifBlank { "短信已发送" }
            )
        }
    }

    /** 3b. 短信验证码登录（官方：UA=xl-acc-sdk/version-5.0.12.512000，body 带 creditkey/token，无 Cookie） */
    suspend fun smsLogin(
        mobile: String,
        smsCode: String,
        creditKey: String,
        smsToken: String,
        deviceId: String
    ): XunleiLoginStep = withContext(Dispatchers.IO) {
        val body = baseLoginBody(deviceId, "8.31.0.9726", "231500", creditKey)
            .put("mobile", mobile)
            .put("smsCode", smsCode)
            .put("token", smsToken)
            .put("register", "0")
            .toString()
        val request = Request.Builder()
            .url(XunleiConstants.SMS_LOGIN_URL)
            .header("User-Agent", "android-ok-http-client/xl-acc-sdk/version-5.0.12.512000")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(jsonMediaType))
            .build()
        client.newCall(request).execute().use { resp ->
            val json = JSONObject(resp.body?.string() ?: "{}")
            parseLoginResponse(json)
        }
    }

    /** 兜底：OAuth2 密码直换 token（官方抓包证实此路径不存在，v3/login + signin_token 才是正路） */
    suspend fun loginWithTokenPassword(
        username: String,
        password: String,
        deviceId: String,
        captchaToken: String,
        checkCode: String = ""
    ): Pair<String, String>? = null

    /** 4. 用 v3/smslogin 返回的 sessionID 换取 access_token（官方抓包 POST /v1/auth/signin/token）。
     * body：{"client_id":"Xp6vsxz_7IYVw2BB","client_secret":"Xp6vsy4tN9toTVdMSpomVdXpRmES",
     *        "provider":"access_end_point_token","signin_token":"<sessionID>"}
     * 请求头必须带 X-Captcha-Token（captcha/init 返回）。 */
    suspend fun exchangeToken(sessionId: String, deviceId: String, captchaToken: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("client_id", XunleiConstants.APP_CLIENT_ID)
            .put("client_secret", XunleiConstants.APP_CLIENT_SECRET)
            .put("provider", "access_end_point_token")
            .put("signin_token", sessionId)
            .toString()
        val builder = Request.Builder()
            .url(XunleiConstants.TOKEN_URL)
            .header("User-Agent", XunleiConstants.APP_UA)
            .header("Accept", "application/json;charset=UTF-8")
            .header("Content-Type", "application/json")
            .header("X-Client-Id", XunleiConstants.APP_CLIENT_ID)
            .header("X-Device-Id", deviceId)
            .header("X-Client-Version", "8.31.0.9726")
        if (captchaToken.isNotBlank()) builder.header("X-Captcha-Token", captchaToken)
        val request = builder.post(body.toRequestBody(jsonMediaType)).build()
        runCatching {
            client.newCall(request).execute().use { resp ->
                val json = JSONObject(resp.body?.string() ?: "{}")
                val at = json.optString("access_token").ifBlank { json.optString("accessToken") }
                val rt = json.optString("refresh_token").ifBlank { json.optString("refreshToken") }
                if (at.isBlank()) null else {
                    // 缓存 user_id（JWT sub），captcha/init 的 meta 需要
                    jwtSub(at).takeIf { it.isNotBlank() }?.let { currentUserId = it }
                    at to rt
                }
            }
        }.getOrNull()
    }

    /** 缓存当前用户 ID（从 access_token JWT 解析），供 captcha/init 的 meta 使用 */
    fun cacheUserId(accessToken: String) {
        if (currentUserId.isBlank()) currentUserId = jwtSub(accessToken)
    }

    /** 从 JWT 中解析 sub（用户 ID） */
    private fun jwtSub(token: String): String = runCatching {
        val payload = token.split(".").getOrNull(1) ?: return@runCatching ""
        val json = String(
            android.util.Base64.decode(payload, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING)
        )
        JSONObject(json).optString("sub")
    }.getOrDefault("")

    /** 解析 v3/login / v3/smslogin 响应 */
    private fun parseLoginResponse(json: JSONObject): XunleiLoginStep {
        val errorCode = json.optString("errorCode")
        if (errorCode == "0" || json.optString("error") == "success") {
            return XunleiLoginStep(
                needSms = false,
                sessionKey = json.optString("loginKey"),
                sessionId = json.optString("sessionID"),
                nickname = json.optString("nickName"),
                userID = json.optString("userID"),
                message = "登录成功"
            )
        }
        // 触发验证面板（review_panel）→ 需要短信验证
        val error = json.optString("error")
        val needSms = error == "review_panel" || errorCode == "1007" ||
            json.optString("verifyType") == "MEA" || json.optString("verifyType").isNotBlank()
        return XunleiLoginStep(
            needSms = needSms,
            message = json.optString("errorDesc").ifBlank { json.optString("error_description") }
        )
    }

    /** 登录请求公共体（对齐官方 app 抓包字段；peerID/devicesign 用官方真实设备标识） */
    private fun baseLoginBody(
        deviceId: String,
        clientVersion: String,
        sdkVersion: String,
        creditKey: String = ""
    ): JSONObject = JSONObject()
        .put("protocolVersion", "301")
        .put("sequenceNo", Random.nextLong(10000000, 99999999).toString())
        .put("platformVersion", "10")
        .put("isCompressed", "0")
        .put("appid", "40")
        .put("clientVersion", clientVersion)
        .put("peerID", XunleiConstants.PEER_ID) // 官方真实 peerID
        .put("appName", "ANDROID-com.xunlei.downloadprovider")
        .put("sdkVersion", sdkVersion)
        .put("devicesign", XunleiConstants.DEVICE_SIGN) // 官方真实设备指纹
        .put("netWorkType", "WIFI")
        .put("providerName", "NONE")
        .put("deviceModel", "M2004J7AC")
        .put("deviceName", "Xiaomi_M2004j7ac")
        .put("OSVersion", "12")
        .put("creditkey", creditKey)
        .put("hl", "zh-CN")

    // ---------- Pan ----------

    /** 文件列表（个人网盘，parent_id 为空=根目录） */
    suspend fun getFiles(
        parentId: String,
        accessToken: String,
        deviceId: String,
        captchaToken: String
    ): List<ShareFile>? = withContext(Dispatchers.IO) {
        val filters = java.net.URLEncoder.encode("""{"trashed":{"eq":false}}""", "UTF-8")
        val url = buildString {
            append(XunleiConstants.FILES_URL)
            append("?parent_id=").append(parentId)
            append("&page_token=&limit=50&with_audit=true&filters=").append(filters)
        }
        panCall(captchaToken, deviceId, "GET:/drive/v1/files", { t ->
            panRequest(url, accessToken, deviceId, t)
        }) { data ->
            data.optJSONArray("files")?.let(::parseFileArray) ?: emptyList()
        }
    }

    /** 创建文件夹（个人网盘），返回新文件夹 id */
    suspend fun createFolder(
        name: String,
        parentId: String,
        accessToken: String,
        deviceId: String,
        captchaToken: String
    ): String? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("kind", "drive#folder")
            .put("name", name)
            .put("parent_id", parentId)
            .put("space", "") // 官方 proto 要求字符串，数字会 400
            .toString()
        panCall(captchaToken, deviceId, "POST:/drive/v1/files", { t ->
            panRequest(XunleiConstants.FILES_URL, accessToken, deviceId, t, body)
        }) { data -> data.optString("id").takeIf { it.isNotBlank() } }
    }

    /** 文件详情（返回下载直链 links.application/octet-stream.url） */
    suspend fun getFileDetail(
        fileId: String,
        accessToken: String,
        deviceId: String,
        captchaToken: String
    ): DownloadLink? = withContext(Dispatchers.IO) {
        val url = "${XunleiConstants.FILES_URL}/$fileId?_magic=2021&usage=PLAY&thumbnail_size=SIZE_LARGE" +
            "&with=hdr10&with=subtitle_files&with=task&with=public_share_tag"
        panCall(captchaToken, deviceId, "GET:/drive/v1/files/$fileId", { t ->
            panRequest(url, accessToken, deviceId, t)
        }) { data ->
            val links = data.optJSONObject("links")
            val urlStr = links?.optJSONObject("application/octet-stream")?.optString("url")
                ?: data.optString("web_content_link")
                ?: ""
            DownloadLink(
                fid = data.optString("id"),
                filename = data.optString("name"),
                downloadUrl = urlStr,
                size = data.optLong("size")
            )
        }
    }

    /** 分享解析（share_id + 可选 pass_code） */
    suspend fun getShare(
        shareId: String,
        passCode: String,
        accessToken: String,
        deviceId: String,
        captchaToken: String
    ): XunleiShareResult? = withContext(Dispatchers.IO) {
        val url = buildString {
            append(XunleiConstants.SHARE_URL)
            append("?share_id=").append(shareId)
            append("&pass_code=").append(java.net.URLEncoder.encode(passCode, "UTF-8"))
            append("&limit=100&page_token=&thumbnail_size=SIZE_SMALL")
        }
        panCall(captchaToken, deviceId, "GET:/drive/v1/share", { t ->
            panRequest(url, accessToken, deviceId, t)
        }) { data ->
            val files = data.optJSONArray("files")?.let(::parseFileArray) ?: emptyList()
            XunleiShareResult(
                title = data.optString("title"),
                files = files,
                passCodeToken = data.optString("pass_code_token"),
                shareId = shareId
            )
        }
    }

    /** 分享子目录文件列表（share/detail，parent_id + pass_code_token） */
    suspend fun getShareDetail(
        shareId: String,
        parentId: String,
        passCodeToken: String,
        accessToken: String,
        deviceId: String,
        captchaToken: String
    ): List<ShareFile>? = withContext(Dispatchers.IO) {
        val url = buildString {
            append(XunleiConstants.SHARE_DETAIL_URL)
            append("?share_id=").append(shareId)
            append("&parent_id=").append(parentId)
            append("&pass_code_token=").append(java.net.URLEncoder.encode(passCodeToken, "UTF-8"))
            append("&limit=100&page_token=&thumbnail_size=SIZE_SMALL")
        }
        panCall(captchaToken, deviceId, "GET:/drive/v1/share/detail", { t ->
            panRequest(url, accessToken, deviceId, t)
        }) { data ->
            data.optJSONArray("files")?.let(::parseFileArray) ?: emptyList()
        }
    }

    /** 转存到指定目录（官方同步返回 RESTORE_COMPLETE + trace_file_ids 映射），返回转存后的新文件 id */
    suspend fun restore(
        shareId: String,
        passCodeToken: String,
        parentFolderId: String,
        fileIds: List<String>,
        accessToken: String,
        deviceId: String,
        captchaToken: String
    ): String? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("share_id", shareId)
            .put("pass_code_token", passCodeToken)
            .put("parent_id", parentFolderId)
            .put("ancestor_ids", JSONArray())
            .put("file_ids", JSONArray().apply { fileIds.forEach { put(it) } })
            .put("specify_parent_id", true)
            .toString()
        panCall(captchaToken, deviceId, "POST:/drive/v1/share/restore", { t ->
            panRequest(XunleiConstants.RESTORE_URL, accessToken, deviceId, t, body)
        }) { data ->
            // params.trace_file_ids 是 JSON 字符串：{"分享文件id":"转存后新id"}
            val trace = data.optJSONObject("params")?.optString("trace_file_ids").orEmpty()
            runCatching {
                val map = JSONObject(trace)
                fileIds.firstOrNull { map.has(it) }
                    ?.let { map.optString(it) }
                    ?.takeIf { it.isNotBlank() }
            }.getOrNull()
                ?: data.optString("file_id").takeIf { it.isNotBlank() }
        }
    }

    /** 批量删除文件（转存后的临时文件；直链已自带签名，删除不影响下载） */
    suspend fun batchDelete(
        ids: List<String>,
        accessToken: String,
        deviceId: String,
        captchaToken: String
    ): Boolean = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("ids", JSONArray().apply { ids.forEach { put(it) } })
            .put("space", "")
            .toString()
        panCall(captchaToken, deviceId, "POST:/drive/v1/files:batchDelete", { t ->
            panRequest("${XunleiConstants.FILES_URL}:batchDelete", accessToken, deviceId, t, body)
        }) { true }
    }

    /** 轮询转存任务（最多 15 次 × 1s） */
    suspend fun pollTask(taskId: String, accessToken: String, deviceId: String, captchaToken: String): Boolean =
        withContext(Dispatchers.IO) {
            val url = "${XunleiConstants.TASKS_URL}/$taskId?type=share"
            for (i in 0 until 15) {
                val done = runCatching {
                    panCall(captchaToken, deviceId, "GET:/drive/v1/tasks/$taskId", { t ->
                        panRequest(url, accessToken, deviceId, t)
                    }) { data ->
                        val status = data.optString("status").ifBlank { data.optString("phase") }
                        status == "PHASE_TYPE_COMPLETE" || data.optInt("error_code") == 0
                    }
                }.getOrDefault(false)
                if (done) return@withContext true
                delay(1000)
            }
            false
        }

    /** 确保「YunX临时转存」目录存在，返回其 id */
    suspend fun ensureTempDir(
        accessToken: String,
        deviceId: String,
        captchaToken: String
    ): String? = withContext(Dispatchers.IO) {
        val root = getFiles("", accessToken, deviceId, captchaToken) ?: emptyList()
        root.firstOrNull { it.isdir && it.fname == XunleiConstants.TEMP_DIR_NAME }?.fid
            ?: createFolder(XunleiConstants.TEMP_DIR_NAME, "", accessToken, deviceId, captchaToken)
    }

    // ---------- 请求构造 ----------

    private fun parseFileArray(array: JSONArray): List<ShareFile> = buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            add(
                ShareFile(
                    fid = item.optString("id"),
                    fname = item.optString("name"),
                    fsize = item.optLong("size"),
                    isdir = item.optString("kind") == "drive#folder",
                    pdirFid = item.optString("parent_id"),
                    fidToken = "",
                    modifyTime = item.optString("modified_time")
                )
            )
        }
    }

    /** pan 请求（Bearer + 设备 + captcha 头，抓包确认无 x-signature） */
    private fun panRequest(
        url: String,
        accessToken: String,
        deviceId: String,
        captchaToken: String,
        body: String? = null
    ): Request {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", XunleiConstants.WEB_UA)
            .header("Authorization", "Bearer $accessToken")
            .header("X-Device-Id", deviceId)
            .header("X-Client-Version", "8.31.0.9726")
            .header("Content-Type", "application/json")
            .header("Origin", "https://pan.xunlei.com")
            .header("Referer", "https://pan.xunlei.com/")
        if (captchaToken.isNotBlank()) builder.header("X-Captcha-Token", captchaToken)
        return if (body != null) builder.post(body.toRequestBody(jsonMediaType)).build()
        else builder.get().build()
    }

    /** pan 请求带验证码自动刷新重试：失败 captcha_invalid → 用旧 token 换新 token → 重试一次（对齐官方） */
    private suspend fun <T> panCall(
        captchaToken: String,
        deviceId: String,
        action: String,
        build: (String) -> Request,
        parse: (JSONObject) -> T
    ): T {
        var token = refreshedCaptcha ?: captchaToken
        repeat(2) { attempt ->
            val response = client.newCall(build(token)).execute()
            val body = response.use { it.body?.string() ?: throw QuarkApiException("请求失败：响应为空") }
            val json = runCatching { JSONObject(body) }.getOrElse {
                throw QuarkApiException("响应解析失败")
            }
            if (!response.isSuccessful || json.has("error")) {
                val err = json.optString("error")
                if (err == "captcha_invalid" && attempt == 0) {
                    // 用正确 action + captcha_sign 重新 init（携带旧 token），拿 723 长度有效 token 后重试
                    val newToken = initPanCaptcha(deviceId, action, token)
                    if (!newToken.isNullOrBlank()) {
                        refreshedCaptcha = newToken
                        token = newToken
                        return@repeat
                    }
                }
                val msg = json.optString("error_description").ifBlank { json.optString("message") }
                    .ifBlank { err }.ifBlank { "请求失败" }
                throw QuarkApiException(msg)
            }
            return parse(json.optJSONObject("data") ?: json)
        }
        throw QuarkApiException("验证码刷新后仍失败")
    }

    /** 用请求对应 action + 正确 captcha_sign 初始化 captcha（pan 专用，alist 算法已验证）。
     * 算法：raw = client_id+client_version+package_name+device_id+timestamp_ms，10 层 md5(raw+salt)，sign="1."+结果 */
    private suspend fun initPanCaptcha(deviceId: String, action: String, oldToken: String): String? =
        withContext(Dispatchers.IO) {
            val ts = System.currentTimeMillis().toString()
            val sign = buildCaptchaSign(deviceId, ts)
            val body = JSONObject()
                .put("client_id", XunleiConstants.APP_CLIENT_ID)
                .put("action", action)
                .put("device_id", deviceId)
                .put("redirect_uri", "xlaccsdk01://xunlei.com/callback?state=harbor")
                .put(
                    "meta",
                    JSONObject()
                        .put("client_version", XunleiConstants.APP_CLIENT_VERSION)
                        .put("package_name", XunleiConstants.APP_PACKAGE_NAME)
                        .put("timestamp", ts)
                        .put("captcha_sign", sign)
                        .put("user_id", currentUserId) // 真实 user_id，空会得到降级 token（POST 类接口拒绝）
                )
                .put("captcha_token", oldToken)
                .toString()
            val request = Request.Builder()
                .url(XunleiConstants.CAPTCHA_INIT_URL)
                .header("User-Agent", XunleiConstants.APP_UA)
                .header("Accept", "application/json;charset=UTF-8")
                .header("Content-Type", "application/json")
                .header("X-Client-Id", XunleiConstants.APP_CLIENT_ID)
                .header("X-Device-Id", deviceId)
                .header("X-Client-Version", "8.31.0.9726")
                .post(body.toRequestBody(jsonMediaType))
                .build()
            runCatching {
                client.newCall(request).execute().use { resp ->
                    val json = JSONObject(resp.body?.string() ?: "{}")
                    json.optString("captcha_token").takeIf { it.isNotBlank() }
                }
            }.getOrNull()
        }

    /** captcha_sign：client_id+client_version+package_name+device_id+timestamp_ms → 10 层 md5(raw+salt)，前缀 "1." */
    private fun buildCaptchaSign(deviceId: String, tsMs: String): String {
        var h = XunleiConstants.APP_CLIENT_ID + XunleiConstants.APP_CLIENT_VERSION +
            XunleiConstants.APP_PACKAGE_NAME + deviceId + tsMs
        for (salt in XunleiConstants.CAPTCHA_SALTS) {
            h = md5Hex(h + salt)
        }
        return "1.$h"
    }

    private fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** 设备 ID：复用官方抓包真实设备（devicesign 前半一致，保证设备指纹有效） */
        fun newDeviceId(): String = XunleiConstants.DEVICE_ID
    }
}