package com.example.starkphone

import android.content.Context
import android.graphics.*
import android.inputmethodservice.InputMethodService
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import java.net.URL
import java.net.URLEncoder

// ローマ字→かな
private val ROMAJI: Map<String, String> = (
    "a あ|i い|u う|e え|o お|ka か|ki き|ku く|ke け|ko こ|ga が|gi ぎ|gu ぐ|ge げ|go ご|" +
    "sa さ|si し|shi し|su す|se せ|so そ|za ざ|zi じ|ji じ|zu ず|ze ぜ|zo ぞ|" +
    "ta た|ti ち|chi ち|tu つ|tsu つ|te て|to と|da だ|di ぢ|du づ|de で|do ど|" +
    "na な|ni に|nu ぬ|ne ね|no の|ha は|hi ひ|hu ふ|fu ふ|he へ|ho ほ|" +
    "ba ば|bi び|bu ぶ|be べ|bo ぼ|pa ぱ|pi ぴ|pu ぷ|pe ぺ|po ぽ|" +
    "ma ま|mi み|mu む|me め|mo も|ya や|yu ゆ|yo よ|ra ら|ri り|ru る|re れ|ro ろ|wa わ|wo を|nn ん|" +
    "kya きゃ|kyu きゅ|kyo きょ|gya ぎゃ|gyu ぎゅ|gyo ぎょ|sha しゃ|shu しゅ|sho しょ|she しぇ|" +
    "sya しゃ|syu しゅ|syo しょ|ja じゃ|ju じゅ|jo じょ|jya じゃ|jyu じゅ|jyo じょ|zya じゃ|zyu じゅ|zyo じょ|" +
    "cha ちゃ|chu ちゅ|cho ちょ|che ちぇ|cya ちゃ|cyu ちゅ|cyo ちょ|tya ちゃ|tyu ちゅ|tyo ちょ|" +
    "nya にゃ|nyu にゅ|nyo にょ|hya ひゃ|hyu ひゅ|hyo ひょ|bya びゃ|byu びゅ|byo びょ|" +
    "pya ぴゃ|pyu ぴゅ|pyo ぴょ|mya みゃ|myu みゅ|myo みょ|rya りゃ|ryu りゅ|ryo りょ|" +
    "fa ふぁ|fi ふぃ|fe ふぇ|fo ふぉ|xa ぁ|xi ぃ|xu ぅ|xe ぇ|xo ぉ|la ぁ|li ぃ|lu ぅ|le ぇ|lo ぉ|" +
    "xya ゃ|xyu ゅ|xyo ょ|xtu っ|ltu っ|xtsu っ|thi てぃ|dhi でぃ|wi うぃ|we うぇ|- ー|, 、|. 。"
    ).split("|").associate { val (k, v) = it.split(" "); k to v }

private fun isVowel(c: Char) = c in "aiueo"

/** returns kana + 未確定のローマ字 */
fun convertRomaji(s: String): Pair<String, String> {
    val out = StringBuilder()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (i + 1 < s.length && c == s[i + 1] && c.isLetter() && !isVowel(c) && c != 'n') { out.append('っ'); i++; continue }
        var hit = false
        for (len in 4 downTo 1) {
            if (i + len <= s.length) {
                val k = ROMAJI[s.substring(i, i + len)]
                if (k != null) { out.append(k); i += len; hit = true; break }
            }
        }
        if (hit) continue
        if (c == 'n' && i + 1 < s.length && s[i + 1] != 'y') { out.append('ん'); i++; continue }
        return Pair(out.toString(), s.substring(i))
    }
    return Pair(out.toString(), "")
}

class StarkKeyView(ctx: Context, private val onKey: (String) -> Unit) : View(ctx) {
    private class Key(val id: String, val label: String, val w: Float = 1f) {
        var cx = 0f; var cy = 0f; var kw = 0f; var kh = 0f; var rot = 0f      // 通常キー(回転した長方形)
        var fan = false; var r0 = 0f; var r1 = 0f; var a0 = 0f; var a1 = 0f   // 扇形キー
    }
    private val rows = listOf(
        "1234567890".map { Key(it.toString(), it.toString()) },
        "qwertyuiop".map { Key(it.toString(), it.uppercase()) },
        "asdfghjkl".map { Key(it.toString(), it.uppercase()) },
        "zxcvbnm".map { Key(it.toString(), it.uppercase()) }
    )
    // 外側→内側
    private val bands = listOf(
        listOf(Key("MODE", "あ"), Key("SPACE", "SPACE", 2.4f), Key("-", "ー"), Key(",", "、"), Key(".", "。")),
        listOf(Key("DEL", "⌫", 1.2f), Key("SHIFT", "⇧"), Key("@", "@"), Key("?", "?")),
        listOf(Key("ENTER", "⏎", 1.6f), Key("!", "!"), Key("'", "'"))
    )
    var jp = true
        set(v) { field = v; invalidate() }
    var shift = false
        set(v) { field = v; invalidate() }
    private var pressed: Key? = null
    private var cX = 0f; private var cY = 0f
    private val bg = Paint().apply { color = 0xFF2F3FA6.toInt() }
    private val grid = Paint().apply { color = 0x26FFFFFF; strokeWidth = 1f }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2.2f; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF; style = Paint.Style.FILL }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
    private val brand = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAAFFFFFF.toInt(); textAlign = Paint.Align.RIGHT }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val cap = resources.displayMetrics.heightPixels * 0.5f
        setMeasuredDimension(width, minOf(width * 0.72f, cap).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val pad = w * 0.015f
        // --- 扇(左) ---
        cX = w * 0.30f; cY = h - pad
        val rMax = minOf(cX - pad, cY - pad - h * 0.05f)
        val rMin = rMax * 0.26f
        val t = (rMax - rMin) / 3f
        bands.forEachIndexed { i, band ->
            val total = band.sumOf { it.w.toDouble() }.toFloat()
            var cum = 0f
            for (k in band) {
                k.fan = true
                k.r1 = rMax - i * t - 1.5f; k.r0 = rMax - (i + 1) * t + 1.5f
                k.a0 = 180f + 90f * cum / total; k.a1 = 180f + 90f * (cum + k.w) / total
                cum += k.w
            }
        }
        // --- 文字キー(右、少し湾曲) ---
        val x0 = w * 0.315f; val x1 = w - pad
        val u = (x1 - x0) / 10f
        val top = pad + h * 0.04f
        val rh = (h - top - pad) / 4f
        rows.forEachIndexed { r, row ->
            val off = (10 - row.size) / 2f
            row.forEachIndexed { j, k ->
                val cx = x0 + (off + j + 0.5f) * u
                val tt = (cx - (x0 + x1) / 2f) / ((x1 - x0) / 2f)
                k.cx = cx; k.kw = u; k.kh = rh * 0.9f
                k.cy = top + rh * (r + 0.5f) + tt * tt * rh * 0.3f
                k.rot = tt * 9f
            }
        }
        brand.textSize = h * 0.04f
    }

    private fun label(k: Key): String = when (k.id) {
        "MODE" -> if (jp) "あ" else "A"
        "-" -> if (jp) "ー" else "-"
        "," -> if (jp) "、" else ","
        "." -> if (jp) "。" else "."
        else -> if (shift && !jp && k.id.length == 1 && k.id[0].isLetter()) k.label.uppercase() else if (!shift && k.id.length == 1 && k.id[0].isLetter()) k.label.lowercase() else k.label
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.drawRect(0f, 0f, w, h, bg)
        var g = 0f
        while (g < w) { c.drawLine(g, 0f, g, h, grid); g += w / 24f }
        g = 0f
        while (g < h) { c.drawLine(0f, g, w, g, grid); g += w / 24f }
        // 寸法線っぽい装飾
        c.drawLine(w * 0.02f, h * 0.025f, w * 0.98f, h * 0.025f, grid)
        // 扇
        for (band in bands) for (k in band) {
            val p = Path()
            val gap = 1.6f; val sw = (k.a1 - k.a0) - 2 * gap
            p.arcTo(RectF(cX - k.r1, cY - k.r1, cX + k.r1, cY + k.r1), k.a0 + gap, sw)
            p.arcTo(RectF(cX - k.r0, cY - k.r0, cX + k.r0, cY + k.r0), k.a1 - gap, -sw)
            p.close()
            if (k === pressed || (k.id == "SHIFT" && shift)) c.drawPath(p, fill)
            c.drawPath(p, line)
            val mid = (k.a0 + k.a1) / 2f; val rm = (k.r0 + k.r1) / 2f
            val tx = cX + rm * Math.cos(Math.toRadians(mid.toDouble())).toFloat()
            val ty = cY + rm * Math.sin(Math.toRadians(mid.toDouble())).toFloat()
            val l = label(k)
            txt.textSize = (k.r1 - k.r0) * if (l.length > 1) 0.32f else 0.5f
            c.save(); c.translate(tx, ty); c.rotate(mid + 90f)
            c.drawText(l, 0f, txt.textSize * 0.35f, txt); c.restore()
        }
        // 文字キー
        for (row in rows) for (k in row) {
            c.save(); c.translate(k.cx, k.cy); c.rotate(k.rot)
            val rect = RectF(-k.kw / 2 + 2, -k.kh / 2, k.kw / 2 - 2, k.kh / 2)
            if (k === pressed) c.drawRoundRect(rect, 8f, 8f, fill)
            c.drawRoundRect(rect, 8f, 8f, line)
            txt.textSize = k.kw * 0.62f
            c.drawText(label(k), 0f, txt.textSize * 0.35f, txt)
            c.restore()
        }
        c.drawText("STARK INDUSTRIES  PROTOTYPE", w - 10f, h - 6f, brand)
    }

    private fun hit(x: Float, y: Float): Key? {
        val dx = x - cX; val dy = y - cY
        val r = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        var ang = Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())).toFloat(); if (ang < 0) ang += 360f
        for (band in bands) for (k in band) if (r in k.r0..k.r1 && ang in k.a0..k.a1) return k
        for (row in rows) for (k in row) {
            val a = Math.toRadians(-k.rot.toDouble())
            val px = x - k.cx; val py = y - k.cy
            val lx = (px * Math.cos(a) - py * Math.sin(a)).toFloat()
            val ly = (px * Math.sin(a) + py * Math.cos(a)).toFloat()
            if (Math.abs(lx) <= k.kw / 2 && Math.abs(ly) <= k.kh / 2 + 4) return k
        }
        return null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> { pressed = hit(e.x, e.y); invalidate() }
            MotionEvent.ACTION_UP -> { hit(e.x, e.y)?.let { onKey(it.id) }; pressed = null; invalidate() }
            MotionEvent.ACTION_CANCEL -> { pressed = null; invalidate() }
        }
        return true
    }
}

class StarkKeyboardService : InputMethodService() {
    private var roman = ""
    private var jp = true
    private var shift = false
    private var gen = 0
    private lateinit var keys: StarkKeyView
    private lateinit var candBox: LinearLayout

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF2F3FA6.toInt()) }
        candBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(8, 4, 8, 4) }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; setBackgroundColor(0xFF232F85.toInt()); addView(candBox)
        }, LinearLayout.LayoutParams(-1, (resources.displayMetrics.density * 42).toInt()))
        keys = StarkKeyView(this) { onKey(it) }
        root.addView(keys, LinearLayout.LayoutParams(-1, -2))
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        roman = ""; showCands(emptyList())
    }

    private fun commit(t: String) {
        currentInputConnection?.commitText(t, 1)
        roman = ""; gen++; showCands(emptyList())
    }

    private fun composingText(): String { val (k, t) = convertRomaji(roman); return k + t }

    private fun render() {
        val ic = currentInputConnection ?: return
        if (roman.isEmpty()) { ic.finishComposingText(); showCands(emptyList()); return }
        val text = composingText()
        ic.setComposingText(text, 1)
        val kana = convertRomaji(roman).first
        showCands(listOf(text))
        if (kana.isNotEmpty()) fetchCands(kana, text)
    }

    private fun fetchCands(kana: String, text: String) {
        val my = ++gen
        Thread {
            try {
                val u = "https://www.google.com/transliterate?langpair=ja-Hira%7Cja&text=" + URLEncoder.encode(kana, "UTF-8")
                val arr = JSONArray(URL(u).readText())
                val top = StringBuilder()
                for (i in 0 until arr.length()) top.append(arr.getJSONArray(i).getJSONArray(1).getString(0))
                val list = LinkedHashSet<String>()
                list.add(top.toString())
                val first = arr.getJSONArray(0).getJSONArray(1)
                for (i in 0 until first.length()) if (arr.length() == 1) list.add(first.getString(i))
                list.add(text)
                if (my == gen) keys.post { showCands(list.toList()) }
            } catch (e: Throwable) { /* オフライン時はかなのみ */ }
        }.start()
    }

    private fun showCands(list: List<String>) {
        candBox.removeAllViews()
        list.forEach { c ->
            candBox.addView(TextView(this).apply {
                text = c; textSize = 17f; setTextColor(Color.WHITE); setPadding(22, 6, 22, 6)
                setOnClickListener { commit(c) }
            })
        }
    }

    private fun onKey(id: String) {
        val ic = currentInputConnection ?: return
        when (id) {
            "DEL" -> if (roman.isNotEmpty()) { roman = roman.dropLast(1); render() }
                     else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            "SPACE" -> if (roman.isNotEmpty()) {
                            val first = (0 until candBox.childCount).firstNotNullOfOrNull { (candBox.getChildAt(it) as? TextView)?.text?.toString() }
                            commit(first ?: composingText())
                        } else commit(" ")
            "ENTER" -> if (roman.isNotEmpty()) commit(composingText()) else sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            "MODE" -> { if (roman.isNotEmpty()) commit(composingText()); jp = !jp; keys.jp = jp }
            "SHIFT" -> { shift = !shift; keys.shift = shift }
            else -> {
                val isLetter = id.length == 1 && id[0] in 'a'..'z'
                if (jp && (isLetter || id == "-" || id == "," || id == ".")) { roman += id; render() }
                else {
                    val ch = if (shift && isLetter) id.uppercase() else id
                    if (shift) { shift = false; keys.shift = false }
                    commit((if (roman.isNotEmpty()) composingText() else "") + ch)
                }
            }
        }
    }
}
