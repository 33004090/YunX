package com.yunx.app.data.network

import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 夸克 API 封装（OkHttp）：账号验证 + 分享解析 + 下载直链。
 * 注意：当前关闭 SSL 证书校验，仅用于调试/抓包，上线前务必恢复。
 */
class QuarkApi(
    private val client: OkHttpClient = createUnsafeClient()
) {

    companion object {
        /** 信任所有证书的 Client（调试用，禁止用于生产） */
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

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // ---------- 账号 ----------

    suspend fun fetchNickname(cookie: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(QuarkConstants.ACCOUNT_INFO_URL)
            .header("Cookie", cookie)
            .header("User-Agent", QuarkConstants.USER_AGENT)
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                val json = JSONObject(body)
                if (json.optInt("status") == 200) {
                    json.optJSONObject("data")
                        ?.optString("nickname")
                        ?.takeIf { it.isNotBlank() }
                } else null
            }
        }.getOrNull()
    }

    // ---------- 分享解析 ----------

    /** 4.1 获取分享 Token（请求体携带 pwd_id/passcode） */
    suspend fun getShareToken(shareId: String, pwd: String?, cookie: String): ShareToken? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("pwd_id", shareId)
            .put("passcode", pwd ?: "")
            .put("support_visit_limit_private_share", true)
            .toString()
        val request = postJson(QuarkConstants.SHARE_TOKEN_URL, cookie, body)
        parseData(request) { data ->
            ShareToken(
                stoken = data.optString("stoken"),
                title = data.optString("title"),
                firstFid = data.optString("first_fid")
            )
        }
    }

    /** 4.3 验证分享提取码 */
    suspend fun verifySharePassword(shareId: String, passcode: String, cookie: String): Boolean =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("share_id", shareId)
                .put("passcode", passcode)
                .toString()
            val request = postJson(QuarkConstants.SHARE_PASSWORD_URL, cookie, body)
            runCatching {
                client.newCall(request).execute().use { response ->
                    val json = JSONObject(response.body?.string() ?: "{}")
                    json.optInt("status") == 200
                }
            }.getOrDefault(false)
        }

    /** 4.2 获取分享文件列表（sharepage/detail）
     *  官方字段：file_name / size / dir(boolean) / share_fid_token，
     *  与 kkdo.md 文档中的 fname/fsize/isdir/fid_token 不同，以抓包为准。
     */
    suspend fun getShareFiles(
        shareId: String,
        stoken: String,
        pdirFid: String,
        cookie: String,
        page: Int = 1,
        size: Int = 100
    ): List<ShareFile>? = withContext(Dispatchers.IO) {
        // 参数名必须为 pwd_id（值=分享链接短码），并追加 ver=2 / _page / _size 等固定参数
        val url = buildString {
            append(QuarkConstants.SHARE_DETAIL_URL)
            append("&pwd_id=").append(shareId)
            append("&stoken=").append(URLEncoder.encode(stoken, "UTF-8"))
            append("&pdir_fid=").append(pdirFid)
            append("&ver=2")
            append("&force=0")
            append("&_page=").append(page)
            append("&_size=").append(size)
            append("&_fetch_banner=0")
            append("&_fetch_share=0")
            append("&fetch_relate_conversation=0")
            append("&_fetch_total=1")
            append("&_sort=file_type:asc,file_name:asc")
        }
        // 该接口需携带 Origin / Referer，否则可能返回 400
        val request = Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("User-Agent", QuarkConstants.API_USER_AGENT)
            .header("Origin", "https://pan.quark.cn")
            .header("Referer", "https://pan.quark.cn/")
            .get()
            .build()
        parseData(request) { data ->
            val array = data.optJSONArray("list") ?: JSONArray()
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    add(
                        ShareFile(
                            fid = item.optString("fid"),
                            fname = item.optString("file_name"),
                            fsize = item.optLong("size"),
                            isdir = item.optBoolean("dir", false),
                            pdirFid = item.optString("pdir_fid"),
                            fidToken = item.optString("share_fid_token"),
                            modifyTime = item.optString("updated_at")
                        )
                    )
                }
            }
        }
    }

    // ---------- 个人网盘 / 转存 ----------

    /** 7.1 个人网盘文件列表（用于查找/确认临时目录）
     *  注意：个人网盘列表字段为 file_name / size / dir(boolean)，
     *  与分享列表的 fname / fsize / isdir(int) 不同，需做兼容映射。
     */
    suspend fun getFileList(
        pdirFid: String,
        cookie: String,
        page: Int = 1,
        size: Int = 100
    ): List<ShareFile>? = withContext(Dispatchers.IO) {
    val url = "${QuarkConstants.FILE_URL}&pdir_fid=$pdirFid&page=$page&size=$size"
    val request = get(url, cookie)
    parseData(request) { data ->
        val array = data.optJSONArray("list") ?: JSONArray()
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(
                    ShareFile(
                        fid = item.optString("fid"),
                        fname = item.optString("file_name").ifEmpty { item.optString("fname") },
                        fsize = if (item.has("size")) item.optLong("size") else item.optLong("fsize"),
                        isdir = item.optBoolean("dir", false) || item.optInt("isdir") == 1,
                        pdirFid = item.optString("pdir_fid"),
                        fidToken = item.optString("fid_token"),
                        modifyTime = item.optString("modify_time")
                    )
                )
            }
        }
    }
}

    /** 创建目录（个人网盘），返回新目录 fid */
    suspend fun createFolder(name: String, parentFid: String, cookie: String): String? =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("pdir_fid", parentFid)
                .put("file_name", name)
                .put("dir_path", "")
                .put("dir_init_lock", false)
                .toString()
            val request = postJson(QuarkConstants.FILE_URL, cookie, body)
            parseData(request) { data -> data.optString("fid") }
        }

    /** 5. 转存分享文件到个人网盘目录，返回异步任务 id（可能为空）
     *  注意：pwd_id 必须为分享链接短码（非空），并携带 pdir_fid/scene，
     *  否则接口返回 400 Bad Parameter: [pwd_id为空]。
     */
    suspend fun saveShareFile(
        shareId: String,
        stoken: String,
        pdirFid: String,
        fid: String,
        fidToken: String,
        toPdirFid: String,
        cookie: String
    ): String? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("pwd_id", shareId)
            .put("stoken", stoken)
            .put("pdir_fid", pdirFid)
            .put("to_pdir_fid", toPdirFid)
            .put("fid_list", JSONArray().put(fid))
            .put("fid_token_list", JSONArray().put(fidToken))
            .put("scene", "link")
            .toString()
        val request = postJson(QuarkConstants.SAVE_URL, cookie, body)
        parseData(request) { data -> data.optString("task_id").takeIf { it.isNotBlank() } }
    }

    /** 轮询异步转存任务，直到完成或超时（最多 10 次 × 1s）
     *  官方轮询响应：data.status == 2（完成）且带 finished_at；
     *  转存后的新 fid 在 data.save_as.save_as_top_fids[0]（download 必须用它）。
     *  @return 转存后的新 fid；null 表示超时/失败。
     */
    suspend fun pollTask(taskId: String, cookie: String): String? = withContext(Dispatchers.IO) {
        val url = "${QuarkConstants.TASK_URL}&task_id=${URLEncoder.encode(taskId, "UTF-8")}&retry_index=0"
        for (i in 0 until 10) {
            val savedFid = runCatching {
                client.newCall(get(url, cookie)).execute().use { response ->
                    val json = JSONObject(response.body?.string() ?: "{}")
                    if (json.optInt("status") != 200) return@use null
                    val data = json.optJSONObject("data") ?: return@use null
                    // 完成：finished_at > 0 或 status/task_status == 2
                    val finished = data.optLong("finished_at") > 0 ||
                        data.optInt("status") == 2 ||
                        data.optInt("task_status") == 2
                    if (!finished) return@use null
                    data.optJSONObject("save_as")
                        ?.optJSONArray("save_as_top_fids")
                        ?.optString(0)
                        ?.takeIf { it.isNotBlank() }
                }
            }.getOrNull()
            if (savedFid != null) return@withContext savedFid
            delay(1000)
        }
        null
    }

    // ---------- 下载直链 ----------

    /** 6.1 获取下载直链 */
    suspend fun getDownloadLink(fid: String, cookie: String): DownloadLink? = withContext(Dispatchers.IO) {
        val body = JSONObject().put("fids", JSONArray().put(fid)).toString()
        val request = postJson(QuarkConstants.DOWNLOAD_URL, cookie, body)
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val json = JSONObject(response.body?.string() ?: return@use null)
                if (json.optInt("status") != 200) return@use null
                val array = json.optJSONArray("data") ?: return@use null
                if (array.length() == 0) return@use null
                val item = array.optJSONObject(0) ?: return@use null
                DownloadLink(
                    fid = item.optString("fid"),
                    filename = item.optString("filename"),
                    downloadUrl = item.optString("download_url"),
                    size = item.optLong("size")
                )
            }
        }.getOrNull()
    }

    // ---------- 请求构造与响应解析 ----------

    private fun get(url: String, cookie: String): Request =
        Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("User-Agent", QuarkConstants.API_USER_AGENT)
            .get()
            .build()

    private fun postJson(url: String, cookie: String, body: String): Request =
        Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("User-Agent", QuarkConstants.API_USER_AGENT)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(jsonMediaType))
            .build()

    private fun <T> parseData(request: Request, parser: (JSONObject) -> T): T? =
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                val json = JSONObject(body)
                if (json.optInt("status") == 200) {
                    parser(json.optJSONObject("data") ?: return@use null)
                } else {
                    null
                }
            }
        }.getOrNull()
}