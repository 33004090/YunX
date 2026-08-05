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
import java.util.UUID
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
    private val formMediaType = "application/x-www-form-urlencoded".toMediaType()

    // ---------- 登录 ----------

    /** 1. 验证码盾初始化，返回 captcha_token（pan 请求需带 X-Captcha-Token） */
    suspend fun initCaptcha(
        deviceId: String,
        username: String,
        action: String = "POST:/auth/signin/token"
    ): String? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("action", action)
            .put("captcha_token", "")
            .put("client_id", XunleiConstants.CLIENT_ID)
            .put("device_id", deviceId)
            .put("meta", JSONObject().put("username", username))
            .put("redirect_uri", "xlaccsdk01://xunlei.com/callback?state=harbor")
            .toString()
        val request = authRequest(XunleiConstants.CAPTCHA_INIT_URL, deviceId, body)
        runCatching {
            client.newCall(request).execute().use { resp ->
                val json = JSONObject(resp.body?.string() ?: "{}")
                json.optString("captcha_token").takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    /** 2. 账号密码登录；成功返回 sessionKey（loginKey），否则可能触发短信验证 */
    suspend fun loginWithPassword(
        username: String,
        password: String,
        deviceId: String,
        captchaToken: String,
        checkCode: String = ""
    ): XunleiLoginStep = withContext(Dispatchers.IO) {
        val body = baseLoginBody(deviceId)
            .put("userName", username)
            .put("passWord", password)
            .put("verifyKey", "")
            .put("verifyCode", checkCode)
            .put("isMd5Pwd", "0")
            .toString()
        val request = authRequest(XunleiConstants.LOGIN_URL, deviceId, body)
        client.newCall(request).execute().use { resp ->
            val json = JSONObject(resp.body?.string() ?: "{}")
            parseLoginResponse(json)
        }
    }

    /** 3a. 发送短信验证码 */
    suspend fun sendSms(mobile: String, deviceId: String): XunleiLoginStep = withContext(Dispatchers.IO) {
        val body = baseLoginBody(deviceId)
            .put("creditkey", "")
            .put("mobile", mobile)
            .put("register", "0")
            .toString()
        val request = authRequest(XunleiConstants.SEND_SMS_URL, deviceId, body)
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

    /** 3b. 短信验证码登录，返回 loginKey（用于换 token） */
    suspend fun smsLogin(
        mobile: String,
        smsCode: String,
        creditKey: String,
        smsToken: String,
        deviceId: String
    ): XunleiLoginStep = withContext(Dispatchers.IO) {
        val body = baseLoginBody(deviceId)
            .put("creditkey", creditKey)
            .put("mobile", mobile)
            .put("smsCode", smsCode)
            .put("token", smsToken)
            .put("register", "0")
            .toString()
        val request = authRequest(XunleiConstants.SMS_LOGIN_URL, deviceId, body)
        client.newCall(request).execute().use { resp ->
            val json = JSONObject(resp.body?.string() ?: "{}")
            parseLoginResponse(json)
        }
    }

    /** 4. 用会话（loginKey/sessionKey）换取 access_token（文档 OAuth2 token 接口） */
    suspend fun exchangeToken(sessionKey: String, deviceId: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        val form = buildString {
            append("grant_type=token")
            append("&client_id=").append(XunleiConstants.CLIENT_ID)
            append("&client_secret=").append(XunleiConstants.CLIENT_SECRET)
            append("&sessionKey=").append(java.net.URLEncoder.encode(sessionKey, "UTF-8"))
            append("&device_id=").append(deviceId)
        }
        val request = Request.Builder()
            .url(XunleiConstants.TOKEN_URL)
            .header("User-Agent", XunleiConstants.APP_UA)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .post(form.toRequestBody(formMediaType))
            .build()
        runCatching {
            client.newCall(request).execute().use { resp ->
                val json = JSONObject(resp.body?.string() ?: "{}")
                val at = json.optString("access_token").ifBlank { json.optString("accessToken") }
                val rt = json.optString("refresh_token").ifBlank { json.optString("refreshToken") }
                if (at.isBlank()) null else at to rt
            }
        }.getOrNull()
    }

    /** 解析 v3/login / v3/smslogin 响应 */
    private fun parseLoginResponse(json: JSONObject): XunleiLoginStep {
        val errorCode = json.optString("errorCode")
        if (errorCode == "0" || json.optString("error") == "success") {
            return XunleiLoginStep(
                needSms = false,
                sessionKey = json.optString("loginKey"),
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

    /** 登录请求公共体（对齐官方 app 抓包字段） */
    private fun baseLoginBody(deviceId: String): JSONObject = JSONObject()
        .put("protocolVersion", "301")
        .put("sequenceNo", Random.nextLong(10000000, 99999999).toString())
        .put("platformVersion", "10")
        .put("isCompressed", "0")
        .put("appid", "40")
        .put("clientVersion", "8.31.0.9726")
        .put("peerID", randomHex(32))
        .put("appName", "ANDROID-com.xunlei.downloadprovider")
        .put("sdkVersion", "512000")
        .put("devicesign", buildDeviceSign(deviceId))
        .put("netWorkType", "WIFI")
        .put("providerName", "NONE")
        .put("deviceModel", "M2004J7AC")
        .put("deviceName", "Xiaomi_M2004j7ac")
        .put("OSVersion", "12")
        .put("creditkey", "")
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
        val request = panRequest(url, accessToken, deviceId, captchaToken)
        parsePan(request) { data ->
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
            .put("space", 1)
            .toString()
        val request = panRequest(XunleiConstants.FILES_URL, accessToken, deviceId, captchaToken, body)
        parsePan(request) { data -> data.optString("id").takeIf { it.isNotBlank() } }
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
        val request = panRequest(url, accessToken, deviceId, captchaToken)
        parsePan(request) { data ->
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
        val request = panRequest(url, accessToken, deviceId, captchaToken)
        parsePan(request) { data ->
            val files = data.optJSONArray("files")?.let(::parseFileArray) ?: emptyList()
            XunleiShareResult(
                title = data.optString("title"),
                files = files,
                passCodeToken = data.optString("pass_code_token"),
                shareId = shareId
            )
        }
    }

    /** 转存到指定目录，返回异步任务 id */
    suspend fun restore(
        shareId: String,
        passCode: String,
        passCodeToken: String,
        parentFolderId: String,
        fileIds: List<String>,
        accessToken: String,
        deviceId: String,
        captchaToken: String
    ): String? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("share_id", shareId)
            .put("pass_code", passCode)
            .put("pass_code_token", passCodeToken)
            .put("parent_folder_id", parentFolderId)
            .put("file_ids", JSONArray().apply { fileIds.forEach { put(it) } })
            .toString()
        val request = panRequest(XunleiConstants.RESTORE_URL, accessToken, deviceId, captchaToken, body)
        parsePan(request) { data ->
            data.optString("task_id").ifBlank { data.optString("taskId") }.takeIf { it.isNotBlank() }
        }
    }

    /** 轮询转存任务（最多 15 次 × 1s） */
    suspend fun pollTask(taskId: String, accessToken: String, deviceId: String, captchaToken: String): Boolean =
        withContext(Dispatchers.IO) {
            val url = "${XunleiConstants.TASKS_URL}/$taskId?type=share"
            for (i in 0 until 15) {
                val done = runCatching {
                    val request = panRequest(url, accessToken, deviceId, captchaToken)
                    client.newCall(request).execute().use { resp ->
                        val json = JSONObject(resp.body?.string() ?: "{}")
                        if (!resp.isSuccessful) return@use false
                        val data = json.optJSONObject("data") ?: json
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

    /** 登录类请求（xluser，普通 JSON + UA） */
    private fun authRequest(url: String, deviceId: String, body: String): Request =
        Request.Builder()
            .url(url)
            .header("User-Agent", XunleiConstants.APP_UA)
            .header("Content-Type", "application/json")
            .header("X-Client-Id", XunleiConstants.CLIENT_ID)
            .header("X-Device-Id", deviceId)
            .header("X-Client-Version", "8.31.0.9726")
            .post(body.toRequestBody(jsonMediaType))
            .build()

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
            .header("X-Client-Id", XunleiConstants.CLIENT_ID)
            .header("client_id", XunleiConstants.CLIENT_ID)
            .header("X-Client-Version", "8.31.0.9726")
            .header("Content-Type", "application/json")
            .header("Origin", "https://pan.xunlei.com")
            .header("Referer", "https://pan.xunlei.com/")
        if (captchaToken.isNotBlank()) builder.header("X-Captcha-Token", captchaToken)
        return if (body != null) builder.post(body.toRequestBody(jsonMediaType)).build()
        else builder.get().build()
    }

    /** pan 响应解析：HTTP 非 2xx 或 error 字段视为业务错误，透传 message */
    private fun <T> parsePan(request: Request, parser: (JSONObject) -> T): T {
        val response = client.newCall(request).execute()
        val body = response.use { it.body?.string() ?: throw QuarkApiException("请求失败：响应为空") }
        val json = runCatching { JSONObject(body) }.getOrElse {
            throw QuarkApiException("响应解析失败")
        }
        if (!response.isSuccessful || json.has("error")) {
            val msg = json.optString("error_description").ifBlank { json.optString("message") }
                .ifBlank { json.optString("error") }.ifBlank { "请求失败" }
            throw QuarkApiException(msg)
        }
        return parser(json.optJSONObject("data") ?: json)
    }

    private fun buildDeviceSign(deviceId: String): String =
        "div101.$deviceId${md5(deviceId)}"

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun randomHex(len: Int): String = buildString {
        repeat(len) { append("0123456789abcdef"[Random.nextInt(16)]) }
    }

    companion object {
        fun newDeviceId(): String = UUID.randomUUID().toString().replace("-", "")
    }
}