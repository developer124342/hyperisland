package pl.pixel.island

import android.content.Context
import android.graphics.drawable.Drawable
import android.media.session.MediaController

enum class Ind { NONE, EQ, REC, TORCH, BATT, CALL, TIMER, DOT }

enum class Type(val prio: Int, val pref: String) {
    CALL(10, "m_call"), NOTIF(8, "m_notif"), CHARGE(8, "m_charge"), REC(7, "m_rec"),
    TORCH(6, "m_torch"), TIMER(5, "m_timer"), MEDIA(4, "m_music"), NAV(3, "m_nav"), DEMO(11, "m_demo")
}

class Act(val label: String = "", val icon: Int = 0, val bg: Int = 0, val run: () -> Unit)

class Item(
    val key: String,
    val type: Type,
    val title: String,
    val sub: String = "",
    val icon: Drawable? = null,
    val glyph: Int = 0,
    val glyphTint: Int = -1,
    val ind: Ind = Ind.NONE,
    val indText: String = "",
    val since: Long = 0L,
    val acts: List<Act> = emptyList(),
    val onTap: (() -> Unit)? = null,
    val autoHideMs: Long = 0L,
    val autoExpand: Boolean = false,
    val media: MediaController? = null
) { var seq = 0L }

object Prefs {
    private val DEF = mapOf(
        "cw" to 150, "ch" to 34, "ew" to 310, "rad" to 30,
        "oy" to 0, "scale" to 100, "anim" to 350
    )
    fun def(k: String) = DEF[k] ?: 0
    private fun sp(c: Context) = c.getSharedPreferences("island", Context.MODE_PRIVATE)
    fun int(c: Context, k: String) = sp(c).getInt(k, def(k))
    fun set(c: Context, k: String, v: Int) = sp(c).edit().putInt(k, v).apply()
    private val DEFB = mapOf("hyper" to false)
    fun bool(c: Context, k: String) = sp(c).getBoolean(k, DEFB[k] ?: true)
    fun setBool(c: Context, k: String, v: Boolean) = sp(c).edit().putBoolean(k, v).apply()
    /** wartość w dp przeskalowana suwakiem "skala", zamieniona na piksele */
    fun px(c: Context, k: String): Int =
        (int(c, k) * int(c, "scale") / 100f * c.resources.displayMetrics.density).toInt()
}
