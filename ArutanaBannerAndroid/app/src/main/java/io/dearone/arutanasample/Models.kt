package io.dearone.arutanasample

/**
 * ARUTANA API 連携のモデル定義。
 * 実レスポンス（枠ID 1339 で検証済み）に合わせて creative.cautionLabel / videoUrl も保持する。
 */

/** 広告枠設定YAML（GET /placement-setting/{placementId}.yaml）。 */
data class PlacementSetting(
    val doTargeting: Boolean,
    val siteId: Int,
    val placementId: Int,
    val adTypeId: Int,
    val frequencySetting: FrequencySetting?
) {
    data class FrequencySetting(val interval: Int, val count: Int)
}

data class Creative(
    val id: Int?,
    val mainImageUrl: String?,
    val width: Int?,
    val height: Int?,
    val cautionLabel: String?
)

data class LinkInfo(val type: String?, val url: String?)

data class Trackers(
    val imp: List<String>,
    val viewableImp: List<String>,
    val inviewImp: List<String>
)

data class Ad(
    val campaignId: Int?,
    val adGroupId: Int?,
    val adId: Int?,
    val creative: Creative,
    val link: LinkInfo,
    val trackers: Trackers
)

/** 広告取得の結果。 */
sealed class AdResult {
    /** 表示する広告が取得できた。 */
    data class Loaded(val ad: Ad, val requestId: String?) : AdResult()
    /** 配信なし（placements空 / ads空 / mainImageUrl空 / フリークエンシーキャップ上限）。 */
    data class NoAd(val reason: String) : AdResult()
    /** 取得失敗（HTTP400 / non-200 / 通信失敗 / タイムアウト）。 */
    data class Failed(val reason: String) : AdResult()
}
