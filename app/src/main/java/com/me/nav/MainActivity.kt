package com.me.nav

import android.Manifest
import android.app.Activity
import android.graphics.Color
import android.os.*
import android.speech.tts.TextToSpeech
import android.text.*
import android.view.*
import android.widget.*
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.concurrent.thread

data class R(val pts: List<GeoPoint>, val len: Double, val sec: Double)

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    val RIGA = BoundingBox(57.05, 24.35, 56.85, 23.90)
    val modes = listOf("Быстрый" to "fastbike", "Тихий" to "trekking", "Скрытый" to "safety")
    val h = Handler(Looper.getMainLooper())
    lateinit var map: MapView; lateinit var me: MyLocationNewOverlay; lateinit var tts: TextToSpeech
    lateinit var ea: EditText; lateinit var eb: EditText; lateinit var info: TextView
    lateinit var list: ListView; lateinit var modeBtn: Button; lateinit var ride: Button
    var pa: GeoPoint? = null; var pb: GeoPoint? = null; var next = 0; var mode = 2
    var line: Polyline? = null; var ma: Marker? = null; var mb: Marker? = null
    var turns = listOf<Pair<GeoPoint, String>>(); var ti = 0; var riding = false; var warned = false
    var sug = listOf<Triple<String, String, GeoPoint>>(); var token = 0; var ignore = false; var deb: Runnable? = null

    fun Int.dp() = (this * resources.displayMetrics.density).toInt()
    fun EditText.onText(f: (String) -> Unit) = addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(s: Editable?) { f(s.toString()) }
        override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
        override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
    })
    fun put(e: EditText, t: String) { ignore = true; e.setText(t); ignore = false }
    fun say(t: String) { tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "n") }
    override fun onInit(st: Int) { tts.language = Locale("ru"); tts.setPitch(2f); tts.setSpeechRate(1.15f) }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        Configuration.getInstance().apply { userAgentValue = packageName; osmdroidBasePath = File(cacheDir, "osm"); osmdroidTileCache = File(cacheDir, "osm/tiles") }
        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
        tts = TextToSpeech(this, this)
        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true); minZoomLevel = 11.0
            setScrollableAreaLimitDouble(RIGA); controller.setZoom(13.0); controller.setCenter(GeoPoint(56.9496, 24.1052))
        }
        me = MyLocationNewOverlay(GpsMyLocationProvider(this), map).also { it.enableMyLocation(); map.overlays.add(it) }
        map.overlays.add(0, MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { tap(p); return true }
            override fun longPressHelper(p: GeoPoint) = false
        }))
        fun et(hint: String) = EditText(this).apply { this.hint = hint; setSingleLine(); setTextColor(Color.WHITE); setHintTextColor(0xFF8FA1B3.toInt()) }
        fun btn(t: String) = Button(this).apply { text = t }
        ea = et("Откуда: адрес или место"); eb = et("Куда: адрес или место")
        ea.onText { q -> if (!ignore) find(q, true) }; eb.onText { q -> if (!ignore) find(q, false) }
        info = TextView(this).apply { setTextColor(0xFFB8C4D0.toInt()); text = "Рига, велосипед. Пиши адрес или тапай по карте." }
        list = ListView(this).apply { visibility = View.GONE }
        val loc = btn("📍").apply { setOnClickListener {
            val m = me.myLocation
            if (m == null) info.text = "Жду GPS…" else { setPt(true, m); put(ea, "Моё местоположение"); map.controller.animateTo(m); route() } } }
        modeBtn = btn("Скрытый").apply { setOnClickListener { mode = (mode + 1) % 3; text = modes[mode].first; route() } }
        val go = btn("Построить").apply { setOnClickListener { list.visibility = View.GONE; route() } }
        ride = btn("Поехали").apply { setOnClickListener {
            if (turns.isEmpty()) return@setOnClickListener
            riding = !riding
            if (riding) { ti = 0; warned = false; say("Погнали, велосипедист!"); me.enableFollowLocation(); map.controller.setZoom(17.0); text = "Стоп" }
            else { me.disableFollowLocation(); text = "Поехали" } } }
        val r1 = LinearLayout(this).apply { addView(ea, LinearLayout.LayoutParams(0, -2, 1f)); addView(loc) }
        val r3 = LinearLayout(this).apply { addView(modeBtn); addView(go); addView(ride) }
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF17212C.toInt()); setPadding(12.dp(), 8.dp(), 12.dp(), 12.dp())
            addView(list, LinearLayout.LayoutParams(-1, 200.dp())); addView(r1); addView(eb); addView(info); addView(r3)
        }
        setContentView(FrameLayout(this).apply { addView(map); addView(sheet, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)) })
        h.post(object : Runnable { override fun run() { if (riding) me.myLocation?.let { follow(it) }; h.postDelayed(this, 1500) } })
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) { me.disableMyLocation(); me.enableMyLocation() }
    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { super.onPause(); map.onPause() }

    fun get(u: String) = (URL(u).openConnection() as HttpURLConnection).run {
        setRequestProperty("User-Agent", "com.me.nav"); connectTimeout = 15000; readTimeout = 40000; inputStream.bufferedReader().readText() }

    fun find(q: String, isA: Boolean) {
        deb?.let { h.removeCallbacks(it) }
        if (q.trim().length < 2) { list.visibility = View.GONE; return }
        deb = Runnable { val t = ++token; thread { try {
            val fs = JSONObject(get("https://photon.komoot.io/api/?limit=8&lat=56.9496&lon=24.1052&bbox=23.90,56.85,24.35,57.05&q=" + URLEncoder.encode(q, "UTF-8"))).getJSONArray("features")
            val out = ArrayList<Triple<String, String, GeoPoint>>()
            for (i in 0 until fs.length()) {
                val f = fs.getJSONObject(i); val p = f.getJSONObject("properties"); val c = f.getJSONObject("geometry").getJSONArray("coordinates")
                val st = listOf(p.optString("street"), p.optString("housenumber")).filter { it.isNotEmpty() }.joinToString(" ")
                val name = p.optString("name").ifEmpty { st }; if (name.isEmpty()) continue
                out.add(Triple(name, listOf(if (p.has("name")) st else "", p.optString("district"), p.optString("city")).filter { it.isNotEmpty() }.joinToString(", "), GeoPoint(c.getDouble(1), c.getDouble(0))))
            }
            runOnUiThread { if (t == token) showSug(out, isA) }
        } catch (e: Exception) { runOnUiThread { info.text = "Нет связи с поиском" } } } }
        h.postDelayed(deb!!, 300)
    }

    fun showSug(r: List<Triple<String, String, GeoPoint>>, isA: Boolean) {
        sug = r
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, r.map { it.first + if (it.second.isEmpty()) "" else "\n" + it.second })
        list.visibility = if (r.isEmpty()) View.GONE else View.VISIBLE
        list.setOnItemClickListener { _, _, i, _ ->
            val t = sug[i]; setPt(isA, t.third); put(if (isA) ea else eb, t.first)
            list.visibility = View.GONE; map.controller.animateTo(t.third); route() }
    }

    fun setPt(isA: Boolean, g: GeoPoint) {
        if (!RIGA.contains(g)) { info.text = "Только Рига"; return }
        val old = if (isA) ma else mb; old?.let { map.overlays.remove(it) }
        val m = Marker(map).apply { position = g; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) }
        map.overlays.add(m); if (isA) { pa = g; ma = m } else { pb = g; mb = m }; map.invalidate()
    }
    fun tap(p: GeoPoint) {
        val isA = next == 0; setPt(isA, p); put(if (isA) ea else eb, "%.5f, %.5f".format(p.latitude, p.longitude)); next = 1 - next; route()
    }

    fun br(a: GeoPoint, b: GeoPoint, pr: String): R {
        val f = JSONObject(get("https://brouter.de/brouter?lonlats=${a.longitude},${a.latitude}|${b.longitude},${b.latitude}&profile=$pr&alternativeidx=0&format=geojson")).getJSONArray("features").getJSONObject(0)
        val c = f.getJSONObject("geometry").getJSONArray("coordinates"); val p = f.getJSONObject("properties")
        return R((0 until c.length()).map { val x = c.getJSONArray(it); GeoPoint(x.getDouble(1), x.getDouble(0)) }, p.getString("track-length").toDouble(), p.getString("total-time").toDouble())
    }

    fun route() {
        val a = pa ?: return; val b = pb ?: return
        info.text = "Считаю…"
        thread { try {
            val fast = br(a, b, "fastbike"); var use = fast; var note = "Самый быстрый"
            if (mode > 0) {
                val tries = if (mode == 2) listOf("safety", "trekking") else listOf("trekking")
                for (pr in tries) {
                    val q = runCatching { br(a, b, pr) }.getOrNull() ?: continue
                    if (q.len <= fast.len * 1.25) { use = q; note = (if (pr == "safety") "Скрытый путь" else "Тихий путь") + ", +" + ((q.len / fast.len - 1) * 100).toInt() + "% к длине"; break }
                }
                if (use === fast) note = "Тихий путь — слишком большой крюк, беру быстрый"
            }
            runOnUiThread { draw(use, note) }
        } catch (e: Exception) { runOnUiThread { info.text = "Не удалось построить маршрут. Проверь интернет." } } }
    }

    fun draw(r: R, note: String) {
        line?.let { map.overlays.remove(it) }
        line = Polyline().apply { setPoints(r.pts); outlinePaint.color = Color.rgb(255, 77, 109); outlinePaint.strokeWidth = 14f }
        map.overlays.add(1, line); map.zoomToBoundingBox(BoundingBox.fromGeoPoints(r.pts).increaseByScale(1.4f), true)
        turns = calcTurns(r.pts); ti = 0; riding = false; ride.text = "Поехали"
        info.text = "%.1f км · %d мин\n%s".format(r.len / 1000, (r.sec / 60).toInt(), note)
    }

    fun calcTurns(p: List<GeoPoint>): List<Pair<GeoPoint, String>> {
        val out = ArrayList<Pair<GeoPoint, String>>(); var last: GeoPoint? = null
        for (i in 1 until p.size - 1) {
            var k = i - 1; while (k > 0 && p[k].distanceToAsDouble(p[i]) < 25) k--
            var n = i + 1; while (n < p.size - 1 && p[n].distanceToAsDouble(p[i]) < 25) n++
            var d = p[i].bearingTo(p[n]) - p[k].bearingTo(p[i]); while (d > 180) d -= 360; while (d < -180) d += 360
            if (Math.abs(d) > 45 && (last == null || p[i].distanceToAsDouble(last) > 40)) { out.add(p[i] to (if (d > 0) "направо" else "налево")); last = p[i] }
        }
        out.add(p.last() to "финиш"); return out
    }

    fun follow(loc: GeoPoint) {
        if (ti >= turns.size) return
        val (p, w) = turns[ti]; val d = loc.distanceToAsDouble(p)
        if (d < 35) { say(if (w == "финиш") "Ура, ты приехал!" else "Поворот $w!"); ti++; warned = false; if (ti >= turns.size) riding = false }
        else if (d < 150 && !warned) { warned = true; say("Через ${(d / 10).toInt() * 10} метров $w") }
    }
}
