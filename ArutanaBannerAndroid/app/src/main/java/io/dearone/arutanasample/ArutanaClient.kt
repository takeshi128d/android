package io.dearone.arutanasample

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * ARUTANA API 連携の中核（SDK非利用）。
 * SDKが内部で行っていた「YAML取得 → 広告取得API → レスポンス解釈 → imp計測発火」を実装する。
 *
 * 接続先はテスト環境(staging)。本番は base URL を差し替える。
 */
class ArutanaClient(
    private val context: Context,
    private val frequencyCap: FrequencyCapManager = FrequencyCapManager(context)
) {
    companion object {
        private const val TAG = "ArutanaClient"
        private const val CONTENTS_BASE = "https://contents.stg.ads.arutana.jp"
        private const val AD_BASE = "https://ad.stg.ads.arutana.jp"
        private const val NETWORK_ID = 1            // iAEON向けは 1 固定
        private const val TIMEOUT_MS = 3000          // 仕様の推奨タイムアウト
        private const val DEBUG_ID = "android-sample"
    }

    /** 広告枠設定YAMLの同日内キャッシュ。 */
    private val settingCache = HashMap<Int, Pair<PlacementSetting, String>>()

    /** 広告取得の全フロー。 */
    suspend fun loadAd(placementId: Int, uid: String?): AdResult = withContext(Dispatchers.IO) {
        val setting = fetchSetting(placementId)
            ?: return@withContext AdResult.Failed("広告枠設定YAML取得失敗")

        if (!frequencyCap.canShow(placementId, setting.frequencySetting)) {
            return@withContext AdResult.NoAd("フリークエンシーキャップ上限")
        }

        val resp = fetchAd(setting, uid)
            ?: return@withContext AdResult.Failed("広告取得失敗")

        val requestId = resp.optStringOrNull("requestId")
        Log.d(TAG, "requestId=$requestId placementId=$placementId")

        val placements = resp.optJSONArray("placements")
        if (placements == null || placements.length() == 0) {
            return@withContext AdResult.NoAd("placements 空")
        }
        val ads = placements.getJSONObject(0).optJSONArray("ads")
        if (ads == null || ads.length() == 0) {
            return@withContext AdResult.NoAd("ads 空")
        }
        val ad = parseAd(ads.getJSONObject(0))
        if (ad.creative.mainImageUrl.isNullOrEmpty()) {
            return@withContext AdResult.NoAd("mainImageUrl 空")
        }
        // 表示確定をフリークエンシーキャップに記録（実発火は imp 時）。
        AdResult.Loaded(ad, requestId)
    }

    /** imp計測URLを全件発火する（viewable/inview は送信しない）。 */
    suspend fun fireImpressions(ad: Ad, placementId: Int, setting: PlacementSetting.FrequencySetting?) =
        withContext(Dispatchers.IO) {
            ad.trackers.imp.forEach { fireTracker(it) }
            frequencyCap.recordImpression(placementId, setting)
        }

    /** 単一トラッカーURLへGETを送る。 */
    fun fireTracker(url: String) {
        try {
            val (code, _) = httpGet(url) ?: return
            Log.d(TAG, "tracker fired status=$code url=$url")
        } catch (e: Exception) {
            Log.w(TAG, "tracker failed: $url", e)
        }
    }

    /** 画像をダウンロードする。 */
    suspend fun downloadBitmap(url: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            Log.w(TAG, "image download failed: $url", e)
            null
        }
    }

    // ---- 内部処理 ----

    private fun today(): String {
        val cal = java.util.Calendar.getInstance()
        return "%04d%02d%02d".format(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    private fun fetchSetting(placementId: Int): PlacementSetting? {
        settingCache[placementId]?.let { (s, day) -> if (day == today()) return s }

        val url = "$CONTENTS_BASE/placement-setting/$placementId.yaml"
        val (code, body) = httpGet(url) ?: return null
        if (code != 200) {
            Log.w(TAG, "placement-setting status=$code ($url)")
            return null
        }
        val setting = parseYaml(body) ?: return null
        settingCache[placementId] = setting to today()
        return setting
    }

    private fun fetchAd(setting: PlacementSetting, uid: String?): JSONObject? {
        val did = androidId()
        val effectiveUid = uid ?: did   // uid 未指定時は did を uid にも入れる（iOS SDK同挙動）

        val body = JSONObject().apply {
            put("doTargeting", setting.doTargeting)
            put("siteId", setting.siteId)
            if (!effectiveUid.isNullOrEmpty()) put("uid", effectiveUid)
            if (!did.isNullOrEmpty()) put("did", did)
            put("placements", JSONArray().put(JSONObject().apply {
                put("id", setting.placementId)
                put("n", 1)
                put("adTypes", JSONArray().put(setting.adTypeId))
            }))
        }

        val url = "$AD_BASE/v1/ad/$NETWORK_ID"
        val (code, resp) = httpPostJson(url, body.toString()) ?: return null
        when {
            code == 400 -> { Log.w(TAG, "ad HTTP 400 不正リクエスト"); return null }
            code !in 200..299 -> { Log.w(TAG, "ad HTTP $code"); return null }
        }
        return try {
            JSONObject(resp)
        } catch (e: Exception) {
            Log.w(TAG, "ad response parse error", e)
            null
        }
    }

    private fun parseAd(o: JSONObject): Ad {
        val c = o.optJSONObject("creative") ?: JSONObject()
        val at = c.optJSONObject("adType")
        val creative = Creative(
            id = c.optIntOrNull("id"),
            mainImageUrl = c.optStringOrNull("mainImageUrl"),
            width = at?.optIntOrNull("width"),
            height = at?.optIntOrNull("height"),
            cautionLabel = c.optStringOrNull("cautionLabel")
        )
        val l = o.optJSONObject("link") ?: JSONObject()
        val link = LinkInfo(l.optStringOrNull("type"), l.optStringOrNull("url"))
        val t = o.optJSONObject("trackers") ?: JSONObject()
        val trackers = Trackers(
            imp = t.optStringList("imp"),
            viewableImp = t.optStringList("viewableImp"),
            inviewImp = t.optStringList("inviewImp")
        )
        return Ad(
            campaignId = o.optIntOrNull("campaignId"),
            adGroupId = o.optIntOrNull("adGroupId"),
            adId = o.optIntOrNull("adId"),
            creative = creative,
            link = link,
            trackers = trackers
        )
    }

    private fun parseYaml(text: String): PlacementSetting? {
        val top = HashMap<String, String>()
        val freq = HashMap<String, String>()
        var inFreq = false
        for (raw in text.split("\n")) {
            val line = raw.substringBefore("#")
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed == "---" || trimmed == "...") continue
            val indented = line.isNotEmpty() && (line[0] == ' ' || line[0] == '\t')
            val idx = trimmed.indexOf(':')
            if (idx < 0) continue
            val k = trimmed.substring(0, idx).trim()
            val v = trimmed.substring(idx + 1).trim()
            if (k == "frecuencySetting" || k == "frequencySetting") { inFreq = true; continue }
            if (indented && inFreq) freq[k] = v else { inFreq = false; top[k] = v }
        }
        val doTargeting = (top["doTargeting"]?.lowercase() in listOf("true", "yes", "on", "1"))
        val siteId = top["siteId"]?.toIntOrNull() ?: return null
        val placementId = top["placementId"]?.toIntOrNull() ?: return null
        val adTypeId = top["adTypeId"]?.toIntOrNull() ?: return null
        val interval = freq["interval"]?.toIntOrNull()
        val count = freq["count"]?.toIntOrNull()
        val fs = if (interval != null && count != null)
            PlacementSetting.FrequencySetting(interval, count) else null
        return PlacementSetting(doTargeting, siteId, placementId, adTypeId, fs)
    }

    private fun androidId(): String? = try {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    } catch (e: Exception) {
        null
    }

    private fun httpGet(urlStr: String): Pair<Int, String>? = try {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("x-debug-id", DEBUG_ID)
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        Pair(code, text)
    } catch (e: Exception) {
        Log.w(TAG, "GET failed: $urlStr", e)
        null
    }

    private fun httpPostJson(urlStr: String, json: String): Pair<Int, String>? = try {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-debug-id", DEBUG_ID)
        }
        conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        Pair(code, text)
    } catch (e: Exception) {
        Log.w(TAG, "POST failed: $urlStr", e)
        null
    }
}
