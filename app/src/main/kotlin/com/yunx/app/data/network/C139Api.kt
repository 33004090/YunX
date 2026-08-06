package com.yunx.app.data.network

import android.util.Base64
import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 139 网盘 API 封装（OkHttp）。
 * 登录态：authorization（网页版直接给，§3.5.5）或 mail_cookies（fast login 换，§3.4）。
 * 分享解析（§12）：share-kd-njs.yun.139.com 列目录 getOutLinkInfoV6 + 直链 getContentInfoFromOutLink，
 * 所有请求带 mcloud-sign 签名（§4）+ §9 请求头。
 */
class C139Api(
    private val client: OkHttpClient = createUnsafeClient()
) {

    private val jsonMediaType = "application/json".toMediaType()

    // ---------- mcloud-sign 签名（§4） ----------

    private fun md5(s: String): String {
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /** §4.1：encodeURIComponent（+→%20，并还原 ! ' ( ) *） */
    private fun encodeURIComponent(s: String): String =
        URLEncoder.encode(s, "UTF-8")
            .replace("+", "%20")
            .replace("%21", "!")
            .replace("%27", "'")
            .replace("%28", "(")
            .replace("%29", ")")
            .replace("%2A", "*")

    /**
     * §4.2 calSign：
     * body' 单字符 ASCII 升序 → base64 → md5(base64) + md5(ts:rand) → md5 → upper。
     * 注意：签名必须基于与实际发送完全一致的 body 字符串（字段顺序、无空格）。
     */
    fun calSign(bodyJson: String, ts: String, rand: String): String {
        val encoded = encodeURIComponent(bodyJson)
        val sorted = encoded.toCharArray().sorted().joinToString("")
        val b64 = Base64.encodeToString(sorted.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val res = md5(b64) + md5("$ts:$rand")
        return md5(res).uppercase()
    }

    /** 生成 mcloud-sign 头值：<ts>,<rand>,<sign>；ts 格式 YYYY-MM-DD HH:MM:SS，rand 16 位字母数字 */
    fun signHeader(bodyJson: String): String {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val rand = buildString {
            val pool = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            repeat(16) { append(pool.random()) }
        }
        return "$ts,$rand,${calSign(bodyJson, ts, rand)}"
    }

    /**
     * 从 authorization（"Basic base64(pc:账号:authToken)"）解码账号。
     * §3.2 最终态：Authorization = base64("pc:<account>:<authToken>")。
     */
    fun accountFromAuthorization(authorization: String): String? = runCatching {
        val b64 = authorization.removePrefix("Basic").trim()
        val decoded = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
        decoded.split(":").getOrNull(1)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    // ---------- 分享解析（§7/§8/§12，host: share-kd-njs.yun.139.com） ----------

    /** 分享列目录：getOutLinkInfoV6；pCaID 为空表示根目录；authorization 可空（分享接口可能不需要登录态） */
    suspend fun getShareFiles(
        linkId: String,
        pcaId: String,
        account: String,
        authorization: String?
    ): List<ShareFile>? = withContext(Dispatchers.IO) {
        val req = JSONObject()
            .put("account", account)
            .put("linkID", linkId)
            .put("pCaID", pcaId)
        val body = JSONObject().put("getOutLinkInfoReq", req).toString()
        val request = sharePost(C139Constants.SHARE_LIST_URL, authorization, body)
        val json = executeJson(request)
        if (!json.optBoolean("success", false)) {
            throw IllegalStateException(json.optString("message").ifBlank { "获取文件列表失败" })
        }
        val data = json.optJSONObject("data") ?: return@withContext null
        val array = data.optJSONArray("files") ?: return@withContext emptyList()
        (0 until array.length()).map { i ->
            val item = array.optJSONObject(i)
            ShareFile(
                fid = item.optString("contentId"),
                fname = item.optString("name"),
                fsize = item.optLong("fileSize", item.optLong("size")),
                isdir = item.optBoolean("isdir", false),
                pdirFid = pcaId,
                fidToken = "",
                modifyTime = item.optString("modifyTime")
            )
        }
    }

    /** 分享取直链：getContentInfoFromOutLink → DownloadURL / PresentURL */
    suspend fun getShareDownloadLink(
        contentId: String,
        linkId: String,
        account: String,
        authorization: String?
    ): DownloadLink? = withContext(Dispatchers.IO) {
        val req = JSONObject()
            .put("contentId", contentId)
            .put("linkID", linkId)
            .put("account", account)
        val body = JSONObject().put("getContentInfoFromOutLinkReq", req).toString()
        val request = sharePost(C139Constants.SHARE_LINK_URL, authorization, body)
        val json = executeJson(request)
        if (!json.optBoolean("success", false)) {
            throw IllegalStateException(json.optString("message").ifBlank { "获取下载链接失败" })
        }
        val data = json.optJSONObject("data") ?: return@withContext null
        val url = data.optString("DownloadURL").ifEmpty { data.optString("PresentURL") }
            .ifEmpty { data.optString("downloadURL") }
        if (url.isBlank()) return@withContext null
        DownloadLink(
            fid = contentId,
            filename = data.optString("filename").ifEmpty { contentId },
            downloadUrl = url,
            size = data.optLong("Size", data.optLong("size"))
        )
    }

    // ---------- 请求构造与响应解析 ----------

    /** 分享专用 POST：§9 请求头 + mcloud-sign + 可选 Authorization */
    private fun sharePost(url: String, authorization: String?, body: String): Request =
        Request.Builder()
            .url(url)
            .apply {
                if (!authorization.isNullOrBlank()) header("Authorization", authorization)
            }
            .header("mcloud-sign", signHeader(body))
            .header("mcloud-channel", "1000101")
            .header("mcloud-client", "10701")
            .header("mcloud-version", "7.14.0")
            .header("CMS-DEVICE", "default")
            .header("x-m4c-caller", "PC")
            .header("x-m4c-src", "10002")
            .header("x-SvcType", "1")
            .header("Origin", "https://yun.139.com")
            .header("Referer", "https://yun.139.com/w/")
            .header("x-DeviceInfo", "||9|7.14.0|chrome|120.0.0.0|||windows 10||zh-CN|||")
            .header("Inner-Hcy-Router-Https", "1")
            .header("User-Agent", C139Constants.PC_UA)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(jsonMediaType))
            .build()

    private fun executeJson(request: Request): JSONObject {
        val response = client.newCall(request).execute()
        val body = response.use { it.body?.string() ?: throw IllegalStateException("请求失败：响应为空") }
        return runCatching { JSONObject(body) }.getOrElse {
            throw IllegalStateException("响应解析失败")
        }
    }

    companion object {
        /** 信任所有证书的 Client（调试/抓包用，与 QuarkApi/BaiduApi/XunleiApi 一致；上线前应改回默认校验） */
        fun createUnsafeClient(): OkHttpClient {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, SecureRandom())
            return OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .build()
        }
    }
}