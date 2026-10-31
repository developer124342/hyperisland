package pl.pixel.island

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.SystemClock
import android.text.TextUtils
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

class IslandView(
    c: Context,
    private val onTouch: (String) -> Unit,
    private val onSwipe: (Int) -> Unit
) : FrameLayout(c) {
    private val hyper = Prefs.bool(c, "hyper")
    private val sc = Prefs.int(c, "scale") / 100f
    private val cw = Prefs.px(c, "cw")
    private val ch = Prefs.px(c, "ch")
    private val ew = Prefs.px(c, "ew")
    private val animMs = Prefs.int(c, "anim").toLong()
    private fun d(v: Int) = (v * sc * resources.displayMetrics.density).toInt()
    private val radExp = Prefs.px(c, "rad").toFloat() + if (hyper) d(8) else 0

    private var item: Item? = null
    private var expanded = false
    private var anim: ValueAnimator? = null
    private var tintColor = 0xFF22222A.toInt()

    private val bg = GradientDrawable().apply {
        orientation = GradientDrawable.Orientation.TOP_BOTTOM
        colors = intArrayOf(Color.BLACK, Color.BLACK)
        cornerRadius = ch / 2f
    }
    private val card = LinearLayout(c)
    private val leftBox = FrameLayout(c)
    private val img = ImageView(c)
    private val ind = IndView(c)
    private val body = LinearLayout(c)
    private val head = LinearLayout(c)
    private val bigArt = ImageView(c)
    private val textCol = LinearLayout(c)
    private val title = TextView(c)
    private val sub = TextView(c)
    private val progress = ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal)
    private val timeRow = LinearLayout(c)
    private val tCur = TextView(c)
    private val tTot = TextView(c)
    private val actions = LinearLayout(c)
    private val col = LinearLayout(c)
    private val dots = DotsView(c)
    private var downX = 0f
    private var downY = 0f
    private var swiped = false

    init {
        card.orientation = LinearLayout.VERTICAL
        card.background = bg
        card.clipToOutline = true

        val top = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(10), 0, d(10), 0)
        }
        img.layoutParams = FrameLayout.LayoutParams(d(22), d(22), Gravity.START or Gravity.CENTER_VERTICAL)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        img.clipToOutline = true
        img.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, v.width * 0.28f) }
        }
        leftBox.addView(img)
        top.addView(leftBox, LinearLayout.LayoutParams(d(46), ViewGroup.LayoutParams.MATCH_PARENT))
        top.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))   // miejsce na otwór aparatu
        top.addView(ind, LinearLayout.LayoutParams(d(46), ViewGroup.LayoutParams.MATCH_PARENT))
        card.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ch))

        body.orientation = LinearLayout.VERTICAL
        body.gravity = if (hyper) Gravity.START else Gravity.CENTER_HORIZONTAL
        if (hyper) body.setPadding(d(18), d(4), d(18), d(16)) else body.setPadding(d(16), 0, d(16), d(14))
        body.visibility = GONE

        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        bigArt.clipToOutline = true
        bigArt.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, d(14).toFloat()) }
        }
        if (hyper) head.addView(bigArt, LinearLayout.LayoutParams(d(52), d(52)).apply { marginEnd = d(12) })
        textCol.orientation = LinearLayout.VERTICAL
        title.apply { setTextColor(Color.WHITE); textSize = (if (hyper) 17f else 16f) * sc
            setTypeface(typeface, Typeface.BOLD)
            gravity = if (hyper) Gravity.START else Gravity.CENTER
            setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        sub.apply { setTextColor(0xFFB0B0B0.toInt()); textSize = 13f * sc
            gravity = if (hyper) Gravity.START else Gravity.CENTER
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        textCol.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        textCol.addView(sub, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        head.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        body.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        progress.max = 1000
        progress.progressTintList = ColorStateList.valueOf(Color.WHITE)
        progress.progressBackgroundTintList = ColorStateList.valueOf(0x44FFFFFF)
        body.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, d(4))
            .apply { topMargin = d(if (hyper) 14 else 10) })
        timeRow.orientation = LinearLayout.HORIZONTAL
        for (t in listOf(tCur, tTot)) { t.setTextColor(0xFF9A9A9A.toInt()); t.textSize = 11f * sc }
        tCur.text = "0:00"; tTot.text = "0:00"
        timeRow.addView(tCur, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        timeRow.addView(tTot)
        body.addView(timeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = d(4) })

        actions.orientation = LinearLayout.HORIZONTAL
        actions.gravity = Gravity.CENTER
        body.addView(actions, LinearLayout.LayoutParams(
            if (hyper) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = d(12) })
        card.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.orientation = LinearLayout.VERTICAL
        col.addView(card, LinearLayout.LayoutParams(cw, ch).apply { gravity = Gravity.CENTER_HORIZONTAL })
        col.addView(dots, LinearLayout.LayoutParams(d(44), d(10)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = d(4)
        })
        dots.visibility = GONE
        addView(col, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))

        card.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; swiped = false }
                MotionEvent.ACTION_UP -> {
                    val dx = ev.rawX - downX; val dy = ev.rawY - downY
                    if (abs(dx) > d(36) && abs(dx) > abs(dy) * 1.5f) { swiped = true; onSwipe(if (dx < 0) 1 else -1) }
                }
            }
            false
        }
        card.setOnClickListener {
            if (swiped) { swiped = false; return@setOnClickListener }
            if (!expanded) { item?.let { onTouch(it.key) }; setExpanded(true, true) }
            else { item?.onTap?.invoke(); setExpanded(false, true) }
        }
        card.setOnLongClickListener { item?.onTap?.invoke(); true }
    }

    fun isOpen() = expanded

    fun setDots(n: Int, idx: Int) {
        dots.set(n, idx)
        dots.visibility = if (n > 1) VISIBLE else GONE
    }

    fun show(i: Item, keyChanged: Boolean, expand: Boolean) {
        val had = item != null
        item = i
        bind(i)
        if (keyChanged) {
            setExpanded(expand, isAttachedToWindow)
            if (had && isAttachedToWindow) { card.alpha = 0.25f; card.animate().alpha(1f).setDuration(200).start() }
            if (!expand && i.type == Type.NOTIF) nudge()
        } else if (expanded) applySize(ew, expandedHeight(), radExp)
    }

    private fun copy(dr: Drawable?): Drawable? = dr?.constantState?.newDrawable()?.mutate() ?: dr

    /** Średni, „ciepły” kolor ikony/okładki – używany do delikatnego przyciemnionego tła rozwiniętej wyspy. */
    private fun accent(dr: Drawable?, fallback: Int): Int {
        val cp = copy(dr) ?: return fallback
        return try {
            val bm = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            cp.setBounds(0, 0, 8, 8); cp.draw(Canvas(bm))
            var r = 0L; var g = 0L; var b = 0L; var n = 0
            for (x in 0 until 8) for (y in 0 until 8) {
                val p = bm.getPixel(x, y)
                if (Color.alpha(p) < 128) continue
                val rr = Color.red(p); val gg = Color.green(p); val bb = Color.blue(p)
                if (max(rr, max(gg, bb)) < 30 || minOf(rr, gg, bb) > 225) continue
                r += rr; g += gg; b += bb; n++
            }
            if (n == 0) fallback else Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
        } catch (_: Exception) { fallback }
    }

    private fun blend(a: Int, b: Int, f: Float): Int {
        val k = f.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt())
    }

    private fun applyTint(k: Float) {
        if (!hyper) return
        bg.colors = intArrayOf(Color.BLACK, blend(Color.BLACK, tintColor, k))
        bg.setStroke(max(1, d(1)), Color.argb((46 * k.coerceIn(0f, 1f)).toInt(), 255, 255, 255))
        leftBox.alpha = 1f - k.coerceIn(0f, 1f)
    }

    private fun bind(i: Item) {
        if (i.icon != null) {
            img.setImageDrawable(i.icon); img.clearColorFilter()
            img.scaleType = ImageView.ScaleType.CENTER_CROP; img.setPadding(0, 0, 0, 0)
        } else if (i.glyph != 0) {
            img.setImageResource(i.glyph); img.setColorFilter(i.glyphTint)
            img.scaleType = ImageView.ScaleType.FIT_CENTER; img.setPadding(d(1), d(1), d(1), d(1))
        } else img.setImageDrawable(null)

        if (hyper) {
            val fb = if (i.glyphTint != -1) blend(Color.BLACK, i.glyphTint, 1f) else 0xFF3A3A48.toInt()
            val ac = accent(i.icon, fb)
            tintColor = Color.rgb((Color.red(ac) * 0.42f).toInt(), (Color.green(ac) * 0.42f).toInt(), (Color.blue(ac) * 0.42f).toInt())
            if (i.icon != null) {
                bigArt.setImageDrawable(copy(i.icon)); bigArt.clearColorFilter()
                bigArt.scaleType = ImageView.ScaleType.CENTER_CROP; bigArt.setPadding(0, 0, 0, 0)
                bigArt.background = null
            } else if (i.glyph != 0) {
                bigArt.setImageResource(i.glyph); bigArt.setColorFilter(i.glyphTint)
                bigArt.scaleType = ImageView.ScaleType.FIT_CENTER; bigArt.setPadding(d(13), d(13), d(13), d(13))
                bigArt.background = GradientDrawable().apply { setColor(0x22FFFFFF); cornerRadius = d(14).toFloat() }
            } else { bigArt.setImageDrawable(null); bigArt.background = null }
            if (expanded) applyTint(1f)
        }

        ind.set(i.ind, i.indText, i.since)
        title.text = i.title
        sub.text = i.sub
        sub.visibility = if (i.sub.isBlank()) GONE else VISIBLE
        progress.visibility = if (i.media != null) VISIBLE else GONE
        timeRow.visibility = if (hyper && i.media != null) VISIBLE else GONE
        actions.removeAllViews()
        actions.visibility = if (i.acts.isEmpty()) GONE else VISIBLE
        actions.gravity = if (hyper && i.media == null) Gravity.START else Gravity.CENTER
        for (a in i.acts) {
            val click = View.OnClickListener {
                a.run()
                if (item?.type != Type.MEDIA) setExpanded(false, true)
            }
            val v: View
            val lp: LinearLayout.LayoutParams
            if (a.icon != 0 && a.label.isBlank()) {
                val round = a.bg != 0
                val pad = d(if (round) (if (hyper) 15 else 12) else (if (hyper) 12 else 10))
                v = ImageView(context).apply {
                    setImageResource(a.icon)
                    setColorFilter(if (a.bg == Color.WHITE) Color.BLACK else Color.WHITE)
                    setPadding(pad, pad, pad, pad)
                    if (round) background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(a.bg) }
                    setOnClickListener(click)
                }
                val sz = d(if (hyper && round) 58 else if (hyper) 50 else 48)
                lp = LinearLayout.LayoutParams(sz, sz)
            } else {
                v = TextView(context).apply {
                    text = a.label; setTextColor(Color.WHITE); textSize = 15f * sc
                    setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
                    setPadding(d(20), d(9), d(20), d(9))
                    background = GradientDrawable().apply {
                        setColor(if (a.bg != 0) a.bg else 0x33FFFFFF); cornerRadius = d(30).toFloat()
                    }
                    setOnClickListener(click)
                }
                lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val m = d(if (hyper && i.media != null) 14 else 8)
            lp.marginStart = if (hyper && i.media == null) 0 else m
            lp.marginEnd = m
            actions.addView(v, lp)
        }
    }

    /** Krótkie „puknięcie” szerokości zwiniętej wyspy przy nowym powiadomieniu. */
    private fun nudge() {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420
            addUpdateListener {
                val f = sin(it.animatedFraction * PI).toFloat()
                if (!expanded) applySize((cw * (1f + 0.2f * f)).toInt(), ch, ch / 2f)
            }
            start()
        }
    }

    private fun expandedHeight(): Int {
        body.measure(MeasureSpec.makeMeasureSpec(ew, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        return ch + body.measuredHeight
    }

    private fun applySize(w: Int, h: Int, r: Float) {
        val lp = card.layoutParams
        lp.width = w; lp.height = h
        card.layoutParams = lp
        bg.cornerRadius = r.coerceAtLeast(0f)
        card.invalidateOutline()
    }

    private fun setExpanded(e: Boolean, animate: Boolean) {
        anim?.cancel()
        expanded = e
        val fw = card.layoutParams.width; val fh = card.layoutParams.height
        val fr = bg.cornerRadius
        val tw = if (e) ew else cw
        val th = if (e) expandedHeight() else ch
        val tr = if (e) radExp else ch / 2f
        if (e) body.visibility = VISIBLE
        if (!animate) {
            applySize(tw, th, tr); body.alpha = 1f; body.visibility = if (e) VISIBLE else GONE
            applyTint(if (e) 1f else 0f); return
        }
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = animMs
            interpolator = if (e) OvershootInterpolator(if (hyper) 1.5f else 0.9f) else DecelerateInterpolator()
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                applySize((fw + (tw - fw) * f).toInt(), (fh + (th - fh) * f).toInt(), fr + (tr - fr) * f)
                val t = a.animatedFraction.coerceIn(0f, 1f)
                body.alpha = if (e) t else 1f - t
                applyTint(if (e) t else 1f - t)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(an: Animator) {
                    if (!e) body.visibility = GONE
                    body.alpha = 1f
                    applyTint(if (e) 1f else 0f)
                }
            })
            start()
        }
    }

    fun appear() {
        alpha = 0f; scaleX = 0.6f; scaleY = 0.6f
        animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(animMs / 2 + 50).start()
    }

    fun leave(done: () -> Unit) {
        animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(180).withEndAction(done).start()
    }

    private fun fmtTime(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    private val tick = object : Runnable {
        override fun run() {
            val m = item?.media
            if (expanded && m != null) {
                val st = m.playbackState
                val dur = m.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                if (st != null && dur > 0) {
                    var pos = st.position
                    if (st.state == PlaybackState.STATE_PLAYING)
                        pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
                    progress.progress = (pos * 1000 / dur).toInt().coerceIn(0, 1000)
                    if (hyper) { tCur.text = fmtTime(pos); tTot.text = fmtTime(dur) }
                }
            }
            postDelayed(this, 500)
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(tick) }
    override fun onDetachedFromWindow() { removeCallbacks(tick); anim?.cancel(); super.onDetachedFromWindow() }
}

/** Kropki pod wyspą – pokazują ile jest aktywnych zadań i które jest wyświetlane. */
class DotsView(c: Context) : View(c) {
    private var n = 0
    private var idx = 0
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    fun set(count: Int, index: Int) { n = count.coerceAtMost(6); idx = index; invalidate() }
    override fun onDraw(cv: Canvas) {
        if (n < 2) return
        val h = height.toFloat(); val r = h * 0.24f; val gap = h * 0.9f
        val total = n * 2 * r + (n - 1) * gap
        var x = (width - total) / 2f + r
        for (k in 0 until n) {
            p.color = if (k == idx) Color.WHITE else 0x66FFFFFF
            cv.drawCircle(x, h / 2f, if (k == idx) r * 1.15f else r, p)
            x += 2 * r + gap
        }
    }
}

/** Animowany wskaźnik po prawej stronie: equalizer, pulsująca kropka, latarka, timer, bateria. */
class IndView(c: Context) : View(c) {
    private var mode = Ind.NONE
    private var text = ""
    private var since = 0L
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()
    private val cols = intArrayOf(-1, -1, -1, -1)

    fun set(m: Ind, t: String, s: Long) { mode = m; text = t; since = s; invalidate() }

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private fun drawText(cv: Canvas, t: String, color: Int, rightX: Float): Float {
        p.style = Paint.Style.FILL; p.color = color; p.textSize = height * 0.46f
        p.typeface = Typeface.DEFAULT_BOLD; p.textAlign = Paint.Align.RIGHT
        while (p.measureText(t) > width - 2 && p.textSize > 8f) p.textSize -= 1f
        val fm = p.fontMetrics
        cv.drawText(t, rightX, height / 2f - (fm.ascent + fm.descent) / 2f, p)
        return p.measureText(t)
    }

    override fun onDraw(cv: Canvas) {
        if (mode == Ind.NONE) return
        val w = width.toFloat(); val h = height.toFloat(); val cy = h / 2
        val t = SystemClock.uptimeMillis() / 1000f
        val pulse = 0.55f + 0.45f * (0.5f + 0.5f * sin(t * 2f * PI.toFloat() * 1.1f))
        when (mode) {
            Ind.EQ -> {
                val n = 4; val bw = w * 0.12f; val gap = w * 0.07f
                var x = w - (n * bw + (n - 1) * gap)
                p.style = Paint.Style.FILL
                for (k in 0 until n) {
                    p.color = cols[k]
                    val a = 0.3f + 0.7f * abs(sin(t * (3.1f + k * 1.7f) + k))
                    val bh = h * 0.62f * a
                    cv.drawRoundRect(x, cy - bh / 2, x + bw, cy + bh / 2, bw / 2, bw / 2, p)
                    x += bw + gap
                }
            }
            Ind.TORCH -> {
                val cx = w - h * 0.4f
                p.style = Paint.Style.FILL
                p.color = Color.argb((90 * pulse).toInt(), 255, 213, 79)
                cv.drawCircle(cx, cy, h * 0.46f * (0.8f + 0.2f * pulse), p)
                p.color = 0xFFFFD54F.toInt()
                cv.drawCircle(cx, cy, h * 0.2f, p)
            }
            Ind.REC, Ind.CALL -> {
                val tx = if (since > 0) fmt(System.currentTimeMillis() - since) else ""
                val tw = if (tx.isNotEmpty()) drawText(cv, tx, Color.WHITE, w) else 0f
                val col = if (mode == Ind.REC) Color.rgb(255, 59, 48) else Color.rgb(52, 199, 89)
                p.style = Paint.Style.FILL
                p.color = Color.argb((255 * pulse).toInt(), Color.red(col), Color.green(col), Color.blue(col))
                cv.drawCircle(w - tw - h * 0.38f, cy, h * 0.17f, p)
            }
            Ind.TIMER -> {
                if (since > 0) {
                    val now = System.currentTimeMillis()
                    drawText(cv, fmt(if (text == "down") since - now else now - since), 0xFFFF9F0A.toInt(), w)
                } else {
                    val r = h * 0.3f; val cx = w - h * 0.4f
                    p.style = Paint.Style.STROKE; p.strokeWidth = h * 0.1f
                    p.strokeCap = Paint.Cap.ROUND; p.color = 0xFFFF9F0A.toInt()
                    oval.set(cx - r, cy - r, cx + r, cy + r)
                    cv.drawArc(oval, t * 300f, 260f, false, p)
                }
            }
            Ind.BATT -> drawText(cv, text, 0xFF34C759.toInt(), w)
            Ind.DOT -> {
                val cx = w - h * 0.4f
                p.style = Paint.Style.FILL
                p.color = Color.argb((110 * pulse).toInt(), 77, 163, 255)
                cv.drawCircle(cx, cy, h * 0.28f * (0.8f + 0.3f * pulse), p)
                p.color = 0xFF4DA3FF.toInt()
                cv.drawCircle(cx, cy, h * 0.13f, p)
            }
            else -> {}
        }
        postInvalidateOnAnimation()
    }
}
