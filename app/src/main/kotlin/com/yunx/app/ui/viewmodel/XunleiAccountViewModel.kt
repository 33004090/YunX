package com.yunx.app.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yunx.app.data.db.XunleiAccountEntity
import com.yunx.app.data.network.XunleiApi
import com.yunx.app.data.network.XunleiLoginStep
import com.yunx.app.data.repository.XunleiAccountRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 迅雷账号 ViewModel：账号+密码登录（可能触发短信验证码）→ 换 token 落库。
 */
class XunleiAccountViewModel(
    private val repository: XunleiAccountRepository
) : ViewModel() {

    val xunleiAccount: StateFlow<XunleiAccountEntity?> = repository.observeAccount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    /** 密码登录结果（needSms=true 时 UI 切到短信验证步骤） */
    var loginStep by androidx.compose.runtime.mutableStateOf<XunleiLoginStep?>(null)
        private set

    /** 登录错误信息 */
    var loginError by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set

    fun consumeLoginError() {
        loginError = null
    }

    /** 账号密码登录 */
    fun login(username: String, password: String) {
        viewModelScope.launch {
            loginError = null
            loginStep = null
            val step = repository.loginWithPassword(username.trim(), password)
            if (step.needSms) {
                // 触发安全验证：优先用 reviewurl 里的 creditkey（风控响应自带），否则走自有 sendSms
                val reviewMap = XunleiApi.parseReviewUrl(step.reviewUrl)
                val creditKey = reviewMap["creditkey"].orEmpty()
                if (creditKey.isNotBlank()) {
                    // 直接用响应里的 creditkey/token 进入短信输入步骤（token 可能为空，sendSms 会补）
                    loginStep = step.copy(
                        smsCreditKey = creditKey,
                        smsToken = reviewMap["token"].orEmpty()
                    )
                } else {
                    val smsStep = repository.sendSms(username.trim())
                    if (smsStep.smsCreditKey.isNotBlank()) {
                        loginStep = smsStep
                    } else {
                        // 不再丢外部链接：给明确失败提示 + 让用户重试
                        loginError = smsStep.message.ifBlank { "短信发送失败，请重试或检查网络" }
                        loginStep = step.copy(message = "短信发送失败")
                    }
                }
            } else if (step.sessionKey.isNotBlank() && step.sessionId.isNotBlank()) {
                val ok = repository.finishLogin(step, username.trim())
                if (!ok) loginError = "登录失败，无法换取凭证"
            } else {
                loginError = step.message.ifBlank { "登录失败，请检查账号密码" }
            }
        }
    }

    /** 发送短信验证码（密码登录触发验证后） */
    fun sendSms(mobile: String) {
        viewModelScope.launch {
            loginError = null
            val step = repository.sendSms(mobile.trim())
            loginStep = step
            if (step.smsCreditKey.isBlank()) loginError = step.message
        }
    }

    /** 短信验证码登录并完成 */
    fun loginWithSms(mobile: String, code: String, creditKey: String, smsToken: String) {
        viewModelScope.launch {
            loginError = null
            val ok = repository.loginWithSms(mobile.trim(), code.trim(), creditKey, smsToken)
            if (!ok) loginError = "验证码校验失败"
        }
    }

    fun logout() {
        viewModelScope.launch { repository.logout() }
    }

    class Factory(
        private val repository: XunleiAccountRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(XunleiAccountViewModel::class.java))
            return XunleiAccountViewModel(repository) as T
        }
    }
}