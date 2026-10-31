package pl.pixel.island

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

class IslandService : AccessibilityService() {
    companion object { @Volatile var instance: IslandService? = null }

    private val h = Handler(Looper.getMainLooper())
    private val items = LinkedHashMap<String, Item>()
    private val hides = HashMap<String, Runnable>()
    private var view: IslandView? = null
    private var shownKey: String? = null
    private var seq = 0L
    private var torchId: String? = null
    private var pinnedKey: String? = null
    private var restoreKey: String? = null
    private var forceExpand = false
    private lateinit var wm: WindowManager

    private val torchCb = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (enabled) {
                torchId = cameraId
                post(Item("torch", Type.TORCH, "Latarka", "Włączona", glyph = R.drawable.ic_torch, glyphTint = 0xFFFFC107.toInt(), ind = Ind.TORCH,
                    acts = listOf(Act("Wyłącz") { setTorch(false) })))
            } else remove("torch")
        }
    }

    private val power = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_POWER_CONNECTED) {
                val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val lvl = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
                val max = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
                val pct = lvl * 100 / max
                post(Item("charge", Type.CHARGE, "Ładowanie", "$pct%", glyph = R.drawable.ic_bolt, glyphTint = 0xFF34C759.toInt(), ind = Ind.BATT,
                    indText = "$pct%", autoHideMs = 3500))
            } else remove("charge")
        }
    }

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        getSystemService(CameraManager::class.java).registerTorchCallback(mainExecutor, torchCb)
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(power, f, Context.RECEIVER_EXPORTED)
        NotifService.instance?.resync()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c); rebuild()
    }

    override fun onDestroy() {
        instance = null
        detach(false)
        try { getSystemService(CameraManager::class.java).unregisterTorchCallback(torchCb) } catch (_: Exception) {}
        try { unregisterReceiver(power) } catch (_: Exception) {}
        super.onDestroy()
    }

    // ---------- API dla reszty aplikacji ----------
    fun post(i: Item) = h.post {
        if (!Prefs.bool(this, i.type.pref)) { items.remove(i.key); render(); return@post }
        i.seq = ++seq
        if (!items.containsKey(i.key) && i.type.prio >= 8) {
            if (pinnedKey != null) restoreKey = pinnedKey
            pinnedKey = null
        }
        items[i.key] = i
        hides.remove(i.key)?.let { h.removeCallbacks(it) }
        if (i.autoHideMs > 0) {
            val r = Runnable { items.remove(i.key); hides.remove(i.key); render() }
            hides[i.key] = r; h.postDelayed(r, i.autoHideMs)
        }
        render()
    }

    fun remove(key: String) = h.post {
        items.remove(key)
        hides.remove(key)?.let { h.removeCallbacks(it) }
        render()
    }

    /** Użytkownik rozwinął wyspę – nie chowaj jej od razu. */
    fun touched(key: String) = h.post {
        val i = items[key] ?: return@post
        if (i.autoHideMs > 0) {
            hides.remove(key)?.let { h.removeCallbacks(it) }
            val r = Runnable { items.remove(key); hides.remove(key); render() }
            hides[key] = r; h.postDelayed(r, 12000)
        }
    }

    fun rebuild() { h.post { detach(false); render() } }

    private val previewR = Runnable {
        detach(false)
        post(Item("demo", Type.DEMO, "Podgląd Dynamic Island", "Pixel 10a • dostosuj rozmiar",
            glyph = R.drawable.ic_music, ind = Ind.EQ,
            acts = listOf(Act(icon = R.drawable.ic_prev) {}, Act(icon = R.drawable.ic_pause, bg = Color.WHITE) {}, Act(icon = R.drawable.ic_next) {}),
            autoHideMs = 4000, autoExpand = true))
    }
    fun preview() { h.removeCallbacks(previewR); h.postDelayed(previewR, 150) }

    fun setTorch(on: Boolean) {
        try { getSystemService(CameraManager::class.java).setTorchMode(torchId ?: return, on) } catch (_: Exception) {}
    }

    /** Otwiera PendingIntent z aplikacji systemowej / zewnętrznej (muzyka, powiadomienia, rozmowy). */
    fun fire(pi: PendingIntent) {
        try {
            val o = ActivityOptions.makeBasic()
            o.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            pi.send(this, 0, null, null, null, null, o.toBundle())
        } catch (_: Exception) {
            try { pi.send() } catch (_: Exception) {}
        }
    }

    // ---------- rysowanie ----------
    private fun render() {
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val order = ordered()
        if (pinnedKey != null && items[pinnedKey!!] == null) pinnedKey = null
        if (pinnedKey == null && restoreKey != null) {
            if (items[restoreKey!!] == null) restoreKey = null
            else if (order.none { it.type.prio >= 8 }) { pinnedKey = restoreKey; restoreKey = null }
        }
        val top = pinnedKey?.let { items[it] } ?: order.firstOrNull()
        if (top == null || (land && Prefs.bool(this, "hl"))) { detach(true); return }
        if (view == null) attach()
        val v = view ?: return
        val changed = top.key != shownKey
        shownKey = top.key
        v.setDots(order.size, order.indexOfFirst { it.key == top.key }.coerceAtLeast(0))
        v.show(top, changed, changed && (top.autoExpand || forceExpand))
        forceExpand = false
    }

    private fun ordered(): List<Item> =
        items.values.sortedWith(compareByDescending<Item> { it.type.prio }.thenByDescending { it.seq })

    /** Przełączanie zadań gestem przesunięcia: dir = +1 następne, -1 poprzednie. */
    fun cycle(dir: Int) = h.post {
        val order = ordered()
        if (order.size < 2) return@post
        val cur = order.indexOfFirst { it.key == shownKey }.coerceAtLeast(0)
        val next = order[(cur + dir + order.size) % order.size]
        pinnedKey = next.key
        forceExpand = view?.isOpen() == true
        render()
    }

    private fun computeY(): Int {
        val ch = Prefs.px(this, "ch")
        val off = (Prefs.int(this, "oy") * resources.displayMetrics.density).toInt()
        val r = try { wm.currentWindowMetrics.windowInsets.displayCutout?.boundingRectTop } catch (_: Exception) { null }
        val base = if (r != null && !r.isEmpty) (r.top + r.bottom) / 2 - ch / 2
        else (12 * resources.displayMetrics.density).toInt()
        return base + off
    }

    private fun attach() {
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = computeY()
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "PixelIsland"
        }
        val v = IslandView(this, { touched(it) }) { cycle(it) }
        try { wm.addView(v, lp); view = v; v.appear() } catch (_: Exception) {}
    }

    private fun detach(animated: Boolean) {
        val v = view ?: return
        view = null; shownKey = null
        if (animated) v.leave { try { wm.removeView(v) } catch (_: Exception) {} }
        else try { wm.removeViewImmediate(v) } catch (_: Exception) {}
    }
}
