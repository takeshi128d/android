package io.dearone.arutanasample

import android.graphics.Color
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import io.dearone.arutanasample.databinding.FragmentLogBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 通信ログ画面。DebugLog の内容をリアルタイム表示する。
 * 送受信（YAML GET / 広告POST / imp / 画像）のすべてが時系列で見える。
 */
class LogFragment : Fragment() {

    private var _binding: FragmentLogBinding? = null
    private val binding get() = _binding!!

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.JAPAN)
    private val listener: () -> Unit = { render() }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnClear.setOnClickListener { DebugLog.clear() }
        binding.btnTest.setOnClickListener { runTestTraffic() }
    }

    /** ログ画面を見ながら通信を発生させる（枠1446 / 99999 を取得→imp発火）。 */
    private fun runTestTraffic() {
        val client = ArutanaClient(requireContext().applicationContext)
        val uid = "arutanaapitest"
        viewLifecycleOwner.lifecycleScope.launch {
            for (id in listOf(1446, 99999)) {
                when (val r = client.loadAd(id, uid)) {
                    is AdResult.Loaded -> client.fireImpressions(r.ad, id, null)
                    else -> { /* ログに理由が出力済み */ }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        DebugLog.addListener(listener)
        render()
    }

    override fun onPause() {
        super.onPause()
        DebugLog.removeListener(listener)
    }

    private fun render() {
        val b = _binding ?: return
        val sb = SpannableStringBuilder()
        for (e in DebugLog.snapshot()) {
            val header = "${timeFmt.format(Date(e.time))}  [${e.tag}] "
            appendColored(sb, header, Color.parseColor("#7D7D8A"))
            appendColored(sb, e.message + "\n", colorFor(e.level))
        }
        b.logText.text = sb
        if (b.autoScroll.isChecked) {
            b.scroll.post { b.scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun appendColored(sb: SpannableStringBuilder, text: String, color: Int) {
        val start = sb.length
        sb.append(text)
        sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun colorFor(level: DebugLog.Level): Int = when (level) {
        DebugLog.Level.INFO -> Color.parseColor("#E6E6E6")
        DebugLog.Level.REQ -> Color.parseColor("#60A5FA")  // 送信
        DebugLog.Level.RES -> Color.parseColor("#C4B5FD")  // 受信
        DebugLog.Level.OK -> Color.parseColor("#4ADE80")
        DebugLog.Level.WARN -> Color.parseColor("#FBBF24")
        DebugLog.Level.ERR -> Color.parseColor("#F87171")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
