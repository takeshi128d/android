package io.dearone.arutanasample

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import io.dearone.arutanasample.databinding.ActivityMainBinding

/**
 * メニューバー（上部AppBar＋下部ナビ）を持ち、下部ナビで別画面（Fragment）へ切り替える。
 * ホーム画面に広告枠（1446 / 99999）を表示する。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.topAppBar)

        if (savedInstanceState == null) {
            showScreen(HomeFragment(), "ホーム")
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> showScreen(HomeFragment(), "ホーム")
                R.id.nav_news -> showScreen(NewsFragment(), "お知らせ")
                R.id.nav_mypage -> showScreen(MyPageFragment(), "マイページ")
            }
            true
        }
    }

    private fun showScreen(fragment: Fragment, title: String) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.navHost, fragment)
            .commit()
        supportActionBar?.title = title
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.top_app_bar, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> {
                (supportFragmentManager.findFragmentById(R.id.navHost) as? HomeFragment)?.reload()
                true
            }
            R.id.action_info -> {
                Snackbar.make(binding.root, "ARUTANA API連携サンプル（枠 1446 / 99999）", Snackbar.LENGTH_LONG).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
