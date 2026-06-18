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
 * 配信なし/失敗時は、デバッグ確認しやすいよう状態テキストを薄く表示する。
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
        binding.bannerImage.visibility = View.GONE
        binding.caution.visibility = View.GONE
        showStatus("広告取得中…")

        scope.launch {
            when (val r = client.loadAd(placementId, uid)) {
                is AdResult.Loaded -> renderAd(r.ad)
                is AdResult.NoAd -> showStatus("配信なし: ${r.reason}")
                is AdResult.Failed -> showStatus("取得失敗: ${r.reason}")
            }
        }
    }

    private suspend fun renderAd(ad: Ad) {
        currentAd = ad
        val url = ad.creative.mainImageUrl
        if (url.isNullOrEmpty()) { showStatus("mainImageUrl 空"); return }

        val bmp = client?.downloadBitmap(url)
        if (bmp == null) { showStatus("画像取得失敗"); return }

        binding.bannerImage.setImageBitmap(bmp)
        binding.bannerImage.visibility = View.VISIBLE
        binding.status.visibility = View.GONE

        val caution = ad.creative.cautionLabel
        if (!caution.isNullOrEmpty()) {
            binding.caution.text = caution
            binding.caution.visibility = View.VISIBLE
        }

        fireImpressionOnce(ad)
    }

    private fun fireImpressionOnce(ad: Ad) {
        if (impFired) return
        impFired = true
        val scope = scope ?: return
        val client = client ?: return
        scope.launch {
            client.fireImpressions(ad, placementId, frequencySetting)
        }
    }

    private fun onClickAd() {
        val url = currentAd?.link?.url ?: return
        // link.url にクリック計測込み。別のclick計測URLは生成しない。
        try {
            CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
        } catch (e: Exception) {
            // ブラウザが無い等。必要なら ACTION_VIEW にフォールバック。
        }
    }

    private fun showStatus(msg: String) {
        binding.status.visibility = View.VISIBLE
        binding.status.text = msg
        binding.bannerImage.visibility = View.GONE
    }
}
