package com.example.binancetoptrades

import android.content.Context

/**
 * All user-editable settings in one place.
 * Defaults match the requested conditions: 24h volume > 30M USDT, at least 10000 trades and NATR >= 0.7
 * within the last 15 minutes, top 7 coins, every 5 minutes.
 */
data class Settings(
    // --- Volume filter ---
    val minVolumeMillionUsdt: Double = 30.0,   // 24h quote volume must be MORE than this, in millions of USDT

    // --- NATR filter ---
    val minNatr: Double = 0.7,                 // NATR of the last window, in %, at least

    // --- Trades filter ---
    val minTrades: Int = 10000,                // trades within the last window, at least
    val topCount: Int = 7,                     // how many coins to put into the list

    // --- General ---
    val windowMinutes: Int = 15,               // "last N minutes" for trades and NATR
    val checkIntervalMinutes: Int = 5,         // how often the list is rebuilt and sent
    val notifyWhenEmpty: Boolean = false,      // send a notification even if no coin matches
    val soundEveryUpdate: Boolean = true       // play the notification sound on every update
) {
    val minVolumeUsdt: Double get() = minVolumeMillionUsdt * 1_000_000.0
}

object SettingsStore {
    private const val PREFS = "binance_top_trades_settings"

    fun load(context: Context): Settings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = Settings()
        return Settings(
            minVolumeMillionUsdt = prefs.getString("minVolumeMillionUsdt", null)?.toDoubleOrNull() ?: d.minVolumeMillionUsdt,
            minNatr = prefs.getString("minNatr", null)?.toDoubleOrNull() ?: d.minNatr,
            minTrades = prefs.getInt("minTrades", d.minTrades),
            topCount = prefs.getInt("topCount", d.topCount),
            windowMinutes = prefs.getInt("windowMinutes", d.windowMinutes),
            checkIntervalMinutes = prefs.getInt("checkIntervalMinutes", d.checkIntervalMinutes),
            notifyWhenEmpty = prefs.getBoolean("notifyWhenEmpty", d.notifyWhenEmpty),
            soundEveryUpdate = prefs.getBoolean("soundEveryUpdate", d.soundEveryUpdate)
        )
    }

    fun save(context: Context, s: Settings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("minVolumeMillionUsdt", s.minVolumeMillionUsdt.toString())
            .putString("minNatr", s.minNatr.toString())
            .putInt("minTrades", s.minTrades)
            .putInt("topCount", s.topCount)
            .putInt("windowMinutes", s.windowMinutes)
            .putInt("checkIntervalMinutes", s.checkIntervalMinutes)
            .putBoolean("notifyWhenEmpty", s.notifyWhenEmpty)
            .putBoolean("soundEveryUpdate", s.soundEveryUpdate)
            .apply()
    }
}
