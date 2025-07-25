package com.fzm.wallet.sdk.alpha

import android.content.ContentValues
import com.alibaba.fastjson.JSON
import com.fzm.wallet.sdk.MnemonicManager
import com.fzm.wallet.sdk.WalletBean
import com.fzm.wallet.sdk.bean.StringResult
import com.fzm.wallet.sdk.bean.Transactions
import com.fzm.wallet.sdk.bean.WithHold
import com.fzm.wallet.sdk.bean.response.TransactionResponse
import com.fzm.wallet.sdk.db.entity.Coin
import com.fzm.wallet.sdk.db.entity.PWallet
import com.fzm.wallet.sdk.net.GoResponse
import com.fzm.wallet.sdk.net.rootScope
import com.fzm.wallet.sdk.net.walletQualifier
import com.fzm.wallet.sdk.repo.OutRepository
import com.fzm.wallet.sdk.repo.WalletRepository
import com.fzm.wallet.sdk.toWalletBean
import com.fzm.wallet.sdk.utils.*
import com.fzm.wallet.sdk.utils.GoWallet.Companion.PARA
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.litepal.LitePal
import org.litepal.extension.find
import walletapi.GsendTx
import walletapi.Walletapi
import java.util.*

/**
 * @author zhengjy
 * @since 2022/01/12
 * Description:
 */
abstract class BaseWallet(protected val wallet: PWallet) : Wallet<Coin> {

    protected val gson by lazy { Gson() }
    protected val walletRepository by lazy { rootScope.get<WalletRepository>(walletQualifier) }
    protected val outRepository by lazy { rootScope.get<OutRepository>(walletQualifier) }

    override fun getId(): String {
        return wallet.id.toString()
    }

    override val walletInfo: WalletBean
        get() = wallet.toWalletBean()

    override suspend fun changeWalletName(name: String): Boolean {
        val wallets = LitePal.where("name = ?", name).find(PWallet::class.java)
        if (!wallets.isNullOrEmpty()) {
            throw Exception("账户名称重复")
        }
        wallet.name = name
        return wallet.update(wallet.id) != 0
    }

    override suspend fun changeWalletPassword(old: String, password: String): Boolean {
        return withContext(Dispatchers.IO) {
            if (wallet.password.isNullOrEmpty()) {
                setPassword(password)
            } else {
                changePassword(old, password)
            }
        }
    }

    private suspend fun setPassword(password: String): Boolean {
        val mnem = MnemonicManager.getMnemonicWords(wallet.password)
        return MnemonicManager.saveMnemonicWords(mnem, password)
    }

    private suspend fun changePassword(old: String, password: String): Boolean {
        if (!MnemonicManager.checkPassword(old)) {
            throw Exception("密码错误")
        }
        val mnem = MnemonicManager.getMnemonicWords(old)
        return MnemonicManager.saveMnemonicWords(mnem, password)
    }

    override suspend fun delete(password: String, confirmation: suspend () -> Boolean): Boolean {
        val verified = withContext(Dispatchers.IO) {
            MnemonicManager.checkPassword(password)
        }
        if (verified) {
            if (confirmation()) {
                withContext(Dispatchers.IO) {
                    LitePal.delete(PWallet::class.java, wallet.id)
                }
                return true
            }
            return false
        } else {
            throw IllegalArgumentException("密码输入错误")
        }
    }

    override suspend fun transfer(
        coin: Coin,
        toAddress: String,
        amount: Double,
        fee: Double,
        note: String?,
        password: String
    ): String {
        return withContext(Dispatchers.IO) {
            val result = MnemonicManager.checkPassword(password)
            if (!result) {
                throw Exception("密码输入错误")
            }
            val mnem = MnemonicManager.getMnemonicWords(password)
            val privateKey = coin.getPrivkey(
                if (coin.chain == "BNB" || coin.chain == "POL") "ETH" else coin.chain,
                mnem
            )
                ?: throw Exception("私钥获取失败")
            handleTransfer(coin, toAddress, amount, fee, note, privateKey)
        }
    }

    private suspend fun handleBtyChildTransfer(
        coin: Coin,
        toAddress: String,
        amount: Double,
        fee: Double,
        note: String?,
        privateKey: String
    ): String {
        val result = outRepository.getWithHold(coin.platform, coin.name)
        val withHold = if (result.isSucceed()) {
            result.data()!!
        } else throw Exception("代扣信息获取失败")
        val gsendTx = GsendTx().apply {
            this.feepriv = withHold.private_key
            this.to = toAddress
            this.tokenSymbol = withHold.tokensymbol
            this.execer = withHold.exer
            this.note = note
            this.amount = amount
            this.txpriv = privateKey
        }

        if (coin.isBtyToken) {
            if (withHold.coinsName.isNullOrEmpty()) {
                gsendTx.fee = fee
            } else {
                withholdCoins(gsendTx, withHold)
            }
        } else {
            //转coins币除了代扣bty作为手续费，自己也要扣点coins
            gsendTx.amount = amount + withHold.fee
            withholdCoins(gsendTx, withHold)
        }
        val resp = Walletapi.coinsTxGroup(gsendTx)
        GoWallet.sendTran(coin.chain, resp.signedTx, withHold.tokensymbol)
            ?: throw Exception("获取结果失败，请至区块链浏览器查看")
        return resp.txId
    }

    private fun withholdCoins(gsendTx: GsendTx, withHold: WithHold) {
        gsendTx.coinsForFee = true
        gsendTx.tokenFee = withHold.fee
        gsendTx.tokenFeeAddr = withHold.address
        //代扣的就是平行链的token和coins，统一都x3
        gsendTx.fee = withHold.btyFee * 3
    }

    private var addressId = 0

    private suspend fun handleTransfer(
        coin: Coin,
        toAddress: String,
        cAmount: Double,
        cFee: Double,
        note: String?,
        privateKey: String,
    ): String {
        val coinToken = coin.newChain
        val tsy = coinToken.tokenSymbol


        if (coin.chain == "POL") {
            val result = walletRepository.createByContract(
                coinToken.cointype,
                tsy,
                coin.address,
                toAddress,
                cAmount,
                cFee,
                coin.contract_address
            )
            if (result.isSucceed()) {
                val createResult = result.data()
                val createJson = gson.toJson(createResult)
                return signAndSends(
                    "ETH",
                    tsy,
                    createJson,
                    coinToken.cointype,
                    137,
                    coin.address,
                    privateKey
                )
            }
        } else {
            // 构造交易
            if (coin.contract_address.isNullOrEmpty()) {
                //如果需要代扣
                if (coinToken.proxy) {
                    val gsendTx = GsendTx().apply {
                        feepriv = privateKey
                        to = toAddress
                        tokenSymbol = tsy
                        execer = coinToken.exer
                        amount = cAmount
                        txpriv = privateKey
                        //消耗的BTY
                        fee = 0.01
                        //扣的手续费接收地址
                        //tokenFeeAddr = YBF_FEE_ADDR
                        //扣多少手续费
                        //tokenFee = if (it.platform == IPConfig.YBF_CHAIN) YBF_TOKEN_FEE else TOKEN_FEE
                        coinsForFee = false

                        //feeAddressID是收比特元的手续费地址格式，txAddressID是当前用户地址格式
                        feeAddressID = if (coin.address.startsWith("0x")) 2 else 0
                        txAddressID = if (coin.address.startsWith("0x")) 2 else 0
                    }
                    val gsendTxResp = Walletapi.coinsTxGroup(gsendTx)
                    GoWallet.sendTran(coinToken.cointype, gsendTxResp.signedTx, tsy)
                    return gsendTxResp.txId
                } else {
                    val rawTx = GoWallet.createTran(
                        coinToken.cointype,
                        coin.address,
                        toAddress,
                        cAmount,
                        cFee,
                        note ?: "",
                        tsy
                    )
                    val stringResult = JSON.parseObject(rawTx, StringResult::class.java)
                    val createRawResult: String = stringResult.result ?: ""
                    return signAndSends(
                        coinToken.cointype,
                        tsy,
                        createRawResult,
                        coinToken.cointype,
                        -1,
                        coin.address,
                        privateKey
                    )


                }


            } else {
                val result = walletRepository.createByContract(
                    coinToken.cointype,
                    tsy,
                    coin.address,
                    toAddress,
                    cAmount,
                    cFee,
                    coin.contract_address
                )
                if (result.isSucceed()) {
                    val createResult = result.data()
                    val createJson = gson.toJson(createResult)
                    if (coin.platform == "wwchain") {
                        return signAndSends(
                            coin.chain,
                            tsy,
                            createJson,
                            PARA,
                            5188,
                            coin.address,
                            privateKey
                        )
                    } else {
                        return signAndSends(
                            coinToken.cointype,
                            tsy,
                            createJson,
                            coinToken.cointype,
                            -1,
                            coin.address,
                            privateKey
                        )
                    }
                }
            }
        }



        return ""

    }

    //    coin: Coin,
//    tokenSymbol: String,
//    coinToken: GoWallet.Companion.CoinToken,
//    privateKey: String,
//    createRawResult: String,
//    chainId:Int = -1,
    private fun signAndSends(
        coinType: String,
        tokenSymbol: String,
        createRawResult: String,
        sendChain: String,
        chainId: Int,
        address: String,
        privateKey: String
    ): String {

        //签名交易
        addressId = if (address.startsWith("0x")) 2 else 0
        val signTx = GoWallet.signTran(
            coinType, Walletapi.stringTobyte(createRawResult), privateKey, addressId, chainId
        ) ?: throw Exception("签名交易失败")

        // 发送交易
        val sendTx = GoWallet.sendTran(sendChain, signTx, tokenSymbol)
        val sendResult = gson.fromJson(sendTx, StringResult::class.java)
        val txId = sendResult.result
        if (sendResult == null) {
            throw Exception("获取结果失败，请至区块链浏览器查看")
        }
        if (!sendResult.error.isNullOrEmpty()) {
            throw Exception(sendResult.error)
        }
        if (txId.isNullOrEmpty()) {
            throw Exception("获取结果失败，请至区块链浏览器查看")
        }
        return txId
    }

    override suspend fun addCoins(coins: List<Coin>, password: suspend () -> String) {
        withContext(Dispatchers.IO) {
            var cachePass = ""
            for (c in coins) {
                checkCoin(c) {
                    cachePass.ifEmpty {
                        withContext(Dispatchers.Main.immediate) {
                            password().also { p -> cachePass = p }
                        }
                    }
                }
            }
        }
    }

    @Throws(Exception::class)
    private suspend fun checkCoin(coin: Coin, password: suspend () -> String) {
        if (coin.chain == null) return
        val existNum = LitePal.where("pwallet_id = ?", wallet.id.toString()).count(Coin::class.java)
        val sameChainCoin =
            LitePal.select().where("chain = ? and pwallet_id = ?", coin.chain, wallet.id.toString())
                .findFirst(Coin::class.java, true)
        if (sameChainCoin != null) {
            val sameCoin = LitePal.select().where(
                "chain = ? and name = ? and platform = ? and pwallet_id = ?",
                coin.chain,
                coin.name,
                coin.platform,
                wallet.id.toString()
            ).findFirst(Coin::class.java, false)
            if (sameCoin != null) {
                updateLocalCoin(ContentValues().apply {
                    put("status", Coin.STATUS_ENABLE)
                }, sameCoin.id)
            } else {
                Coin().apply {
                    chain = coin.chain
                    name = coin.name
                    platform = coin.platform
                    netId = coin.netId
                    icon = coin.icon
                    nickname = coin.nickname
                    treaty = coin.treaty
                    optionalName = coin.optionalName

                    status = Coin.STATUS_ENABLE
                    address = sameChainCoin.address
                    pubkey = sameChainCoin.pubkey
                    sort = existNum
                    setPrivkey(sameChainCoin.encPrivkey)
                    setpWallet(wallet)
                    save()
                }
            }
        } else {
            val pass = password()
            if (!MnemonicManager.checkPassword(pass)) {
                throw Exception("密码输入错误")
            }
            val mnem = MnemonicManager.getMnemonicWords(pass)
            if (mnem.isEmpty()) {
                throw Exception("助记词解密失败")
            }
            val hdWallet = GoWallet.getHDWallet(coin.chain, mnem) ?: throw Exception("创建主链失败")
            Coin().apply {
                chain = coin.chain
                name = coin.name
                platform = coin.platform
                netId = coin.netId
                icon = coin.icon
                nickname = coin.nickname
                treaty = coin.treaty
                optionalName = coin.optionalName

                status = Coin.STATUS_ENABLE
                address = hdWallet.newAddress_v2(0)
                pubkey = GoWallet.encodeToStrings(hdWallet.newKeyPub(0))
                sort = existNum
                setpWallet(wallet)
                save()
            }
        }
    }

    override suspend fun deleteCoins(coins: List<Coin>) = withContext(Dispatchers.IO) {
        for (c in coins) {
            updateLocalCoin(
                ContentValues().apply { put("status", Coin.STATUS_DISABLE) },
                c.id
            )
        }
    }

    override fun getCoinBalance(
        requireQuotation: Boolean,
        predicate: ((Coin) -> Boolean)?
    ): Flow<List<Coin>> = flow {
        supervisorScope {
            val coins = LitePal.where(
                "pwallet_id = ? and status = ?",
                wallet.id.toString(),
                Coin.STATUS_ENABLE.toString()
            ).find(Coin::class.java, true).let {
                if (predicate == null) it else it.filter(predicate)
            }
            if (coins.isEmpty()) {
                emit(emptyList())
                return@supervisorScope
            } else {
                emit(coins)
            }
            val deferred = ArrayDeque<Deferred<Unit>>()
            for (coin in coins) {
                deferred.add(async(Dispatchers.IO) {
                    try {
                        if (coin.contract_address.isNullOrEmpty() || coin.platform == "wwchain") {
                            coin.balance = GoWallet.handleBalance(coin)
                            updateLocalCoin(
                                ContentValues().apply { put("balance", coin.balance) },
                                coin.id
                            )
                        } else {
                            val result = walletRepository.getBalanceByContract(
                                coin.chain,
                                coin.address,
                                coin.contract_address
                            )
                            if (result.isSucceed()) {
                                coin.balance = result.data()?.balance
                                updateLocalCoin(
                                    ContentValues().apply { put("balance", coin.balance) },
                                    coin.id
                                )
                            }
                        }
                        return@async
                    } catch (e: Exception) {
                        // 资产获取异常
                    }
                })
            }
            val quotationDeferred =
                if (requireQuotation || coins.any { it.nickname.isNullOrEmpty() }) {
                    // 查询资产行情等
                    async { walletRepository.getCoinList(coins.map { "${it.name},${it.platform}" }) }
                } else null
            quotationDeferred?.await()?.dataOrNull()?.also { coinMeta ->
                val coinMap = coins.associateBy { "${it.chain}-${it.name}-${it.platform}" }
                for (meta in coinMeta) {
                    coinMap["${meta.chain}-${meta.name}-${meta.platform}"]?.apply {
                        this.rmb = meta.rmb
                        this.icon = meta.icon
                        this.nickname = meta.nickname
                        this.treaty = meta.treaty
                        this.netId = meta.netId
                        this.optionalName = meta.optionalName
                        this.contract_address = meta.contract_address
                        updateLocalCoin(
                            ContentValues().apply {
                                put("rmb", rmb)
                                put("icon", icon)
                                put("nickname", nickname)
                                put("treaty", treaty)
                                put("netId", netId)
                                put("optionalName", optionalName)
                                put("contract_address", contract_address)
                            },
                            id
                        )
                    }
                }
                emit(coins)
            }
            while (deferred.isNotEmpty()) {
                deferred.poll()?.await()
            }
            emit(coins)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun getCoinBalance(coin: Coin, requireQuotation: Boolean): Coin {
        return withContext(Dispatchers.IO) {
            try {
                coin.balance = GoWallet.handleBalance(coin)
            } catch (e: Exception) {
                // 资产获取异常
            }
            if (requireQuotation || coin.nickname.isNullOrEmpty()) {
                // 查询资产行情等
                val result = walletRepository.getCoinList(listOf("${coin.name},${coin.platform}"))
                result.dataOrNull()?.firstOrNull()?.also { meta ->
                    coin.rmb = meta.rmb
                    coin.icon = meta.icon
                    coin.nickname = meta.nickname
                    coin.treaty = meta.treaty
                    coin.netId = meta.netId
                    coin.optionalName = meta.optionalName
                }
            }
            updateLocalCoin(
                ContentValues().apply {
                    put("balance", coin.balance)
                    put("rmb", coin.rmb)
                    put("icon", coin.icon)
                    put("nickname", coin.nickname)
                    put("treaty", coin.treaty)
                    put("netId", coin.netId)
                    put("optionalName", coin.optionalName)
                },
                coin.id
            )
            coin
        }
    }

    override suspend fun getTransactionList(
        coin: Coin,
        type: Long,
        index: Long,
        size: Long
    ): List<Transactions> {
        return withContext(Dispatchers.IO) {
            if (coin.contract_address.isNullOrEmpty() || coin.platform == "wwchain") {
                // 处理 GoWallet 同步调用
                val coinToken = coin.newChain
                val jsonData =
                    GoWallet.getTranList(
                        coin.address,
                        coinToken.cointype,
                        coinToken.tokenSymbol,
                        type,
                        index,
                        size
                    )
                val response = gson.fromJson(jsonData, TransactionResponse::class.java)
                response.result ?: emptyList()
            } else {
                // 处理 Repository 异步调用
                val res = walletRepository.queryTransactionsByaddress(
                    coin.chain, "", coin.address, coin.contract_address, index, size, 0, type
                )
                if (res.isSucceed()) {
                    res.data() ?: emptyList()
                } else {
                    emptyList() // 或抛出自定义异常
                }
            }
        }
    }

    override suspend fun getTransactionByHash(
        chain: String,
        tokenSymbol: String,
        hash: String
    ): Transactions {
        return withContext(Dispatchers.IO) {
            val data = GoWallet.getTranByTxid(chain, tokenSymbol, hash)
            if (data.isNullOrEmpty()) throw Exception("查询数据为空")
            val response = gson.fromJson<GoResponse<Transactions>>(
                data,
                object : TypeToken<GoResponse<Transactions>>() {}.type
            )
            if (response.error == null) {
                response.result ?: throw Exception("查询结果为空")
            } else {
                throw Exception(response.error)
            }
        }
    }

    override suspend fun getAddress(chain: String): String? {
        val coinList = LitePal.select()
            .where("chain = ? and pwallet_id = ?", chain, wallet.id.toString())
            .find<Coin>(true)
        return coinList.let { it.firstOrNull()?.address }
    }

    override suspend fun getRedPacketAssets(address: String): List<Coin> {
        return withContext(Dispatchers.IO) {
            val coins = LitePal.where(
                "pwallet_id = ? and status = ? and chain = ?",
                wallet.id.toString(),
                Coin.STATUS_ENABLE.toString(),
                Walletapi.TypeBtyString
            ).find(Coin::class.java)
            coins.forEach {
                it.balance = GoWallet.handleRedPacketBalance(it)
            }
            coins
        }
    }

    override suspend fun getMainCoin(chain: String): Coin? {
        return withContext(Dispatchers.IO) {
            LitePal.where(
                "pwallet_id = ? and chain = ? and name = ?",
                wallet.id.toString(),
                chain,
                chain
            ).findFirst(Coin::class.java)
        }
    }

    override fun close() {
        wallet.password = null
        wallet.isPutpassword = false
        wallet.mnem = null
        wallet.save()
    }

    private fun updateLocalCoin(values: ContentValues, id: Long) {
        LitePal.update(Coin::class.java, values, id)
    }

    protected fun getChineseMnem(mnem: String): String {
        val afterString = mnem.replace(" ", "")
        val afterString2 = afterString.replace("\n", "")
        return afterString2.replace("", " ").trim()
    }

    protected fun getKey(coin: Coin, type: Long): String =
        "${coin.chain}${coin.address}${coin.name}$type"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BaseWallet

        if (wallet != other.wallet) return false

        return true
    }

    override fun hashCode(): Int {
        return wallet.hashCode()
    }


}