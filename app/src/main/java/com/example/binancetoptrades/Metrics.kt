package com.example.binancetoptrades

import kotlin.math.abs
import kotlin.math.max

/** Statistics of the last window of 1-minute candles. */
data class WindowStats(val trades: Long, val natr: Double, val volume: Double)

/** A coin that is a candidate for the list. */
data class TopCoin(val symbol: String, val trades: Long, val natr: Double, val volume24h: Double)

/**
 * Definitions (all on 1-minute candles):
 *  - TR (true range) of a candle = max(high - low, |high - previous close|, |low - previous close|).
 *  - NATR of a candle = TR / close * 100 (percent).
 *  - NATR of the window = average NATR of its candles.
 *  - Trades of the window = sum of the "number of trades" of its candles.
 */
object Metrics {

    /** NATR (%) of every candle. The first candle uses high - low (no previous close). */
    fun natrValues(candles: List<Candle>): List<Double> {
        val result = ArrayList<Double>(candles.size)
        var prevClose: Double? = null
        for (c in candles) {
            var tr = c.high - c.low
            val p = prevClose
            if (p != null) {
                tr = max(tr, max(abs(c.high - p), abs(c.low - p)))
            }
            result.add(if (c.close > 0.0) tr / c.close * 100.0 else 0.0)
            prevClose = c.close
        }
        return result
    }

    /**
     * Statistics of the last [window] CLOSED candles, or null if there are fewer than [window] of them
     * (a new listing). One extra older candle is used, when available, as the previous close.
     */
    fun windowStats(candles: List<Candle>, window: Int, nowMs: Long): WindowStats? {
        val closed = candles.filter { it.closeTime < nowMs }
        if (window < 1 || closed.size < window) return null
        val used = closed.takeLast(window + 1)
        val win = used.takeLast(window)
        val natr = natrValues(used).takeLast(window)
        var trades = 0L
        var volume = 0.0
        for (c in win) {
            trades += c.trades
            volume += c.quoteVolume
        }
        return WindowStats(trades, natr.average(), volume)
    }

    /** Applies the user's conditions and returns the top coins by number of trades. */
    fun selectTop(coins: List<TopCoin>, s: Settings): List<TopCoin> =
        coins
            .filter { it.volume24h > s.minVolumeUsdt && it.trades >= s.minTrades && it.natr >= s.minNatr }
            .sortedByDescending { it.trades }
            .take(s.topCount.coerceAtLeast(1))
}
