package com.yunx.app.data.repository

import com.yunx.app.data.db.XunleiAccountDao
import com.yunx.app.data.db.XunleiAccountEntity
import com.yunx.app.data.network.XunleiApi
import com.yunx.app.data.network.XunleiConstants
import com.yunx.app.data.network.XunleiLoginStep
import kotlinx.coroutines.flow.Flow

/**
 * 迅雷账号仓库：账号+密码登录（可能触发短信验证）→ 换 token 落库。
 */
class XunleiAccountRepository(
    private val dao: XunleiAccountDao,
    private val api: XunleiApi
) {

    fun observeAccount(): Flow<XunleiAccountEntity?> = dao.observeAccount()

    suspend fun getAccount(): XunleiAccountEntity? = dao.getAccount()

    /** 账号密码登录；返回登录步骤（needSms=true 表示需短信验证，携带 smsCreditKey/smsToken） */
    suspend fun loginWithPassword(
        username: String,
        password: String
    ): XunleiLoginStep {
        val account = dao.getAccount()
        val deviceId = account?.deviceId ?: XunleiApi.newDeviceId()
        val captchaToken = api.initCaptcha(deviceId, username) ?: ""
        return api.loginWithPassword(username, password, deviceId, captchaToken)
    }

    /** 发送短信验证码（登录触发 review_panel 后调用） */
    suspend fun sendSms(mobile: String): XunleiLoginStep {
        val deviceId = dao.getAccount()?.deviceId ?: XunleiApi.newDeviceId()
        return api.sendSms(mobile, deviceId)
    }

    /** 短信验证码登录并换取 token，成功落库返回 true */
    suspend fun loginWithSms(
        mobile: String,
        smsCode: String,
        creditKey: String,
        smsToken: String
    ): Boolean {
        val deviceId = dao.getAccount()?.deviceId ?: XunleiApi.newDeviceId()
        val step = api.smsLogin(mobile, smsCode, creditKey, smsToken, deviceId)
        if (step.sessionKey.isBlank()) return false
        val tokens = api.exchangeToken(step.sessionKey, deviceId) ?: return false
        val captchaToken = api.initCaptcha(deviceId, mobile) ?: ""
        dao.upsert(
            XunleiAccountEntity(
                id = "xunlei",
                accessToken = tokens.first,
                refreshToken = tokens.second,
                deviceId = deviceId,
                captchaToken = captchaToken,
                nickname = step.nickname.ifBlank { "迅雷用户" }
            )
        )
        return true
    }

    /** 密码登录成功后直接换 token 落库 */
    suspend fun finishLogin(
        step: XunleiLoginStep,
        username: String
    ): Boolean {
        if (step.sessionKey.isBlank()) return false
        val deviceId = dao.getAccount()?.deviceId ?: XunleiApi.newDeviceId()
        val tokens = api.exchangeToken(step.sessionKey, deviceId) ?: return false
        val captchaToken = api.initCaptcha(deviceId, username) ?: ""
        dao.upsert(
            XunleiAccountEntity(
                id = "xunlei",
                accessToken = tokens.first,
                refreshToken = tokens.second,
                deviceId = deviceId,
                captchaToken = captchaToken,
                nickname = step.nickname.ifBlank { "迅雷用户" }
            )
        )
        return true
    }

    suspend fun logout() {
        dao.clear()
    }
}