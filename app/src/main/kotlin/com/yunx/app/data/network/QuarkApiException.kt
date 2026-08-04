package com.yunx.app.data.network

/**
 * 夸克 API 业务异常：携带服务端返回的 message 字段，
 * 用于把具体错误原因（如「提取码错误」「分享已失效」）透传给 UI。
 */
class QuarkApiException(message: String) : Exception(message)
