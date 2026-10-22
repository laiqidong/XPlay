package com.shilapi.xcertplay

import android.content.Context
import android.graphics.*
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.adb.LocalAdbDevicePolicy
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.Executors
import org.json.JSONObject

/** Process-owned instrument output. Activity visibility never owns the lyric clock or OEM lease. */
internal object InstrumentLyrics {
    private val main = Handler(Looper.getMainLooper())
    private val lookup = Executors.newSingleThreadExecutor()
    private val commands = Executors.newSingleThreadExecutor()
    private var context: Context? = null
    private var repository: InstrumentLyricsRepository? = null
    @Volatile private var attached = false
    private var manuallyStopped = false
    @Volatile private var server: FrameServer? = null
    private var boundSink: com.shilapi.xcertplay.media.AndroidMediaSink? = null
    @Volatile private var mapRenderer: P7InstrumentMapRenderer? = null
    data class FloatingSnapshot(val current: String = "", val next: String = "", val night: Boolean = true, val visible: Boolean = false, val status: Boolean = false,
        val songKey: String = "", val lineIndex: Int = -1, val motionEpoch: Long = 0)
    private var floatingMotionEpoch = 0L
    private var floatingSnapshot = FloatingSnapshot()
    private val floatingObservers = mutableSetOf<(FloatingSnapshot) -> Unit>()
    fun observeFloating(listener: (FloatingSnapshot) -> Unit): () -> Unit {
        floatingObservers.add(listener); listener(floatingSnapshot)
        return { floatingObservers.remove(listener) }
    }
    fun floatingStyleChanged() = main.post { floatingObservers.toList().forEach { it(floatingSnapshot) } }
    fun bindSink(sink: com.shilapi.xcertplay.media.AndroidMediaSink) = main.post { boundSink = sink; syncMap() }
    private fun needsLyrics(c: Context) = InstrumentLyricsSettings.floating(c) ||
        (InstrumentLyricsSettings.enabled(c) && !InstrumentLyricsSettings.map(c) && InstrumentLyricsSettings.lyrics(c))
    private fun syncMap() {
        val c = context ?: return
        val enabled = supported() && attached && !manuallyStopped && InstrumentLyricsSettings.enabled(c) && InstrumentLyricsSettings.map(c)
        if (enabled) {
            val renderer = mapRenderer ?: P7InstrumentMapRenderer().also { mapRenderer = it }
            boundSink?.let(renderer::bind)
        } else { mapRenderer?.close(); mapRenderer = null }
    }
    private var info = CarPlayNowPlaying()
    private var artwork: Bitmap? = null
    private var song: InstrumentSong? = null
    private var lines = emptyList<InstrumentLyricLine>()
    private var anchor = 0L
    private var anchorAt = 0L
    private var progressAt = 0L
    private var lastLookup = 0L
    private var clockKnown = false
    private var launching = false
    private var pendingInteractiveStart = false
    @Volatile private var activeAdb: LocalAdb? = null
    @Volatile private var sessionGeneration = 0L
    private var retryAt = 0L
    private var revision = 0L
    @Volatile private var picture = Picture()
    @Volatile var channelState = "stopped"; private set
    @Volatile var lyricState = "waiting_song"; private set
    @Volatile var diagnostics = ""; private set
    private val observers = mutableSetOf<() -> Unit>()
    private data class Picture(val title: String = "", val artist: String = "", val current: String = "",
        val next: String = "", val previous: String = "", val playing: Boolean = false, val cover: Bitmap? = null,
        val font: Int = 100, val night: Boolean = true, val revision: Long = 0)

    fun supported() = LocalAdbDevicePolicy.isXpengP7() && android.os.Build.VERSION.SDK_INT == 28
    fun observe(listener: () -> Unit): () -> Unit { observers.add(listener); listener(); return { observers.remove(listener) } }
    private fun changed() { observers.toList().forEach { it() } }
    private fun initialize(c: Context) {
        if (context == null) { context = c.applicationContext; repository = InstrumentLyricsRepository(c.applicationContext) }
    }
    fun attach(c: Context) = main.post {
        initialize(c); attached = true; manuallyStopped = false; sessionGeneration++
        refresh(); syncMap(); main.removeCallbacks(tick); main.post(tick)
    }
    fun detach() = main.post {
        attached = false; sessionGeneration++; repository?.cancel(); song = null; lines = emptyList()
        info = CarPlayNowPlaying(); artwork = null; clockKnown = false
        boundSink = null; mapRenderer?.close(); mapRenderer = null
        floatingSnapshot = FloatingSnapshot(); floatingObservers.toList().forEach { it(floatingSnapshot) }
        lyricState = "waiting_song"; stopOutput(); main.removeCallbacks(tick); changed()
    }
    fun update(next: CarPlayNowPlaying, cover: Bitmap?) = main.post {
        if (!attached) return@post
        val track = info.title != next.title || info.artist != next.artist || info.album != next.album || info.sourceApp != next.sourceApp
        val now = SystemClock.elapsedRealtime()
        if (track || next.playing != info.playing || (clockKnown && next.elapsedMillis != null &&
                next.elapsedMillis != info.elapsedMillis && kotlin.math.abs(next.elapsedMillis!! - position(now)) > 1000)) floatingMotionEpoch++
        if (track) { clockKnown = false; progressAt = 0; anchor = 0; lines = emptyList() }
        if (next.elapsedMillis != null && (!track || next.elapsedMillis != info.elapsedMillis || info.title == null)) {
            if (next.elapsedMillis != info.elapsedMillis || !clockKnown || next.playing != info.playing) {
                anchor = next.elapsedMillis!!; anchorAt = now; progressAt = now; clockKnown = true
            }
        } else if (!track && info.playing && !next.playing && clockKnown) {
            anchor = position(now); anchorAt = now
        }
        if (!track && !info.playing && next.playing && clockKnown) anchorAt = now
        info = next; artwork = cover
        val selected = next.title?.takeIf { it.isNotBlank() }?.let {
            InstrumentSong(it, next.artist.orEmpty(), next.album.orEmpty(), next.durationMillis ?: 0)
        }
        if (selected != song) {
            repository?.cancel(); song = selected; lines = emptyList(); lyricState = "waiting_song"
            if (selected != null) requestLyrics()
        }
        drawState(); changed()
    }
    private fun position(now: Long): Long = (anchor + if (info.playing) (now - anchorAt).coerceAtLeast(0) else 0)
        .coerceIn(0, info.durationMillis?.coerceAtLeast(0) ?: Long.MAX_VALUE)

    fun settingsChanged(c: Context) = main.post {
        initialize(c)
        floatingMotionEpoch++
        // Privacy changes invalidate in-flight online responses immediately.
        repository?.cancel()
        if (song != null) requestLyrics()
        refresh(); syncMap(); drawState(); floatingStyleChanged(); changed()
    }
    fun start(c: Context) = main.post {
        initialize(c); manuallyStopped = false
        if (!InstrumentLyricsSettings.enabled(c)) { channelState = "disabled"; changed(); return@post }
        if (!attached) { channelState = "waiting_carplay"; changed(); return@post }
        ensureServer(); syncMap(); launch(true)
    }
    fun stop() = main.post { manuallyStopped = true; if (context?.let { !InstrumentLyricsSettings.floating(it) } == true) repository?.cancel(); syncMap(); stopOutput(); changed() }
    private fun stopOutput() {
        if (server == null && !launching) { channelState = "stopped"; return }
        sessionGeneration++
        pendingInteractiveStart = false
        server?.close(); server = null
        activeAdb?.cancelPendingOperations()
        channelState = "restoring"; diagnostics = ""
        // The shell guardian restores after EOF; status below verifies its recovery marker.
        val c = context ?: return
        val recoveryGeneration = sessionGeneration
        commands.execute {
            var remaining: String? = null
            runCatching { LocalAdb(AdbKeys.load(c)).use { adb ->
                if (adb.connect(false) == LocalAdb.Access.READY)
                    remaining = adb.shell("sleep 1; if [ -e /data/local/tmp/xplay-instrument-native-lease-v1 ]; then echo pending; else echo released; fi")
            } }
            main.post {
                if (server == null && sessionGeneration == recoveryGeneration) {
                    channelState = if (remaining == "released") "released" else "restore_unconfirmed"
                    changed()
                }
            }
        }
    }
    private fun refresh() {
        val c = context ?: return
        syncMap()
        if (!supported() || !InstrumentLyricsSettings.enabled(c) || !attached || manuallyStopped) {
            if (server != null) stopOutput()
            if (channelState !in setOf("restoring", "released", "restore_unconfirmed"))
                channelState = if (!attached) "waiting_carplay" else "disabled"
            return
        }
        if (server == null && InstrumentLyricsSettings.autoStart(c)) { ensureServer(); launch(false) }
    }
    private fun ensureServer() {
        if (server == null) server = FrameServer().also { it.start() }
    }
    private fun launch(interactive: Boolean) {
        val c = context ?: return
        if (launching) { pendingInteractiveStart = pendingInteractiveStart || interactive; return }
        if (!interactive && SystemClock.elapsedRealtime() < retryAt) return
        if (c.packageName != "com.laiqidong.xplay") { channelState = "release_required"; changed(); return }
        launching = true; channelState = "authorizing"; changed()
        val generation = sessionGeneration
        commands.execute {
            var status = "adb_unavailable"
            try { LocalAdb(AdbKeys.load(c)).use { adb ->
                activeAdb = adb
                val access = adb.connect(interactive)
                status = if (access == LocalAdb.Access.NOT_APPROVED) "authorization_required" else "adb_unavailable"
                if (access == LocalAdb.Access.READY) {
                    if (adb.shell("id -u") != "2000") status = "shell_required"
                    else {
                        fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
                        val classpath = quote(c.applicationInfo.sourceDir + ":/system/framework/android.car.jar")
                        if (server != null && generation == sessionGeneration) {
                            adb.shell("CLASSPATH=$classpath /system/bin/nohup /system/bin/app_process /system/bin --nice-name=xplay-p7-instrument com.shilapi.xcertplay.P7InstrumentWorker >/dev/null 2>&1 </dev/null &")
                            status = "starting"
                        }
                    }
                }
            } } catch (_: Exception) { status = "adb_unavailable" }
            finally { activeAdb = null }
            main.post {
                launching = false; retryAt = SystemClock.elapsedRealtime() + 30000
                if (generation == sessionGeneration && server != null) { channelState = status; changed() }
                if (pendingInteractiveStart) { pendingInteractiveStart = false; if (server != null) launch(true) }
            }
        }
    }
    fun rematch() = main.post {
        val c = context ?: return@post
        val key = song?.key ?: return@post
        repository?.cancel()
        java.io.File(c.cacheDir, "instrument-lyrics/$key.lrc").delete()
        lines = emptyList(); requestLyrics()
    }
    fun clearCache(c: Context) { initialize(c); repository?.clear(); main.post { lines = emptyList(); lyricState = "cache_cleared"; drawState(); changed() } }
    fun cacheBytes(c: Context): Long { initialize(c); return repository?.bytes() ?: 0 }
    private fun requestLyrics() {
        val selected = song ?: return
        val c = context ?: return
        if (!attached || (manuallyStopped && !InstrumentLyricsSettings.floating(c)) || !needsLyrics(c)) { lyricState = "waiting_song"; return }
        if (!needsLyrics(c)) { lyricState = "song_only"; return }
        val repo = repository ?: return
        val ticket = repo.generation
        val online = InstrumentLyricsSettings.online(c)
        lyricState = "searching"; lastLookup = SystemClock.elapsedRealtime()
        lookup.execute {
            val result = repo.find(selected, online, ticket)
            main.post {
                if (repo.generation != ticket || selected != song || !attached) return@post
                lines = result.lines; lyricState = result.status; drawState(); changed()
            }
        }
    }
    private fun drawState() {
        val c = context ?: return
        val now = SystemClock.elapsedRealtime()
        val trusted = clockKnown && (!info.playing || now - progressAt < 15000)
        val index = if (trusted && needsLyrics(c))
            lines.indexOfLast { it.time <= position(now) - InstrumentLyricsSettings.delay(c) } else -1
        val current = when {
            !InstrumentLyricsSettings.lyrics(c) -> ""
            !trusted -> c.getString(com.shilapi.xcertplay.host.R.string.settings_instrument_wait_progress)
            index >= 0 -> lines[index].text
            lines.isNotEmpty() -> c.getString(com.shilapi.xcertplay.host.R.string.settings_instrument_intro)
            else -> c.getString(com.shilapi.xcertplay.host.R.string.settings_instrument_no_lyrics)
        }
        val showLyrics = trusted && needsLyrics(c)
        val previous = if (showLyrics && index > 0) lines[index - 1].text else ""
        val next = if (showLyrics && index + 1 in lines.indices) lines[index + 1].text else ""
        val chinese = repository?.chinese
        fun simplified(value: String) = chinese?.simplified(value) ?: value
        val night = c.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK !=
            android.content.res.Configuration.UI_MODE_NIGHT_NO
        val p = Picture(simplified(info.title.orEmpty()), simplified(info.artist.orEmpty()),
            simplified(current), simplified(next), simplified(previous), info.playing,
            artwork.takeIf { InstrumentLyricsSettings.cover(c) }, InstrumentLyricsSettings.font(c), night)
        if (p != picture.copy(revision = 0)) { revision++; picture = p.copy(revision = revision) }
        val hasLine = trusted && index >= 0 && lines[index].text.isNotBlank()
        val statusResource = when {
            lyricState == "searching" -> com.shilapi.xcertplay.host.R.string.settings_instrument_searching
            lyricState == "network_error" -> com.shilapi.xcertplay.host.R.string.settings_instrument_network_error
            lyricState == "offline" -> com.shilapi.xcertplay.host.R.string.settings_instrument_offline
            lines.isEmpty() -> com.shilapi.xcertplay.host.R.string.settings_instrument_no_lyrics
            !trusted -> com.shilapi.xcertplay.host.R.string.settings_instrument_wait_progress
            else -> com.shilapi.xcertplay.host.R.string.settings_instrument_intro
        }
        val floating = FloatingSnapshot(
            if (hasLine) simplified(lines[index].text) else simplified(info.title.orEmpty()),
            if (hasLine) simplified(next) else c.getString(statusResource), night,
            attached && InstrumentLyricsSettings.floating(c) && info.playing && !info.title.isNullOrBlank(),
            status = !hasLine, songKey = song?.key.orEmpty(), lineIndex = index, motionEpoch = floatingMotionEpoch)
        if (floating != floatingSnapshot) {
            floatingSnapshot = floating; floatingObservers.toList().forEach { it(floating) }
        }
    }
    private var ticks = 0
    private val tick = object : Runnable {
        override fun run() {
            if (!attached) return
            refresh(); drawState()
            if (++ticks % 10 == 0) {
                if (server != null) pollWorker()
                if (lyricState == "network_error" && SystemClock.elapsedRealtime() - lastLookup > 60000) requestLyrics()
                changed()
            }
            main.postDelayed(this, 200)
        }
    }
    private var polling = false
    private fun pollWorker() {
        if (polling) return
        polling = true
        val generation = sessionGeneration
        commands.execute {
            var data: JSONObject? = null
            runCatching { LocalSocket().use { socket ->
                socket.connect(LocalSocketAddress("xplay.p7.instrument.control.v1", LocalSocketAddress.Namespace.ABSTRACT))
                check(socket.peerCredentials.uid == 2000); socket.soTimeout = 1500
                data = JSONObject(DataInputStream(socket.inputStream).readUTF())
            } }
            main.post {
                polling = false
                if (generation == sessionGeneration && server != null) {
                    if (data != null) {
                        channelState = data!!.optString("state", "starting")
                        diagnostics = "${data!!.optInt("width")}×${data!!.optInt("height")} · ${data!!.optInt("requestedFps")} fps · ${data!!.optLong("submittedFrames")}" 
                    } else if (!launching) {
                        if (channelState !in setOf("authorization_required", "shell_required", "release_required", "adb_unavailable")) channelState = "unavailable"
                        if (InstrumentLyricsSettings.autoStart(context!!)) launch(false)
                    }
                    changed()
                }
            }
        }
    }

    private class FrameServer : AutoCloseable {
        @Volatile private var closed = false
        @Volatile private var listener: LocalServerSocket? = null
        @Volatile private var client: LocalSocket? = null
        fun start() { Thread(::serve, "xplay-instrument-frames").apply { isDaemon = true; start() } }
        private fun serve() {
            try {
                val server = LocalServerSocket("xplay.p7.instrument.frames.v1"); listener = server
                if (closed) return
                while (!closed) {
                    val socket = server.accept(); client = socket
                    try {
                        check(socket.peerCredentials.uid == 2000); socket.soTimeout = 3000
                        val input = DataInputStream(socket.inputStream); val out = DataOutputStream(socket.outputStream)
                        check(input.readInt() == 0x4f504332); out.writeInt(0x4f504332); out.flush()
                        var cachedKey = ""; var jpeg = ByteArray(0)
                        while (!closed) {
                            when (input.readInt()) {
                                2 -> out.writeInt(if (closed) 0 else 1)
                                3 -> out.writeInt(if (context?.let { InstrumentLyricsSettings.hideLane(it) } == true) 1 else 0)
                                1 -> {
                                    val width = input.readInt(); val height = input.readInt()
                                    check(width in 64..1024 && height in 64..1024)
                                    if (context?.let { InstrumentLyricsSettings.map(it) } == true) {
                                        val frame = mapRenderer?.capture(width, height)
                                        if (frame == null) { out.writeLong(-1); out.writeInt(0); out.flush(); continue }
                                        val bytes = ByteArrayOutputStream()
                                        try { check(frame.second.compress(Bitmap.CompressFormat.JPEG, 85, bytes)) }
                                        finally { frame.second.recycle() }
                                        check(bytes.size() in 4..1_048_576)
                                        out.writeLong(frame.first); out.writeInt(bytes.size()); bytes.writeTo(out); out.flush()
                                        continue
                                    }
                                    val p = picture
                                    val key = "$width/$height/${p.revision}"
                                    if (key != cachedKey) {
                                        val bitmap = render(p, width, height)
                                        try {
                                            val bytes = ByteArrayOutputStream()
                                            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, bytes))
                                            jpeg = bytes.toByteArray(); check(jpeg.size in 4..1_048_576); cachedKey = key
                                        } finally { bitmap.recycle() }
                                    }
                                    out.writeLong(p.revision); out.writeInt(jpeg.size); out.write(jpeg)
                                }
                                else -> break
                            }
                            out.flush()
                        }
                    } catch (_: Exception) { }
                    finally { runCatching { socket.close() }; client = null }
                }
            } catch (_: Exception) { }
            finally { runCatching { listener?.close() } }
        }
        override fun close() { closed = true; runCatching { client?.close() }; runCatching { listener?.close() } }
    }

    private fun render(p: Picture, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val background = if (p.night) Color.rgb(12, 15, 20) else Color.rgb(242, 243, 245)
        val primary = if (p.night) Color.rgb(245, 246, 248) else Color.rgb(25, 29, 35)
        val secondary = if (p.night) Color.rgb(173, 181, 194) else Color.rgb(79, 88, 101)
        canvas.drawColor(background)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        p.cover?.takeUnless { it.isRecycled }?.let { cover ->
            runCatching { paint.alpha = if (p.night) 36 else 20; canvas.drawBitmap(cover, null, Rect(0, 0, width, height), paint) }
            paint.alpha = 255
        }
        // Reference-canvas units, independent of Android UI density; scale to the OEM request.
        val scale = minOf(width, height) / 660f
        val x = width * .08f; val available = width * .84f
        val headerGap = 10f * scale
        val lyricGap = 16f * scale
        fun layout(value: String, size: Float, color: Int, maxLines: Int, bold: Boolean): android.text.StaticLayout {
            val tp = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size * scale; this.color = color
                typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
            return android.text.StaticLayout.Builder.obtain(value, 0, value.length, tp, available.toInt().coerceAtLeast(1))
                .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER).setMaxLines(maxLines)
                .setLineSpacing(0f, 1f)
                .setEllipsize(android.text.TextUtils.TruncateAt.END).setIncludePad(false).build()
        }
        fun draw(text: android.text.StaticLayout, y: Float) {
            canvas.save(); canvas.translate(x, y); text.draw(canvas); canvas.restore()
        }
        val c = context!!
        val title = layout(p.title.ifBlank { c.getString(com.shilapi.xcertplay.host.R.string.settings_instrument_wait_song) }, 30f, primary, 2, true)
        val artist = layout(p.artist, 23f, secondary, 1, false)
        val headerTop = height * .14f
        draw(title, headerTop)
        val artistTop = headerTop + title.height + headerGap
        draw(artist, artistTop)
        val current = layout(p.current, 44f * p.font / 100, primary, 2, true)
        val previousColor = if (p.night) Color.rgb(136, 145, 159) else Color.rgb(105, 113, 125)
        val previous = layout(p.previous, 25f, previousColor, 1, false)
        val previousHeight = if (p.previous.isBlank()) 0 else previous.height
        val next = layout(p.next, 27f, secondary, 2, false)
        val nextHeight = if (p.next.isBlank()) 0 else next.height
        val precedingHeight = if (previousHeight > 0) previousHeight + lyricGap else 0f
        val followingHeight = if (nextHeight > 0) lyricGap + nextHeight else 0f
        val blockHeight = precedingHeight + current.height + followingHeight
        // Measure the whole group so wrapped current/next lyrics stay compact without overlap.
        val groupTop = (height * .57f - blockHeight / 2)
            .coerceAtLeast(artistTop + artist.height + 30f * scale)
        if (previousHeight > 0) draw(previous, groupTop)
        val currentTop = groupTop + precedingHeight
        draw(current, currentTop)
        if (nextHeight > 0) draw(next, currentTop + current.height + lyricGap)
        if (!p.playing) draw(layout(c.getString(com.shilapi.xcertplay.host.R.string.settings_instrument_paused),
            20f, secondary, 1, false), height * .88f)
        return bitmap
    }
}
