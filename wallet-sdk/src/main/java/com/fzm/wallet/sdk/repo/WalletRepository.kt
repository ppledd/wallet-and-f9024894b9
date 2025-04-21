package com.fzm.wallet.sdk.repo

import com.fzm.wallet.sdk.api.Apis
import com.fzm.wallet.sdk.bean.BrowserBean
import com.fzm.wallet.sdk.bean.toRequestBody
import com.fzm.wallet.sdk.db.entity.AddCoinTabBean
import com.fzm.wallet.sdk.db.entity.Coin
import com.fzm.wallet.sdk.db.entity.ContractBalance
import com.fzm.wallet.sdk.db.entity.CreateRaw
import com.fzm.wallet.sdk.net.HttpResult
import com.fzm.wallet.sdk.net.apiCall
import com.fzm.wallet.sdk.net.goCall
import okhttp3.MediaType
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject

class WalletRepository constructor(private val apis: Apis) {


    val mediaType: MediaType = MediaType.get("application/json; charset=utf-8");

    suspend fun getCoinList(names: List<String>): HttpResult<List<Coin>> {
        return apiCall { apis.getCoinList(mapOf("names" to names)) }
    }

    suspend fun searchCoinList(
        page: Int,
        limit: Int,
        keyword: String,
        chain: String,
        platform: String
    ): HttpResult<List<Coin>> {
        val body = toRequestBody(
            "page" to page,
            "limit" to limit,
            "keyword" to keyword,
            "chain" to chain,
            "platform" to platform
        )
        return apiCall { apis.searchCoinList(body) }
    }

    suspend fun getTabData(): HttpResult<List<AddCoinTabBean>> {
        return apiCall { apis.getTabData() }
    }

    suspend fun getBrowserUrl(platform: String): HttpResult<BrowserBean> {
        return apiCall { apis.getBrowserUrl(platform) }
    }

    suspend fun getTransactionCount(address: String): HttpResult<String> {

        val param = JSONObject()
        param.put("id", 1)
        param.put("jsonrpc", "2.0")
        param.put("method", "eth_getTransactionCount")
        param.put("params", JSONArray(listOf(address, "latest")))


        val requestBody: RequestBody = RequestBody.create(mediaType, param.toString())


        return goCall { apis.getTransactionCount(requestBody) }
    }

    suspend fun getBalanceByContract(
        cointype: String,
        address: String,
        contractAddress: String
    ): HttpResult<ContractBalance> {
        val extendInfo = JSONObject()
        extendInfo.put("Execer", contractAddress)

        return goCall {
            apis.getBalanceByContract(
                toRequestBody(
                    "Wallet.GetBalance",
                    "cointype" to cointype,
                    "address" to address,
                    "extend_info" to extendInfo
                )
            )
        }
    }

    suspend fun createByContract(
        cointype: String,
        tokensymbol: String,
        from: String,
        to: String,
        amount: Double,
        fee: Double,
        contractAddress: String
    ): HttpResult<CreateRaw> {
        val extend = JSONObject()
        extend.put("token_addr", contractAddress)
        val transaction = JSONObject()
        transaction.put("from", from)
        transaction.put("to", to)
        transaction.put("amount", amount)
        transaction.put("fee", fee)
        transaction.put("extend", extend)

        return goCall {
            apis.CreateByContract(
                toRequestBody(
                    "Wallet.CreateRawTransaction",
                    "cointype" to cointype,
                    "tokensymbol" to tokensymbol,
                    "transaction" to transaction
                )
            )
        }
    }

    suspend fun getGasPrice(): HttpResult<String> {

        val param = JSONObject()
        param.put("id", 1)
        param.put("jsonrpc", "2.0")
        param.put("method", "eth_gasPrice")

        val requestBody: RequestBody = RequestBody.create(mediaType, param.toString())

        return goCall { apis.getGasPrice(requestBody) }
    }

    suspend fun sendRawTransaction(signHash: String?): HttpResult<String> {
        val param = JSONObject()
        param.put("id", 1)
        param.put("jsonrpc", "2.0")
        param.put("method", "eth_sendRawTransaction")
        param.put("params", JSONArray(listOf(signHash)))

        val requestBody: RequestBody = RequestBody.create(mediaType, param.toString())

        return goCall { apis.sendRawTransaction(requestBody) }
    }

}