package com.yunx.app.data.network

/**
 * 解析结果：share_id + 提取码。
 */
data class ParsedShare(
    val shareId: String,
    val pwd: String?
)

/**
 * 从分享链接或整段分享文案中提取 share_id 与提取码。
 *
 * 支持格式：
 * - https://pan.quark.cn/s/01defac105e1（提取码：3vfy）
 * - https://pan.quark.cn/s/6fac71a11e58?pwd=xqvq
 * - 整段复制文案（自动提取链接与「提取码：xxxx」）
 */
object ShareLinkParser {

    private val urlRegex = Regex("""https?://[^\s]+""")
    private val shareIdRegex = Regex("""/s/([A-Za-z0-9]+)""")
    private val pwdInUrlRegex = Regex("""[?&]pwd=([A-Za-z0-9]+)""")
    private val pwdInTextRegex = Regex("""提取码[：:]\s*([A-Za-z0-9]{4})""")

    fun parse(text: String): ParsedShare? {
        val url = urlRegex.find(text.trim())?.value ?: return null
        val shareId = shareIdRegex.find(url)?.groupValues?.getOrNull(1) ?: return null
        val pwd = pwdInUrlRegex.find(url)?.groupValues?.getOrNull(1)
            ?: pwdInTextRegex.find(text)?.groupValues?.getOrNull(1)
        return ParsedShare(shareId = shareId, pwd = pwd)
    }
}