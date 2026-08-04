package com.yunx.app.data.repository

import android.webkit.CookieManager
import com.yunx.app.data.db.QuarkAccountDao
import com.yunx.app.data.db.QuarkAccountEntity
import com.yunx.app.data.network.QuarkApi
import com.yunx.app.data.network.QuarkConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * 夸克账号数据仓库：Room 持久化 + 网络验证。
 */
class QuarkAccountRepository(
    private val dao: QuarkAccountDao,
    private val api: QuarkApi
) {

    fun observeAccount(): Flow<QuarkAccountEntity?> = dao.observeAccount()

    suspend fun getAccount(): QuarkAccountEntity? = dao.getAccount()

    /** 退出登录：清理 WebView Cookie + 清除本地记录 */
    suspend fun logoutQuark() {
        withContext(Dispatchers.IO) {
            runCatching {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
            }
        }
        dao.clear()
    }

    /**
     * 校验 Cookie 有效性；有效则拉取昵称并落库，返回 true；无效返回 false。
     */
    suspend fun saveQuarkAccount(cookie: String): Boolean {
        if (!QuarkConstants.isValidCookie(cookie)) return false
        val nickname = api.fetchNickname(cookie) ?: "夸克用户"
        dao.upsert(
            QuarkAccountEntity(
                id = "quark",
                cookie = cookie,
                nickname = nickname
            )
        )
        return true
    }
}