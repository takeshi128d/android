package io.dearone.arutanasample

import android.os.Handler
import android.os.Looper

/**
 * アプリ内デバッグログ。通信の送受信をリアルタイムで画面表示するための簡易ロガー。
 * スレッドセーフ。更新はメインスレッドでリスナーに通知する。
 */
object DebugLog {

    enum class Level { INFO, REQ, RES, OK, WARN, ERR }

    data class Entry(
        val time: Long,
        val level: Level,
        val tag: String,
        val message: String
    )

    private const val MAX = 800
    private val entries = ArrayList<Entry>()
    private val listeners = ArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    @Synchronized
    fun add(level: Level, tag: String, message: String) {
        entries.add(Entry(System.currentTimeMillis(), level, tag, message))
        while (entries.size > MAX) entries.removeAt(0)
        notifyListeners()
    }

    fun i(tag: String, m: String) = add(Level.INFO, tag, m)
    fun req(tag: String, m: String) = add(Level.REQ, tag, m)
    fun res(tag: String, m: String) = add(Level.RES, tag, m)
    fun ok(tag: String, m: String) = add(Level.OK, tag, m)
    fun warn(tag: String, m: String) = add(Level.WARN, tag, m)
    fun err(tag: String, m: String) = add(Level.ERR, tag, m)

    @Synchronized
    fun snapshot(): List<Entry> = ArrayList(entries)

    @Synchronized
    fun clear() {
        entries.clear()
        notifyListeners()
    }

    @Synchronized
    fun addListener(l: () -> Unit) { listeners.add(l) }

    @Synchronized
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    private fun notifyListeners() {
        val snapshot = ArrayList(listeners)
        main.post { snapshot.forEach { it() } }
    }
}
