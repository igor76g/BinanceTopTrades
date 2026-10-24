package com.example.binancetoptrades

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Binance answered HTTP 429 / 418: the app must slow down. */
class RateLimitException(val retryAfterSec: Long) : IOException("Binance rate limit, retry after $retryAfterSec s")

/** One 1-minute candle. */
data class Candle(
    val openTime: Long,
    val closeTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val quoteVolume: Double,   // USDT traded in this candle
    val trades: Long           // number of trades in this candle
)

/**
 * Binance USDT-M Futures PUBLIC REST API client.
 * No API key is needed: all endpoints used here are public market data.
 *
 *   GET /fapi/v1/exchangeInfo   - list of symbols
 *   GET /fapi/v1/ticker/24hr    - 24h statistics (quoteVolume)
 *   GET /fapi/v1/klines         - 1-minute candles (high, low, close, quote volume, number of trades)
 *
 * All calls are blocking: call them from a background thread (Dispatchers.IO).
 */
object BinanceApi {
    private const val BASE_URL = "https://fapi.binance.com"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    @Throws(IOException::class)
    private fun get(path: String): String {
        val request = Request.Builder().url(BASE_URL + path).get().build()
        client.newCall(request).execute().use { response ->
            if (response.code == 429 || response.code == 418) {
                throw RateLimitException(response.header("Retry-After")?.toLongOrNull() ?: 60L)
            }
            if (!response.isSuccessful) {
                throw IOException("Binance HTTP ${response.code} for $path")
            }
            return response.body?.string() ?: throw IOException("Empty response for $path")
        }
    }

    /** Currently trading USDT-margined perpetual futures symbols (e.g. BTCUSDT). */
    @Throws(IOException::class)
    fun fetchUsdtPerpetualSymbols(): Set<String> {
        val root = JSONObject(get("/fapi/v1/exchangeInfo"))
        val symbols = root.getJSONArray("symbols")
        val result = HashSet<String>()
        for (i in 0 until symbols.length()) {
            val s = symbols.getJSONObject(i)
            if (s.optString("quoteAsset") == "USDT" &&
                s.optString("contractType") == "PERPETUAL" &&
                s.optString("status") == "TRADING"
            ) {
                result.add(s.getString("symbol"))
            }
        }
        return result
    }

    /** Map of symbol -> 24h quote volume in USDT. */
    @Throws(IOException::class)
    fun fetchQuoteVolumes(): Map<String, Double> {
        val array = JSONArray(get("/fapi/v1/ticker/24hr"))
        val result = HashMap<String, Double>(array.length() * 2)
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val volume = item.optString("quoteVolume").toDoubleOrNull() ?: continue
            result[item.getString("symbol")] = volume
        }
        return result
    }

    /** The last [limit] one-minute candles of [symbol], oldest first (the last one may still be forming). */
    @Throws(IOException::class)
    fun fetchKlines(symbol: String, limit: Int): List<Candle> {
        val array = JSONArray(get("/fapi/v1/klines?symbol=$symbol&interval=1m&limit=$limit"))
        val result = ArrayList<Candle>(array.length())
        for (i in 0 until array.length()) {
            val k = array.getJSONArray(i)
            result.add(
                Candle(
                    openTime = k.getLong(0),
                    closeTime = k.getLong(6),
                    open = k.getString(1).toDouble(),
                    high = k.getString(2).toDouble(),
                    low = k.getString(3).toDouble(),
                    close = k.getString(4).toDouble(),
                    quoteVolume = k.getString(7).toDouble(),
                    trades = k.getLong(8)
                )
            )
        }
        return result
    }
}
