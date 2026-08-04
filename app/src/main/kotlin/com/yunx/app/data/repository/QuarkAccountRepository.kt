package com.yunx.app.data.repository

import com.yunx.app.data.db.QuarkAccountDao
import com.yunx.app.data.db.QuarkAccountEntity
import com.yunx.app.data.network.QuarkApi
import com.yunx.app.data.network.QuarkConstants
import kotlinx.coroutines.flow.Flow

/**
 * 夸克账号数据仓库：Room 持久化 + 网络验证。
 */
class QuarkAccountRepository(
    private val dao: QuarkAccountDao,
    private val api: QuarkApi
) {

    fun observeAccount(): Flow<QuarkAccountEntity?> = dao.observeAccount()

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