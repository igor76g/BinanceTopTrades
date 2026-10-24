package com.example.binancetoptrades

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Foreground service. Every few minutes it:
 *   1. reads the 24h volume of all USDT-M futures (one request),
 *   2. loads the last 1-minute candles of the coins that pass the volume filter,
 *   3. computes trades and NATR of the last window, applies the conditions,
 *   4. sends the top coins by number of trades as ONE phone notification.
 *
 * Settings are re-read on every cycle, so saved changes apply at the next check.
 */
class MonitorService : Service() {

    companion object {
        private const val TAG = "MonitorService"
        private const val SYMBOLS_REFRESH_MS = 600_000L
        private const val RETRY_AFTER_ERROR_MS = 60_000L
        private const val WARN_AFTER_FAILED_CYCLES = 3
        private const val PARALLEL_REQUESTS = 6

        /** Human-readable status shown in the app UI. */
        val status = MutableStateFlow("Stopped")

        /** The latest list, shown in the app UI. */
        val listText = MutableStateFlow("-")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var symbols: Set<String> = emptySet()
    private var symbolsAtMs = 0L
    private var failedCycles = 0
    private var connectionLost = false
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onBind(intent: Intent?): IBinder? = null

    /** Keep the CPU and Wi-Fi awake while the screen is off, otherwise Doze pauses monitoring. */
    private fun acquireLocks() {
        if (wakeLock?.isHeld != true) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BinanceTopTrades:monitor")
                .apply { setReferenceCounted(false); acquire() }
        }
        if (wifiLock?.isHeld != true) {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "BinanceTopTrades:wifi")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wakeLock = null
        wifiLock = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifier.ensureChannels(this)
        ServiceCompat.startForeground(
            this,
            Notifier.FOREGROUND_ID,
            Notifier.buildServiceNotification(this, "Starting..."),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        acquireLocks()
        publishStatus("Starting: the first check loads candles of all coins...")

        if (job?.isActive != true) {
            job = scope.launch {
                while (isActive) {
                    val settings = SettingsStore.load(applicationContext)
                    val startedMs = System.currentTimeMillis()
                    var waitMs = settings.checkIntervalMinutes.coerceAtLeast(1) * 60_000L
                    try {
                        cycle(settings)
                        onCycleOk()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: RateLimitException) {
                        waitMs = maxOf(RETRY_AFTER_ERROR_MS, e.retryAfterSec * 1000L)
                        onCycleFailed("Binance rate limit, pausing for ${waitMs / 1000} s")
                    } catch (e: Exception) {
                        Log.w(TAG, "Check failed", e)
                        waitMs = minOf(waitMs, RETRY_AFTER_ERROR_MS)
                        onCycleFailed("${e.message ?: e.javaClass.simpleName} (retry in ${waitMs / 1000} s)")
                    }
                    // Start the next cycle one interval after the START of this one (keeps the 5-minute rhythm).
                    delay(maxOf(1000L, startedMs + waitMs - System.currentTimeMillis()))
                }
            }
        }
        return START_STICKY
    }

    /** One monitoring cycle (network calls run on Dispatchers.IO). */
    private suspend fun cycle(settings: Settings) {
        val nowMs = System.currentTimeMillis()

        if (symbols.isEmpty() || nowMs - symbolsAtMs >= SYMBOLS_REFRESH_MS) {
            symbols = BinanceApi.fetchUsdtPerpetualSymbols()
            symbolsAtMs = nowMs
        }
        val volumes = BinanceApi.fetchQuoteVolumes()
        if (volumes.isEmpty()) throw IOException("Empty ticker data")

        val candidates = symbols.filter { (volumes[it] ?: 0.0) > settings.minVolumeUsdt }.sorted()
        val window = settings.windowMinutes.coerceAtLeast(1)
        val failed = AtomicInteger(0)
        val limitError = AtomicReference<RateLimitException?>(null)
        val gate = Semaphore(PARALLEL_REQUESTS)

        // window + 2 candles: one forming, one older candle that provides the previous close
        val stats: List<TopCoin?> = coroutineScope {
            candidates.map { symbol ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        try {
                            val candles = BinanceApi.fetchKlines(symbol, window + 2)
                            val st = Metrics.windowStats(candles, window, nowMs)
                            if (st == null) null else TopCoin(symbol, st.trades, st.natr, volumes[symbol] ?: 0.0)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: RateLimitException) {
                            limitError.set(e)
                            failed.incrementAndGet()
                            null
                        } catch (e: Exception) {
                            failed.incrementAndGet()
                            null
                        }
                    }
                }
            }.awaitAll()
        }
        limitError.get()?.let { throw it }
        if (candidates.isNotEmpty() && failed.get() == candidates.size) {
            throw IOException("Could not load candles for any coin")
        }

        val top = Metrics.selectTop(stats.filterNotNull(), settings)
        val lines = Notifier.formatLines(top)
        listText.value = if (lines.isEmpty()) "No coin matches the conditions" else lines.joinToString("\n")

        if (top.isNotEmpty() || settings.notifyWhenEmpty) {
            Notifier.sendTopList(this, top, window, settings.soundEveryUpdate)
        }
        Log.i(TAG, "Checked ${candidates.size} coins, ${top.size} match")

        val failedText = if (failed.get() > 0) " (${failed.get()} coins skipped)" else ""
        publishStatus(
            "Checked ${candidates.size} coins$failedText at ${timeFormat.format(Date())}. " +
                "${top.size} match. Next check in ${settings.checkIntervalMinutes} min."
        )
    }

    private fun onCycleOk() {
        failedCycles = 0
        if (connectionLost) {
            connectionLost = false
            Notifier.sendWarning(this, "Connection restored", "Market data from Binance is flowing again.")
        }
    }

    private fun onCycleFailed(text: String) {
        failedCycles++
        publishStatus("Error: $text")
        if (failedCycles >= WARN_AFTER_FAILED_CYCLES && !connectionLost) {
            connectionLost = true
            Notifier.sendWarning(
                this,
                "Connection lost",
                "Binance data could not be loaded several times in a row. " +
                    "The list is NOT being updated. Check the internet / VPN."
            )
        }
    }

    private fun publishStatus(text: String) {
        status.value = text
        Notifier.updateServiceNotification(this, text)
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        releaseLocks()
        status.value = "Stopped"
        super.onDestroy()
    }
}
