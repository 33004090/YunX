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
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/**
 * 139 网盘 API 封装（OkHttp）。
 * 登录态：cookie（含账号信息，authorization 可选）。
 * 分享解析（§15，7.13+）：share-kd-njs.yun.139.com
 *   - 列目录 getOutLinkInfoV6（pCaID:"root"/父coID，passwd 提取码）
 *   - 下载 dlFromOutLinkV3（coIDLst.item:[coID] → data.redrUrl OBS 直链，900s）
 * 分享接口请求/响应均经 AES-CBC 加密（§14）：base64(IV(16B) ‖ AES_CBC(KEY=PVGDwmcvfs1uV3d1, IV, 明文))；
 * mcloud-sign 按「明文 body」计算（§4），加密只是传输包装；mcloud-skey 可省略。
 */
class C139Api(
    private val client: OkHttpClient = createUnsafeClient()
) {

    private val jsonMediaType = "application/json;charset=UTF-8".toMediaType()

    private val shareAesKey: SecretKeySpec =
        SecretKeySpec(C139Constants.SHARE_AES_KEY.toByteArray(Charsets.UTF_8), "AES")

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
     * 注意：签名必须基于与实际发送一致的「明文 JSON」字符串（字段顺序、无空格）。
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

    // ---------- §14 分享接口 AES-CBC 加解密（固定密钥 + IV 前置） ----------

    /** 明文 JSON → 加密 base64（IV(16B) 前置） */
    private fun encryptBody(plaintext: String): String {
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, shareAesKey, IvParameterSpec(iv))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    /** 加密 base64 → 明文 JSON；解密后若为 gzip（首 2 字节 0x1f 0x8b）先解压（alist YunCrypto 同款） */
    private fun decryptBody(b64: String): String {
        val raw = Base64.decode(b64, Base64.NO_WRAP)
        val iv = raw.copyOfRange(0, 16)
        val ct = raw.copyOfRange(16, raw.size)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, shareAesKey, IvParameterSpec(iv))
        var d = cipher.doFinal(ct)  // PKCS5Padding 自动去填充
        // alist YunCrypto 同款：解密后若为 gzip 则解压（首 2 字节 0x1f 0x8b）
        if (d.size > 2 && d[0] == 0x1f.toByte() && d[1] == 0x8b.toByte()) {
            d = GZIPInputStream(ByteArrayInputStream(d)).use { it.readBytes() }
        }
        return String(d, Charsets.UTF_8)
    }

    // ---------- 分享解析（§15，7.13+ 加密） ----------

    /**
     * 分享列目录：getOutLinkInfoV6 —— 官方为「匿名」调用（§9530修复文档 §2/§3）：
     * 不带 authorization、不带 mcloud-sign、不带 mcloud-* 头；body account 固定空串；
     * 带完整字段（caSrt/coSrt/srtDr/bNum/eNum），否则 9530；passwd 填错返回 9188。
     * @param pcaId 根目录传 "root"（不能为空，§16.2），子目录传父目录 coID
     * @param passwd 提取码（无则空串）
     * @return data.coLst[] → ShareFile（fid=coID）
     */
    suspend fun getShareFiles(
        linkId: String,
        pcaId: String,
        passwd: String
    ): List<ShareFile>? = withContext(Dispatchers.IO) {
        val req = JSONObject()
            .put("account", "")            // 列表端点 account 必须为空串，且本调用不带鉴权头（§3）
            .put("linkID", linkId)
            .put("passwd", passwd)
            .put("caSrt", 1)               // 排序：目录按创建时间
            .put("coSrt", 1)               // 排序：文件按创建时间
            .put("srtDr", 0)               // 排序方向：降序
            .put("bNum", 1)                // 分页起始
            .put("pCaID", pcaId)
            .put("eNum", 200)              // 分页大小
        val plain = JSONObject().put("getOutLinkInfoReq", req).toString()
        val respJson = sharePostAnonymous(C139Constants.SHARE_LIST_URL, plain)
        val resultCode = respJson.optString("resultCode")
        if (resultCode.isNotBlank() && resultCode != "0") {
            throw IllegalStateException(respJson.optString("desc").ifBlank { "获取文件列表失败（$resultCode）" })
        }
        if (!respJson.optBoolean("success", true)) {
            throw IllegalStateException(respJson.optString("desc").ifBlank { "获取文件列表失败" })
        }
        val data = respJson.optJSONObject("data") ?: return@withContext null
        val array = data.optJSONArray("coLst") ?: return@withContext emptyList()
        (0 until array.length()).map { i ->
            val item = array.optJSONObject(i)
            ShareFile(
                fid = item.optString("coID"),
                fname = item.optString("coName"),
                fsize = item.optLong("coSize"),
                isdir = item.optBoolean("isdir", item.optInt("coType", 1) == 2),
                pdirFid = pcaId,
                fidToken = "",
                modifyTime = ""
            )
        }
    }

    /**
     * 分享下载：dlFromOutLinkV3 → data.redrUrl（OBS S3 签名直链，900s 有效）。
     * @param coId Step1 列目录得到的 coID
     */
    suspend fun getShareDownloadLink(
        coId: String,
        linkId: String,
        account: String,
        authorization: String?
    ): DownloadLink? = withContext(Dispatchers.IO) {
        val reqV3 = JSONObject()
            .put("account", account)
            .put("linkID", linkId)
            .put("coIDLst", JSONObject().put("item", JSONArray().put(coId)))
            .put(
                "commonAccountInfo",
                JSONObject().put("account", account).put("accountType", 1)
            )
        val plain = JSONObject().put("dlFromOutLinkReqV3", reqV3).toString()
        val respJson = sharePostEncrypted(C139Constants.SHARE_LINK_URL, plain, authorization)
        val resultCode = respJson.optString("resultCode")
        if (resultCode.isNotBlank() && resultCode != "0") {
            throw IllegalStateException(respJson.optString("desc").ifBlank { "获取下载链接失败（$resultCode）" })
        }
        if (!respJson.optBoolean("success", true)) {
            throw IllegalStateException(respJson.optString("desc").ifBlank { "获取下载链接失败" })
        }
        val data = respJson.optJSONObject("data") ?: return@withContext null
        val url = data.optString("redrUrl")
        if (url.isBlank()) return@withContext null
        DownloadLink(
            fid = coId,
            filename = data.optString("fileName").ifEmpty { data.optString("coName").ifEmpty { coId } },
            downloadUrl = url,
            size = data.optLong("coSize", data.optLong("size"))
        )
    }

    // ---------- 请求构造与响应解析 ----------

    /**
     * 匿名 POST（列表端点 getOutLinkInfoV6 专用，§9530修复文档 §3/§5 + hcy-cool-flag 修复）：
     * 不带 Authorization、不带 mcloud-sign、不带 mcloud-* 头；body 仍加密；
     * 必须带 hcy-cool-flag: 1（139 网关选择解密方案的开关，缺它业务层拿不到明文 → 9530）；
     * 响应解密（兼容明文透传）。
     */
    private fun sharePostAnonymous(url: String, plainBody: String): JSONObject {
        val encrypted = encryptBody(plainBody)
        val request = Request.Builder()
            .url(url)
            .header("hcy-cool-flag", "1")
            .header("x-deviceinfo", C139Constants.SHARE_X_DEVICEINFO)
            .header("x-huawei-channelsrc", C139Constants.SHARE_X_HUAWEI_CHANNELSRC)
            .header("x-mm-source", C139Constants.SHARE_X_MM_SOURCE)
            .header("Content-Type", "application/json;charset=UTF-8")
            .header("User-Agent", C139Constants.SHARE_MOBILE_UA)
            .header("Origin", "https://yun.139.com")
            .header("Referer", "https://yun.139.com/")
            .header("Accept", "application/json, text/plain, */*")
            .post(encrypted.toRequestBody(jsonMediaType))
            .build()
        val response = client.newCall(request).execute()
        val body = response.use { it.body?.string() ?: throw IllegalStateException("请求失败：响应为空") }
        // 响应体应为加密 base64（§14）；网关透传明文时兜底
        return runCatching { JSONObject(decryptBody(body)) }.getOrElse { JSONObject(body) }
    }

    /**
     * 分享专用 POST（§14/§15 + hcy-cool-flag 修复）：body 加密发送，mcloud-sign 按明文算，
     * 必须带 hcy-cool-flag: 1（网关解密开关，缺它业务层拿不到明文 → 9530），响应解密（兼容明文透传）。
     */
    private fun sharePostEncrypted(url: String, plainBody: String, authorization: String?): JSONObject {
        val encrypted = encryptBody(plainBody)
        val request = Request.Builder()
            .url(url)
            .apply { if (!authorization.isNullOrBlank()) header("Authorization", authorization) }
            .header("hcy-cool-flag", "1")
            .header("x-deviceinfo", C139Constants.SHARE_X_DEVICEINFO)
            .header("x-huawei-channelsrc", C139Constants.SHARE_X_HUAWEI_CHANNELSRC)
            .header("x-mm-source", C139Constants.SHARE_X_MM_SOURCE)
            .header("mcloud-sign", signHeader(plainBody))
            .header("Content-Type", "application/json;charset=UTF-8")
            .header("User-Agent", C139Constants.SHARE_MOBILE_UA)
            .header("Origin", "https://yun.139.com")
            .header("Referer", "https://yun.139.com/")
            .header("Accept", "application/json, text/plain, */*")
            .post(encrypted.toRequestBody(jsonMediaType))
            .build()
        val response = client.newCall(request).execute()
        val body = response.use { it.body?.string() ?: throw IllegalStateException("请求失败：响应为空") }
        // 响应体应为加密 base64（§14）；网关透传明文时兜底
        return runCatching { JSONObject(decryptBody(body)) }.getOrElse { JSONObject(body) }
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