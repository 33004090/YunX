package com.yunx.app.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 夸克 CDN 节点优选（文档《夸克网盘分享解析与下载提速对比分析》§4.1）：
 * 夸克返回的直链默认落在 dl-pc-sz（深圳），若用户不在华南，换成离得近的 dl-pc-* 节点可能更快。
 * 签名 URL 的 auth_key/token 按「路径+查询」计算、与 host 无关，只换子域名通常可用。
 * 探测用 Range: bytes=0-0 只拉 1 字节，开销极小；失败自动回退原链接。
 */
object QuarkCdn {

    /** 候选节点（含默认 sz 与常用城市节点） */
    private val NODES = listOf(
        "dl-pc-sz", "dl-pc-bj", "dl-pc-sh", "dl-pc-gz", "dl-pc-cd",
        "dl-pc-hz", "dl-pc-wh", "dl-pc-tj", "dl-pc-nj", "dl-pc-xa"
    )

    /** 把直链的 CDN 子域名替换为指定节点 */
    fun withNode(url: String, node: String): String =
        url.replace(Regex("""//dl-pc-[a-z]+\."""), "//$node.")

    /** 是否为夸克 dl-pc-* 直链（可做节点优选） */
    fun isQuarkCdnUrl(url: String): Boolean =
        url.contains("//dl-pc-") && url.contains(".drive.quark.cn/")

    /**
     * 并发探测各节点首字节延迟，返回最快可达直链；
     * 探测超时/失败自动回退原链接（不阻断下载）。
     */
    suspend fun fastest(original: String, cookie: String): String = withContext(Dispatchers.IO) {
        if (!isQuarkCdnUrl(original)) return@withContext original
        val candidates = NODES.map { withNode(original, it) }
        coroutineScope {
            val results = candidates.map { url ->
                async { probe(url, cookie) }
            }.map { it.await() }
            results.filter { it.second < Long.MAX_VALUE }
                .minByOrNull { it.second }
                ?.first ?: original
        }
    }

    /** 单节点探测：GET Range:0-0，返回 (url, 耗时ms)；失败耗时 Long.MAX_VALUE */
    private fun probe(url: String, cookie: String): Pair<String, Long> {
        return try {
            val t0 = System.currentTimeMillis()
            val con = URL(url).openConnection() as HttpURLConnection
            con.requestMethod = "GET"
            con.setRequestProperty("Range", "bytes=0-0")
            con.setRequestProperty("User-Agent", QuarkConstants.API_USER_AGENT)
            con.setRequestProperty("Cookie", cookie)
            con.connectTimeout = 1500
            con.readTimeout = 1500
            val code = con.responseCode
            con.disconnect()
            if (code in 200..206) url to (System.currentTimeMillis() - t0)
            else url to Long.MAX_VALUE
        } catch (_: Exception) {
            url to Long.MAX_VALUE
        }
    }
}