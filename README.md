# Binance Top Trades (Android, Kotlin)

Scans all Binance USDT-M perpetual futures and sends ONE phone notification every 5 minutes with the list of
the 7 coins that had the most trades in the last 15 minutes. A coin is included only if:

- 24h quote volume is MORE than **30 million USDT**,
- the number of trades in the last 15 minutes is at least **10000**,
- NATR of the last 15 minutes is at least **0.7 %**.

Uses only PUBLIC Binance Futures endpoints (`/fapi/v1/exchangeInfo`, `/fapi/v1/ticker/24hr`,
`/fapi/v1/klines`). No API key is needed. All values above are editable in the app (volume, NATR, trades,
list size, window length, interval).

## Get the APK (GitHub builds it for free)

1. Create a GitHub repository and upload the contents of this folder (the hidden `.github` folder must be included).
2. Open the **Actions** tab. The "Build APK" workflow starts by itself (or: Run workflow).
3. When it finishes (about 5 minutes) open the run and download the artifact **BinanceTopTrades-debug-apk**
   (a zip with `app-debug.apk`). Copy the APK to the phone and install it (allow "install unknown apps").

Or open the folder in Android Studio and press Run.

## Use

1. Open the app, adjust the settings, tap **Save settings**.
2. Tap **Start monitoring**, allow notifications and allow the app to run without battery restrictions.
3. The first check loads candles of all coins and takes longer; then the list arrives every 5 minutes.
   The current list is also shown in the app.

## Definitions (1-minute candles)

- NATR of a candle = true range / close x 100 (true range uses the previous close).
- NATR of the window = average NATR of the last 15 closed candles.
- Trades = sum of the "number of trades" of the same 15 candles.

## Settings

| Setting | Default | Meaning |
|---|---|---|
| Minimum 24h volume (million USDT) | 30 | Volume filter (strictly more than) |
| Minimum NATR of the last window (%) | 0.7 | NATR filter |
| Minimum number of trades in the last window | 10000 | Trades filter |
| Number of coins in the list | 7 | Size of the list |
| Window: last N minutes | 15 | Window for trades and NATR |
| Check and notify interval (minutes) | 5 | How often the list is rebuilt and sent |
| Send a notification even when no coin matches | off | Otherwise nothing is sent for an empty list |
| Play a sound on every update | on | Off = the new list silently replaces the old one |

## Notes

- If no coin matches, no notification is sent (unless the option above is on); the app still shows the status.
- If Binance data cannot be loaded 3 checks in a row, a "Connection lost" notification is sent, and
  "Connection restored" when it is back.
- For reliable work with the screen off: allow the battery exemption on Start, and on Xiaomi / Huawei /
  Samsung / OnePlus also set Settings > Apps > Binance Top Trades > Battery > Unrestricted and enable Autostart.
- Traffic: one 24h ticker request plus one small candle request per coin above the volume limit, every 5 minutes.
