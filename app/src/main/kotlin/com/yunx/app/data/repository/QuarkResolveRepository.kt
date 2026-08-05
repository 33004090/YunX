package com.yunx.app.data.repository

import com.yunx.app.data.network.QuarkApi
import com.yunx.app.data.network.QuarkConstants
import com.yunx.app.data.network.ShareLinkParser
import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareSession

/**
 * 夸克分享解析仓库：token → 列表 → 转存临时目录 → 下载直链。
 * 所有 API 失败统一携带服务端 message（QuarkApiException）透传给 UI。
 */
class QuarkResolveRepository(private val api: QuarkApi) : ShareResolveRepository {

    /**
     * 创建分享会话：解析链接 → 获取 stoken（请求体携带提取码）。
     */
    override suspend fun createSession(link: String, pwd: String?, cookie: String): Result<ShareSession> {
        val parsed = ShareLinkParser.parse(link)
            ?: return Result.failure(IllegalArgumentException("无法识别分享链接"))
        val effectivePwd = pwd?.takeIf { it.isNotBlank() } ?: parsed.pwd
        return runCatching {
            val token = api.getShareToken(parsed.shareId, effectivePwd, cookie)
                ?: throw IllegalStateException("未获取到分享凭证")
            ShareSession(parsed.shareId, token.stoken, token.title)
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(it) }
        )
    }

    /** 获取指定目录下的文件列表 */
    override suspend fun listFiles(session: ShareSession, dirFid: String, cookie: String): Result<List<ShareFile>> =
        runCatching {
            api.getShareFiles(session.shareId, session.stoken, dirFid, cookie)
                ?: throw IllegalStateException("未获取到文件列表")
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(it) }
        )

    /**
     * 确保「YunX临时转存」目录存在，返回其 fid；不存在则创建。
     */
    override suspend fun ensureTempDir(cookie: String): Result<String> = runCatching {
        val rootFiles = api.getFileList(QuarkConstants.DEFAULT_PDIR_FID, cookie)
            ?: throw IllegalStateException("获取网盘目录失败")
        rootFiles.firstOrNull { it.isdir && it.fname == QuarkConstants.TEMP_DIR_NAME }?.fid
            ?: api.createFolder(QuarkConstants.TEMP_DIR_NAME, QuarkConstants.DEFAULT_PDIR_FID, cookie)
            ?: throw IllegalStateException("创建临时目录失败")
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) }
    )

    /**
     * 转存分享文件到临时目录，等待异步任务完成。
     * @return 转存后的新 fid（取直链必须用它，分享 fid 转存后已变更）
     */
    override suspend fun transferFile(
        session: ShareSession,
        file: ShareFile,
        toDirFid: String,
        cookie: String
    ): Result<String> = runCatching {
        val taskId = api.saveShareFile(
            shareId = session.shareId,
            stoken = session.stoken,
            pdirFid = file.pdirFid,
            fid = file.fid,
            fidToken = file.fidToken,
            toPdirFid = toDirFid,
            cookie = cookie
        ) ?: throw IllegalStateException("转存失败")
        api.pollTask(taskId, cookie)
            ?: throw IllegalStateException("转存超时，请稍后重试")
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) }
    )

    /** 获取文件下载直链（转存后调用） */
    override suspend fun getDownloadLink(fid: String, cookie: String): Result<DownloadLink> = runCatching {
        api.getDownloadLink(fid, cookie)
            ?: throw IllegalStateException("获取下载链接失败")
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) }
    )

    /** 夸克取直链：转存到临时目录 → 用转存后新 fid 取直链 → 取链成功后立即删除临时转存 */
    override suspend fun getShareDownloadLink(
        session: ShareSession,
        file: ShareFile,
        cookie: String
    ): Result<DownloadLink> = runCatching {
        val dirFid = ensureTempDir(cookie).getOrThrow()
        val savedFid = transferFile(session, file, dirFid, cookie).getOrThrow()
        val link = api.getDownloadLink(savedFid, cookie)
            ?: throw IllegalStateException("获取下载链接失败")
        // 取链成功后立即删除临时转存文件（失败不阻断下载；目录保留，下次复用）
        runCatching { api.deleteFile(savedFid, cookie) }
        link
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) }
    )
}