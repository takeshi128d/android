package io.dearone.arutanasample

import org.json.JSONObject

/** JSONObject の安全な取得ヘルパ。 */
fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null

fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null

fun JSONObject.optStringList(key: String): List<String> {
    val arr = optJSONArray(key) ?: return emptyList()
    val out = ArrayList<String>(arr.length())
    for (i in 0 until arr.length()) {
        val s = arr.optString(i, "")
        if (s.isNotEmpty()) out.add(s)
    }
    return out
}
