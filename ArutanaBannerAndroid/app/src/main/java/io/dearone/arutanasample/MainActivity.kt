package io.dearone.arutanasample

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import io.dearone.arutanasample.databinding.ActivityMainBinding

/**
 * トップ面。メニューバー（上部AppBar＋下部ナビ）と、複数のバナー枠（1339 / 99999）を持つ。
 * トップ面アクセス時（onResume）にバナーリクエストを発火する。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val client by lazy { ArutanaClient(applicationContext) }

    // 会員ID等。送れる場合は最優先。送れない場合は did で代替（クライアント側で処理）。
    private val uid = "iaeon-member-0001"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.topAppBar)

        // 複数バナー枠を登録（同様のID=1339 と 99999）。
        binding.bannerSlot1.bind(lifecycleScope, client, 1339, uid)
        binding.bannerSlot2.bind(lifecycleScope, client, 99999, uid)

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> { reloadBanners(); true }
                else -> {
                    Snackbar.make(binding.root, "${item.title}（サンプル）", Snackbar.LENGTH_SHORT).show()
                    true
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // トップ面アクセス時にバナーリクエストを出す。
        reloadBanners()
    }

    private fun reloadBanners() {
        binding.bannerSlot1.load()
        binding.bannerSlot2.load()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.top_app_bar, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> { reloadBanners(); true }
            R.id.action_info -> {
                Snackbar.make(binding.root, "ARUTANA API連携サンプル（枠 1339 / 99999）", Snackbar.LENGTH_LONG).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
