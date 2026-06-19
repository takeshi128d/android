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
        DebugLog.i("LOAD", "==== 枠$placementId 広告ロード開始 (uid=$uid) ====")

        val setting = fetchSetting(placementId)
            ?: return@withContext AdResult.Failed("広告枠設定YAML取得失敗")
        DebugLog.i("LOAD", "枠$placementId setting siteId=${setting.siteId} adTypeId=${setting.adTypeId} freq=${setting.frequencySetting}")

        if (!frequencyCap.canShow(placementId, setting.frequencySetting)) {
            DebugLog.warn("LOAD", "枠$placementId フリークエンシーキャップ上限 → 配信なし扱い")
            return@withContext AdResult.NoAd("フリークエンシーキャップ上限")
        }

        val resp = fetchAd(setting, uid)
            ?: return@withContext AdResult.Failed("広告取得失敗")

        val requestId = resp.optStringOrNull("requestId")
        if (requestId != null) DebugLog.i("AD", "requestId=$requestId")

        val placements = resp.optJSONArray("placements")
        if (placements == null || placements.length() == 0) {
            DebugLog.warn("LOAD", "枠$placementId placements 空 → 配信なし・消し込み")
            return@withContext AdResult.NoAd("placements 空")
        }
        val ads = placements.getJSONObject(0).optJSONArray("ads")
        if (ads == null || ads.length() == 0) {
            DebugLog.warn("LOAD", "枠$placementId ads 空 → 配信なし・消し込み")
            return@withContext AdResult.NoAd("ads 空")
        }
        val ad = parseAd(ads.getJSONObject(0))
        if (ad.creative.mainImageUrl.isNullOrEmpty()) {
            DebugLog.warn("LOAD", "枠$placementId mainImageUrl 空 → 消し込み")
            return@withContext AdResult.NoAd("mainImageUrl 空")
        }
        DebugLog.ok("LOAD", "枠$placementId 採用 adId=${ad.adId} imp=${ad.trackers.imp.size}件 click=${ad.link.url}")
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
        DebugLog.req("IMP", "GET $url")
        try {
            val (code, _) = httpGet(url) ?: run {
                DebugLog.err("IMP", "発火失敗（通信エラー）: $url")
                return
            }
            DebugLog.res("IMP", "HTTP $code")
        } catch (e: Exception) {
            DebugLog.err("IMP", "発火失敗: ${e.message}")
        }
    }

    /** 画像をダウンロードする。 */
    suspend fun downloadBitmap(url: String): Bitmap? = withContext(Dispatchers.IO) {
        DebugLog.req("IMG", "GET $url")
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }
            val bmp = conn.inputStream.use { BitmapFactory.decodeStream(it) }
            DebugLog.res("IMG", if (bmp != null) "取得成功 ${bmp.width}x${bmp.height}" else "デコード失敗")
            bmp
        } catch (e: Exception) {
            DebugLog.err("IMG", "画像取得失敗: ${e.message}")
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
        settingCache[placementId]?.let { (s, day) ->
            if (day == today()) {
                DebugLog.i("YAML", "枠$placementId 設定YAMLを同日キャッシュから使用")
                return s
            }
        }

        val url = "$CONTENTS_BASE/placement-setting/$placementId.yaml"
        DebugLog.req("YAML", "GET $url\nx-debug-id: $DEBUG_ID")
        val (code, body) = httpGet(url) ?: run {
            DebugLog.err("YAML", "枠$placementId 通信失敗/タイムアウト")
            return null
        }
        DebugLog.res("YAML", "HTTP $code\n${truncate(body)}")
        if (code != 200) {
            DebugLog.warn("YAML", "枠$placementId 設定取得不可（status=$code）→ 配信なし扱い")
            return null
        }
        val setting = parseYaml(body)
        if (setting == null) {
            DebugLog.err("YAML", "枠$placementId YAML解析失敗")
            return null
        }
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
        DebugLog.req(
            "AD",
            "POST $url\nContent-Type: application/json\nx-debug-id: $DEBUG_ID\n\n${body.toString(2)}"
        )
        val (code, resp) = httpPostJson(url, body.toString()) ?: run {
            DebugLog.err("AD", "枠${setting.placementId} 通信失敗/タイムアウト")
            return null
        }
        DebugLog.res("AD", "HTTP $code\n${truncate(resp)}")
        when {
            code == 400 -> { DebugLog.err("AD", "HTTP 400 不正リクエスト → 表示しない"); return null }
            code !in 200..299 -> { DebugLog.warn("AD", "HTTP $code → 表示しない"); return null }
        }
        return try {
            JSONObject(resp)
        } catch (e: Exception) {
            DebugLog.err("AD", "レスポンス解析失敗: ${e.message}")
            null
        }
    }

    private fun truncate(s: String, max: Int = 4000): String =
        if (s.length <= max) s else s.substring(0, max) + "…(${s.length}文字)"

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
