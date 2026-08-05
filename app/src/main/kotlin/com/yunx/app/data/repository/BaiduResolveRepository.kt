package com.yunx.app.data.repository

import com.yunx.app.data.network.BaiduApi
import com.yunx.app.data.network.ShareLinkParser
import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareSession

/**
 * 百度分享解析仓库：verify 拿 sekey → xpan/share 列文件 → 转存临时目录 → filemetas 拿直链 → 删除转存。
 * 全部基于抓包链路（share/verify → xpan/share list → share/transfer → filemetas），
 * 直链 URL 自带签名，拿链后立即删除临时转存（失败不阻断下载）。
 */
class BaiduResolveRepository(private val api: BaiduApi) : ShareResolveRepository {

    /** surl -> sekey（verify 返回的 randsk） */
    private val sekeys = mutableMapOf<String, String>()

    /** surl -> (share_id, uk)，由列表接口返回（转存必需） */
    private val shareInfos = mutableMapOf<String, Pair<String, String>>()

    override suspend fun createSession(link: String, pwd: String?, cookie: String): Result<ShareSession> =
        runCatching {
            val parsed = ShareLinkParser.parse(link)
                ?: throw IllegalArgumentException("无法识别百度分享链接")
            val surl = parsed.shareId
            val effectivePwd = pwd?.takeIf { it.isNotBlank() } ?: parsed.pwd
                ?: throw IllegalArgumentException("该分享需要提取码")
            val sekey = api.verifyShare(surl, effectivePwd, cookie)
            sekeys[surl] = sekey
            ShareSession(surl, sekey, "")
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(it) }
        )

    override suspend fun listFiles(session: ShareSession, dirFid: String, cookie: String): Result<List<ShareFile>> =
        runCatching {
            val sekey = session.stoken.ifBlank { sekeys[session.shareId] ?: "" }
            // 顶层 dirFid 为空/"/"；子目录 dirFid 为目录 path（如 /folder）
            val result = api.listShare(session.shareId, sekey, dirFid, cookie)
            // 缓存 share_id/uk（转存需要）
            shareInfos[session.shareId] = result.shareId to result.uk
            result.files
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(it) }
        )

    override suspend fun ensureTempDir(cookie: String): Result<String> = runCatching {
        // 直接转存到网盘根目录（必然存在），彻底绕开 filemanager 建目录（mkdir 在普通 Cookie 下 errno=2）。
        // 转存后立即取直链并删除，根目录不留残留。
        "/"
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) }
    )

    override suspend fun transferFile(
        session: ShareSession,
        file: ShareFile,
        toDirFid: String,
        cookie: String
    ): Result<String> = runCatching {
        val (shareId, uk) = requireShareInfo(session, cookie)
        val result = api.transfer(shareId, uk, session.stoken, file.fid, toDirFid, cookie)
        result.fsId
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) }
    )

    /** 个人网盘文件直链（filemetas） */
    override suspend fun getDownloadLink(fid: String, cookie: String): Result<DownloadLink> = runCatching {
        val dlink = api.fileMetasDlink(fid, cookie)
        DownloadLink(fid = fid, filename = "", downloadUrl = dlink, size = 0L)
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) }
    )

    /** 百度取直链：转存临时目录 → filemetas 拿 dlink → 删除临时转存文件 */
    override suspend fun getShareDownloadLink(
        session: ShareSession,
        file: ShareFile,
        cookie: String
    ): Result<DownloadLink> = runCatching {
        val (shareId, uk) = requireShareInfo(session, cookie)
        val dirPath = ensureTempDir(cookie).getOrThrow()
        val transferred = api.transfer(shareId, uk, session.stoken, file.fid, dirPath, cookie)
        val dlink = api.fileMetasDlink(transferred.fsId, cookie)
        // 直链自带签名，拿链后立即删除临时转存（失败不阻断下载）
        runCatching { api.deleteFile(transferred.path, cookie) }
        DownloadLink(
            fid = transferred.fsId,
            filename = file.fname,
            downloadUrl = dlink,
            size = file.fsize
        )
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) }
    )

    /** 取 share_id/uk：优先用列表接口缓存的，否则先列一次根目录 */
    private suspend fun requireShareInfo(session: ShareSession, cookie: String): Pair<String, String> {
        shareInfos[session.shareId]?.let { return it }
        val sekey = session.stoken.ifBlank { sekeys[session.shareId] ?: "" }
        val result = api.listShare(session.shareId, sekey, "/", cookie)
        val info = result.shareId to result.uk
        shareInfos[session.shareId] = info
        return info
    }
}