package io.dearone.arutanasample

import android.content.Context

/**
 * フリークエンシーキャップ（interval / count による表示回数制御）。
 * SharedPreferences に表示時刻を保持する簡易実装。
 * 枠1339は frecuencySetting なしのため、その場合は常に表示可。
 */
class FrequencyCapManager(context: Context) {

    private val prefs = context.getSharedPreferences("arutana_freqcap", Context.MODE_PRIVATE)

    fun canShow(placementId: Int, setting: PlacementSetting.FrequencySetting?): Boolean {
        if (setting == null || setting.count <= 0 || setting.interval <= 0) return true
        val windowStart = System.currentTimeMillis() - setting.interval * 3600_000L
        val recent = stamps(placementId).count { it >= windowStart }
        return recent < setting.count
    }

    fun recordImpression(placementId: Int, setting: PlacementSetting.FrequencySetting?) {
        val now = System.currentTimeMillis()
        var list = stamps(placementId).toMutableList()
        list.add(now)
        if (setting != null && setting.interval > 0) {
            val cutoff = now - setting.interval * 3600_000L * 2
            list = list.filter { it >= cutoff }.toMutableList()
        }
        prefs.edit().putString(key(placementId), list.joinToString(",")).apply()
    }

    private fun stamps(placementId: Int): List<Long> {
        val s = prefs.getString(key(placementId), "") ?: ""
        if (s.isEmpty()) return emptyList()
        return s.split(",").mapNotNull { it.toLongOrNull() }
    }

    private fun key(placementId: Int) = "fc_$placementId"
}
