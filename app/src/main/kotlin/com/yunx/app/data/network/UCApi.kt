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

/**
 * UC 网盘 API 封装（OkHttp）：账号验证 + 分享解析 + 下载直链。
 * 与夸克 API 结构一致，仅域名/UA/pr 参数不同。
 */
class UCApi(
    private val client: OkHttpClient = QuarkApi.createUnsafeClient()
) {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // ---------- 账号 ----------

    suspend fun fetchNickname(cookie: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(UCConstants.ACCOUNT_INFO_URL)
            .header("Cookie", cookie)
            .header("User-Agent", UCConstants.USER_AGENT)
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                val json = JSONObject(body)
                if (json.optBoolean("success", false)) {
                    json.optJSONObject("data")
                        ?.optString("nickname")
                        ?.takeIf { it.isNotBlank() }
                } else null
            }
        }.getOrNull()
    }

    // ---------- 分享解析 ----------

    suspend fun getShareToken(shareId: String, pwd: String?, cookie: String): ShareToken? = withContext(Dispatchers.IO) {
        // 官方抓包：body 为 pwd_id/passcode/share_for_transfer（用于转存/下载场景）
        val body = JSONObject()
            .put("pwd_id", shareId)
            .put("passcode", pwd ?: "")
            .put("share_for_transfer", true)
            .toString()
        val request = postJson(UCConstants.SHARE_TOKEN_URL, cookie, body)
        parseData(request) { data ->
            ShareToken(
                stoken = data.optString("stoken"),
                title = data.optString("title"),
                firstFid = data.optString("first_fid")
            )
        }
    }

    /**
     * 获取分享文件列表（sharepage/v2/detail，UC 官方为 POST + JSON body）。
     * 官方抓包：body 携带 pwd_id/passcode/page/size/fetch_banner 等，不携带 stoken；
     * 进入子目录时 body 追加 pdir_fid。
     */
    /**
     * 获取转存分享文件列表（transfer_share/detail，官方下载流程）。
     * GET + query 携带 stoken → 返回的 share_fid_token 与 stoken 绑定，download 才能通过校验。
     */
    suspend fun getTransferShareFiles(
        shareId: String,
        stoken: String,
        pdirFid: String,
        cookie: String,
        page: Int = 1,
        size: Int = 50
    ): List<ShareFile>? = withContext(Dispatchers.IO) {
        val url = buildString {
            append(UCConstants.TRANSFER_SHARE_DETAIL_URL)
            append("&pwd_id=").append(shareId)
            append("&pdir_fid=").append(pdirFid)
            append("&fetch_file_list=1")
            append("&passcode=")
            append("&_page=").append(page)
            append("&_size=").append(size)
            append("&_fetch_total=1")
            append("&_fetch_task=1")
            append("&_fetch_share=1")
            append("&_sort=")
            append("&stoken=").append(URLEncoder.encode(stoken, "UTF-8"))
        }
        val request = Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("User-Agent", UCConstants.USER_AGENT)
            .header("Origin", "https://fast.uc.cn")
            .header("Referer", "https://fast.uc.cn/")
            .get()
            .build()
        parseData(request) { data ->
            // 兼容 data.list 或 data.detail_info.list 两种结构
            val array = data.optJSONArray("list")
                ?: data.optJSONObject("detail_info")?.optJSONArray("list")
                ?: JSONArray()
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

    suspend fun getShareFiles(
        shareId: String,
        pwd: String?,
        pdirFid: String,
        cookie: String,
        page: Int = 1,
        size: Int = 50
    ): List<ShareFile>? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("pwd_id", shareId)
            .put("passcode", pwd ?: "")
            .put("force", 0)
            .put("page", page)
            .put("size", size)
            .put("fetch_banner", 1)
            .put("fetch_share", 1)
            .put("fetch_total", 1)
            .put("sort", "file_type:asc,file_name:asc")
            .put("banner_platform", "other")
            .put("web_platform", "windows")
            .put("fetch_error_background", 1)
        // 子目录时追加 pdir_fid（根目录官方不传）
        if (pdirFid.isNotBlank() && pdirFid != UCConstants.DEFAULT_PDIR_FID) {
            body.put("pdir_fid", pdirFid)
        }
        val request = Request.Builder()
            .url("${UCConstants.SHARE_DETAIL_URL}&ve=2.5.20")
            .header("Cookie", cookie)
            .header("User-Agent", UCConstants.USER_AGENT)
            .header("Origin", "https://drive.uc.cn")
            .header("Referer", "https://drive.uc.cn/")
            .header("Content-Type", "application/json;charset=UTF-8")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()
        parseData(request) { data ->
            // UC v2/detail：文件列表在 data.detail_info.list（不是 data.list）
            val detailInfo = data.optJSONObject("detail_info")
            val array = detailInfo?.optJSONArray("list") ?: JSONArray()
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

    suspend fun getFileList(
        pdirFid: String,
        cookie: String,
        page: Int = 1,
        size: Int = 100
    ): List<ShareFile>? = withContext(Dispatchers.IO) {
        val url = "${UCConstants.FILE_URL}&pdir_fid=$pdirFid&page=$page&size=$size"
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

    suspend fun createFolder(name: String, parentFid: String, cookie: String): String? =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("pdir_fid", parentFid)
                .put("file_name", name)
                .put("dir_path", "")
                .put("dir_init_lock", false)
                .toString()
            val request = postJson(UCConstants.FILE_URL, cookie, body)
            parseData(request) { data -> data.optString("fid") }
        }

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
        val request = postJson(UCConstants.SAVE_URL, cookie, body)
        parseData(request) { data -> data.optString("task_id").takeIf { it.isNotBlank() } }
    }

    suspend fun pollTask(taskId: String, cookie: String): String? = withContext(Dispatchers.IO) {
        val url = "${UCConstants.TASK_URL}&task_id=${URLEncoder.encode(taskId, "UTF-8")}&retry_index=0"
        for (i in 0 until 10) {
            val savedFid = runCatching {
                client.newCall(get(url, cookie)).execute().use { response ->
                    val json = JSONObject(response.body?.string() ?: "{}")
                    if (json.optInt("status") != 200) return@use null
                    val data = json.optJSONObject("data") ?: return@use null
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

    /**
     * UC 官方下载流程（抓包）：不需要先转存！
     * POST file/download?entry=ft&fr=pc&pr=UCBrowser
     * body: {"fids":[分享fid],"pwd_id":短码,"stoken":token接口返回,"fids_token":[分享fid_token]}
     */
    suspend fun getShareDownloadLink(
        fid: String,
        fidToken: String,
        stoken: String,
        pwdId: String,
        cookie: String
    ): DownloadLink? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("fids", JSONArray().put(fid))
            .put("pwd_id", pwdId)
            .put("stoken", stoken)
            .put("fids_token", JSONArray().put(fidToken))
            .toString()
        val request = postJson(UCConstants.DOWNLOAD_URL, cookie, body)
        val response = client.newCall(request).execute()
        val bodyStr = response.use {
            it.body?.string() ?: throw QuarkApiException("获取下载链接失败：响应为空")
        }
        val json = runCatching { JSONObject(bodyStr) }.getOrElse {
            throw QuarkApiException("响应解析失败")
        }
        if (json.optInt("status") != 200) {
            throw QuarkApiException(json.optString("message").ifBlank { "获取下载链接失败" })
        }
        val item = json.optJSONArray("data")?.optJSONObject(0)
            ?: throw QuarkApiException("未返回下载链接")
        DownloadLink(
            fid = item.optString("fid"),
            filename = item.optString("file_name").ifEmpty { item.optString("filename") },
            downloadUrl = item.optString("download_url"),
            size = item.optLong("size")
        )
    }

    suspend fun getDownloadLink(fid: String, cookie: String): DownloadLink? = withContext(Dispatchers.IO) {
        val body = JSONObject().put("fids", JSONArray().put(fid)).toString()
        val request = postJson(UCConstants.DOWNLOAD_URL, cookie, body)
        val response = client.newCall(request).execute()
        val bodyStr = response.use {
            it.body?.string() ?: throw QuarkApiException("获取下载链接失败：响应为空")
        }
        val json = runCatching { JSONObject(bodyStr) }.getOrElse {
            throw QuarkApiException("响应解析失败")
        }
        if (json.optInt("status") != 200) {
            throw QuarkApiException(json.optString("message").ifBlank { "获取下载链接失败" })
        }
        val array = json.optJSONArray("data") ?: throw QuarkApiException("响应缺少 data")
        if (array.length() == 0) throw QuarkApiException("未返回下载链接")
        val item = array.optJSONObject(0) ?: throw QuarkApiException("未返回下载链接")
        DownloadLink(
            fid = item.optString("fid"),
            filename = item.optString("file_name").ifEmpty { item.optString("filename") },
            downloadUrl = item.optString("download_url"),
            size = item.optLong("size")
        )
    }

    // ---------- 请求构造与响应解析 ----------

    private fun get(url: String, cookie: String): Request =
        Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("User-Agent", UCConstants.USER_AGENT)
            .get()
            .build()

    private fun postJson(url: String, cookie: String, body: String): Request =
        Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("User-Agent", UCConstants.USER_AGENT)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(jsonMediaType))
            .build()

    private fun <T> parseData(request: Request, parser: (JSONObject) -> T): T {
        val response = client.newCall(request).execute()
        val body = response.use {
            it.body?.string() ?: throw QuarkApiException("请求失败：响应为空")
        }
        val json = runCatching { JSONObject(body) }.getOrElse {
            throw QuarkApiException("响应解析失败")
        }
        if (json.optInt("status") != 200) {
            throw QuarkApiException(json.optString("message").ifBlank { "请求失败" })
        }
        return parser(json.optJSONObject("data") ?: throw QuarkApiException("响应缺少 data"))
    }
}