package com.yunx.app.data.repository

import android.webkit.CookieManager
import com.yunx.app.data.db.UCAccountDao
import com.yunx.app.data.db.UCAccountEntity
import com.yunx.app.data.network.UCApi
import com.yunx.app.data.network.UCConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * UC 账号数据仓库：Room 持久化 + 网络验证。
 */
class UCAccountRepository(
    private val dao: UCAccountDao,
    private val api: UCApi
) {

    fun observeAccount(): Flow<UCAccountEntity?> = dao.observeAccount()

    suspend fun getAccount(): UCAccountEntity? = dao.getAccount()

    suspend fun logoutUC() {
        withContext(Dispatchers.IO) {
            runCatching {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
            }
        }
        dao.clear()
    }

    suspend fun saveUCAccount(cookie: String): Boolean {
        if (!UCConstants.isValidCookie(cookie)) return false
        val nickname = api.fetchNickname(cookie) ?: "UC用户"
        dao.upsert(
            UCAccountEntity(
                id = "uc",
                cookie = cookie,
                nickname = nickname
            )
        )
        return true
    }
}