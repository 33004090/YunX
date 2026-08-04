package com.yunx.app.data.repository

import com.yunx.app.data.network.QuarkApi
import com.yunx.app.data.network.QuarkConstants
import com.yunx.app.data.network.ShareLinkParser
import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareSession

/**
 * 分享解析仓库：token → 列表 → 转存临时目录 → 下载直链。
 */
class QuarkResolveRepository(private val api: QuarkApi) {

    /**
     * 创建分享会话：解析链接 → 获取 stoken（请求体携带提取码）。
     */
    suspend fun createSession(link: String, pwd: String?, cookie: String): Result<ShareSession> {
        val parsed = ShareLinkParser.parse(link)
            ?: return Result.failure(IllegalArgumentException("无法识别分享链接"))
        val effectivePwd = pwd?.takeIf { it.isNotBlank() } ?: parsed.pwd
        val token = api.getShareToken(parsed.shareId, effectivePwd, cookie)
            ?: return Result.failure(IllegalArgumentException("分享不存在、已失效或提取码错误"))
        return Result.success(ShareSession(parsed.shareId, token.stoken, token.title))
    }

    /** 获取指定目录下的文件列表 */
    suspend fun listFiles(session: ShareSession, dirFid: String, cookie: String): Result<List<ShareFile>> {
        val files = api.getShareFiles(session.shareId, session.stoken, dirFid, cookie)
        return if (files != null) {
            Result.success(files)
        } else {
            Result.failure(IllegalArgumentException("获取文件列表失败，请重试"))
        }
    }

    /**
     * 确保「YunX临时转存」目录存在，返回其 fid；不存在则创建。
     */
    suspend fun ensureTempDir(cookie: String): Result<String> {
        val rootFiles = api.getFileList(QuarkConstants.DEFAULT_PDIR_FID, cookie)
            ?: return Result.failure(IllegalArgumentException("获取网盘目录失败，请重试"))
        rootFiles.firstOrNull { it.isdir && it.fname == QuarkConstants.TEMP_DIR_NAME }?.let {
            return Result.success(it.fid)
        }
        val fid = api.createFolder(QuarkConstants.TEMP_DIR_NAME, QuarkConstants.DEFAULT_PDIR_FID, cookie)
            ?: return Result.failure(IllegalArgumentException("创建临时目录失败，请重试"))
        return Result.success(fid)
    }

    /**
     * 转存分享文件到临时目录，等待异步任务完成。
     * @return 转存后的新 fid（取直链必须用它，分享 fid 转存后已变更）
     */
    suspend fun transferFile(
        session: ShareSession,
        file: ShareFile,
        toDirFid: String,
        cookie: String
    ): Result<String> {
        val taskId = api.saveShareFile(
            shareId = session.shareId,
            stoken = session.stoken,
            pdirFid = file.pdirFid,
            fid = file.fid,
            fidToken = file.fidToken,
            toPdirFid = toDirFid,
            cookie = cookie
        ) ?: return Result.failure(IllegalArgumentException("转存失败，请重试"))
        val savedFid = api.pollTask(taskId, cookie)
            ?: return Result.failure(IllegalArgumentException("转存超时，请稍后重试"))
        return Result.success(savedFid)
    }

    /** 获取文件下载直链（转存后调用） */
    suspend fun getDownloadLink(fid: String, cookie: String): Result<DownloadLink> {
        val link = api.getDownloadLink(fid, cookie)
        return if (link != null) {
            Result.success(link)
        } else {
            Result.failure(IllegalArgumentException("获取下载链接失败，请重试"))
        }
    }
}