package com.yunx.app.data.repository

import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareSession

/**
 * 分享解析仓库公共接口：夸克 / UC 共用同一套流程（token → 列表 → 转存 → 直链）。
 */
interface ShareResolveRepository {
    suspend fun createSession(link: String, pwd: String?, cookie: String): Result<ShareSession>
    suspend fun listFiles(session: ShareSession, dirFid: String, cookie: String): Result<List<ShareFile>>
    suspend fun ensureTempDir(cookie: String): Result<String>
    suspend fun transferFile(
        session: ShareSession,
        file: ShareFile,
        toDirFid: String,
        cookie: String
    ): Result<String>
    suspend fun getDownloadLink(fid: String, cookie: String): Result<DownloadLink>
}