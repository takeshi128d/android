package io.dearone.arutanasample

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import io.dearone.arutanasample.databinding.FragmentHomeBinding

/**
 * ホーム画面（トップ面）。広告枠1（1446）と 枠2（99999, ダミー）を表示する。
 * トップ面アクセス時（onResume）にバナーリクエストを発火する。
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val client by lazy { ArutanaClient(requireContext().applicationContext) }

    // 会員ID等。検証用の固定値。
    private val uid = "arutanaapitest"

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val scope = viewLifecycleOwner.lifecycleScope

        binding.bannerSlot1.bind(scope, client, 1446, uid)    // 実枠
        binding.bannerSlot2.bind(scope, client, 99999, uid)   // ダミー枠

        // 配信なしで枠が消えたら見出しも一緒に消し込む。
        binding.bannerSlot1.onVisibilityChanged = { visible ->
            binding.sectionOsusume.visibility = if (visible) View.VISIBLE else View.GONE
        }
        binding.bannerSlot2.onVisibilityChanged = { visible ->
            binding.sectionPr.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    /** バナーを再取得する（トップ面アクセス／更新ボタンから）。 */
    fun reload() {
        _binding?.let {
            it.bannerSlot1.load()
            it.bannerSlot2.load()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
