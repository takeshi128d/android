package io.dearone.arutanasample

import android.content.Context
import android.net.Uri
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.browser.customtabs.CustomTabsIntent
import io.dearone.arutanasample.databinding.ViewBannerBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 画像バナー枠を表示するView。
 * - 画像ダウンロード完了 → 表示反映 → trackers.imp を全件・1回だけ発火
 * - タップ → link.url をアプリ内ブラウザ(Custom Tabs)で開く
 *
 * bind() で枠IDと依存を設定し、load() で取得・表示する。
 * 配信なし / 取得失敗 / YAMLなし のときは、この View 自体を GONE にして枠を消し込む（詰める）。
 * 原因を画面で確認したい場合は debug=true にすると理由テキストを表示する。
 */
class BannerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    private val binding = ViewBannerBinding.inflate(LayoutInflater.from(context), this, true)

    private var scope: CoroutineScope? = null
    private var client: ArutanaClient? = null
    private var placementId: Int = 0
    private var uid: String? = null

    private var currentAd: Ad? = null
    private var frequencySetting: PlacementSetting.FrequencySetting? = null
    private var impFired = false

    /** true にすると配信なし/失敗時に枠を消さず理由テキストを表示する（調査用）。 */
    var debug: Boolean = false

    /** 枠の表示/非表示が変わったときの通知（見出しも一緒に消したい場合などに使用）。 */
    var onVisibilityChanged: ((visible: Boolean) -> Unit)? = null

    fun bind(scope: CoroutineScope, client: ArutanaClient, placementId: Int, uid: String?) {
        this.scope = scope
        this.client = client
        this.placementId = placementId
        this.uid = uid
        binding.label.text = "広告枠 placementId=$placementId"
        binding.bannerImage.setOnClickListener { onClickAd() }
    }

    fun load() {
        val scope = scope ?: return
        val client = client ?: return
        impFired = false
        currentAd = null
        // 取得中は枠を出さない（前回表示が残らないよう消し込んでおく）。
        collapse()

        scope.launch {
            when (val r = client.loadAd(placementId, uid)) {
                is AdResult.Loaded -> renderAd(r.ad)
                is AdResult.NoAd -> hideOrDebug("配信なし: ${r.reason}")
                is AdResult.Failed -> hideOrDebug("取得失敗: ${r.reason}")
            }
        }
    }

    private suspend fun renderAd(ad: Ad) {
        currentAd = ad
        val url = ad.creative.mainImageUrl
        if (url.isNullOrEmpty()) { hideOrDebug("mainImageUrl 空"); return }

        val bmp = client?.downloadBitmap(url)
        if (bmp == null) { hideOrDebug("画像取得失敗"); return }

        // 広告あり → 枠を表示。
        visibility = View.VISIBLE
        binding.bannerImage.setImageBitmap(bmp)
        binding.bannerImage.visibility = View.VISIBLE
        binding.status.visibility = View.GONE

        val caution = ad.creative.cautionLabel
        if (!caution.isNullOrEmpty()) {
            binding.caution.text = caution
            binding.caution.visibility = View.VISIBLE
        } else {
            binding.caution.visibility = View.GONE
        }

        onVisibilityChanged?.invoke(true)
        fireImpressionOnce(ad)
    }

    /** 枠を消し込む（高さ0で詰める）。 */
    private fun collapse() {
        binding.bannerImage.visibility = View.GONE
        binding.caution.visibility = View.GONE
        binding.status.visibility = View.GONE
        visibility = View.GONE
        onVisibilityChanged?.invoke(false)
    }

    /** 配信なし/失敗時: 通常は消し込み、debug=true のときだけ理由を表示。 */
    private fun hideOrDebug(reason: String) {
        if (debug) showStatus(reason) else collapse()
    }

    private fun fireImpressionOnce(ad: Ad) {
        if (impFired) return
        impFired = true
        val scope = scope ?: return
        val client = client ?: return
        DebugLog.ok("BANNER", "枠$placementId 表示反映 → imp ${ad.trackers.imp.size}件を発火（viewable/inviewは未送信）")
        scope.launch {
            client.fireImpressions(ad, placementId, frequencySetting)
        }
    }

    private fun onClickAd() {
        val url = currentAd?.link?.url ?: return
        DebugLog.i("CLICK", "枠$placementId タップ → link.url をアプリ内ブラウザで開く\n$url")
        // link.url にクリック計測込み。別のclick計測URLは生成しない。
        try {
            CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
        } catch (e: Exception) {
            DebugLog.err("CLICK", "ブラウザ起動失敗: ${e.message}")
        }
    }

    private fun showStatus(msg: String) {
        visibility = View.VISIBLE
        binding.status.visibility = View.VISIBLE
        binding.status.text = msg
        binding.bannerImage.visibility = View.GONE
        onVisibilityChanged?.invoke(true)
    }
}
