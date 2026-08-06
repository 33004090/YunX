package com.yunx.app.data.repository

import com.yunx.app.data.network.C139Api
import com.yunx.app.data.network.C139Constants
import com.yunx.app.data.network.ShareLinkParser
import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareSession

/**
 * 139（和彩云）分享解析仓库：cookie → getOutLinkInfoV6 列目录 → getContentInfoFromOutLink 直链。
 * 139 分享无需转存（share host 直接列目录 + 取直链），credential 为登录 Cookie（含账号信息）；
 * authorization 从 cookie 提取，分享接口按需携带（可空）。
 */
class C139ResolveRepository(private val api: C139Api) : ShareResolveRepository {

    override suspend fun createSession(link: String, pwd: String?, cookie: String): Result<ShareSession> {
        val parsed = ShareLinkParser.parse(link)
            ?: return Result.failure(IllegalArgumentException("无法识别分享链接"))
        if (C139Constants.extractAccountFull(cookie).isNullOrBlank()) {
            return Result.failure(IllegalStateException("登录态缺少账号信息，请重新登录"))
        }
        return runCatching {
            // 139 分享无 token：shareId 即 linkID，stoken 暂存提取码
            ShareSession(parsed.shareId, pwd.orEmpty(), parsed.shareId)
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(it) }
        )
    }

    override suspend fun listFiles(session: ShareSession, dirFid: String, cookie: String): Result<List<ShareFile>> =
        runCatching {
            val account = C139Constants.extractAccountFull(cookie)
                ?: throw IllegalStateException("登录态缺少账号信息，请重新登录")
            val authorization = C139Constants.extractAuthorization(cookie)
            // 根目录 pCaID 传空字符串（文档 §7：pCaID 父目录，空=root）
            val pcaId = if (dirFid == "0" || dirFid.isBlank()) "" else dirFid
            api.getShareFiles(session.shareId, pcaId, account, authorization)
                ?: throw IllegalStateException("未获取到文件列表")
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(it) }
        )

    /** 139 分享不需要转存/个人网盘直链，保留空实现避免误用 */
    override suspend fun ensureTempDir(cookie: String): Result<String> =
        Result.failure(UnsupportedOperationException("139 分享无需转存"))

    override suspend fun transferFile(
        session: ShareSession,
        file: ShareFile,
        toDirFid: String,
        cookie: String
    ): Result<String> = Result.failure(UnsupportedOperationException("139 分享无需转存"))

    override suspend fun getDownloadLink(fid: String, cookie: String): Result<DownloadLink> =
        Result.failure(UnsupportedOperationException("139 分享请使用 getShareDownloadLink"))

    override suspend fun getShareDownloadLink(
        session: ShareSession,
        file: ShareFile,
        cookie: String
    ): Result<DownloadLink> = runCatching {
        val account = C139Constants.extractAccountFull(cookie)
            ?: throw IllegalStateException("登录态缺少账号信息，请重新登录")
        val authorization = C139Constants.extractAuthorization(cookie)
        api.getShareDownloadLink(file.fid, session.shareId, account, authorization)
            ?: throw IllegalStateException("获取下载链接失败")
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) }
    )
}