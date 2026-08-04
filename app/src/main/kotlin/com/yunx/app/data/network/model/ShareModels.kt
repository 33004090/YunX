package com.yunx.app.data.network.model

/**
 * 分享解析会话（一次解析流程的凭证）。
 */
data class ShareSession(
    val shareId: String,
    val stoken: String,
    val title: String
)

/**
 * 分享 Token 响应（4.1 接口）。
 */
data class ShareToken(
    val stoken: String,
    val title: String,
    val firstFid: String
)

/**
 * 分享文件/目录项。
 */
data class ShareFile(
    val fid: String,
    val fname: String,
    val fsize: Long,
    val isdir: Boolean,
    val pdirFid: String,
    val fidToken: String,
    val modifyTime: String = ""
)

/**
 * 下载直链。
 */
data class DownloadLink(
    val fid: String,
    val filename: String,
    val downloadUrl: String,
    val size: Long
)