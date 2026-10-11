package io.github.jqssun.airplay.renderer

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.util.Base64
import android.view.Surface
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import io.github.jqssun.airplay.renderer.DecoderSelector.Companion.videoCaps

class VideoRenderer(ctx: Context) {
    private val pollOutput by lazy {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getBoolean("lab_output_poll", false)
    }
    private var outputExecutor: ScheduledExecutorService? = null
    private var outputPoll: ScheduledFuture<*>? = null
    private val captureHeaders by lazy { ctx.packageName.endsWith(".lab") }
    private var headersLogged = false

    // Opt-in experiment only; keep this separate from the resolution comparison.
    private val sonyOutputPath by lazy {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getBoolean("lab_sony_output_path", false)
    }
    private val useWorker by lazy {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("lab_decoder_worker", false)
    }
    private val directSurface by lazy {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("lab_direct_surface", false)
    }
    private val sonyFormatPath by lazy {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("lab_sony_format_path", false)
    }
    private data class Frame(val data: ByteArray, val ptsNs: Long, val hevc: Boolean, val arrivalNs: Long)
    private var workerDelegate: Lazy<BoundedFrameWorker<Frame>> = createWorker()
    private fun createWorker(): Lazy<BoundedFrameWorker<Frame>> = lazy {
        BoundedFrameWorker<Frame>(8, { frame, token ->
            synchronized(lock) {
                if (workerDelegate.value.current(token)) {
                    processFrame(frame.data, frame.ptsNs, frame.hevc, frame.arrivalNs)
                }
            }
        }, {
            synchronized(lock) {
                try { drainOutput() } catch (e: Exception) {
                    Log.w(TAG, "Worker drain failed", e)
                    stopCodec()
                }
            }
        })
    }

    private val lock = Object()
    private val pipeline = VideoPipeline()
    private val timing = VideoTimingProbe()
    val selector = DecoderSelector(ctx)
    private var avcDecoder: MediaCodecInfo? = null
    private var hevcDecoder: MediaCodecInfo? = null
    private var maxFps = 0
    private var codec: MediaCodec? = null
    private var displaySurface: Surface? = null
    private var currentH265 = false
    private var videoWidth = 0
    private var videoHeight = 0
    private var firstFrameQueued = false

    // stats
    @Volatile var fps = 0; private set
    @Volatile var bitrateBps = 0L; private set
    @Volatile var frameCount = 0L; private set
    @Volatile var codecName = ""; private set
    @Volatile var droppedFrames = 0L; private set
    @Volatile var framePacingJitterUs = 0L; private set

    var enforceSdr = true
    var keyAllowFrameDrop = true
    var scheduledOutputBufferRelease = true
    var benchmarkLog = false
    var benchmarkLogCallback: ((String) -> Unit)? = null
    private var _framesThisSec = 0
    private var _bytesThisSec = 0L
    private var _lastStatReset = 0L
    private val _frameIntervalsNs = LongArray(120)
    private var _frameIntervalIdx = 0
    private var _frameIntervalCount = 0
    private var _lastOutputFrameNs = 0L
    // anchors that map decoder PTS (us) to System.nanoTime() for scheduled rendering
    private var _ptsBaseUs = Long.MIN_VALUE
    private var _wallBaseNs = 0L

    fun setResolution(w: Int, h: Int) {
        videoWidth = w
        videoHeight = h
        pipeline.setVideoSize(w, h)
    }

    // doesn't restart codec; decoder renders into pipeline's own persistent surface
    fun setSurface(surface: Surface) = synchronized(lock) {
        if (directSurface && displaySurface === surface) return@synchronized
        displaySurface = surface
        if (directSurface) {
            codec?.let { c ->
                try {
                    c.setOutputSurface(surface)
                    Log.i(TAG, "Decoder surface switched to direct display")
                }
                catch (e: Exception) { Log.w(TAG, "Direct surface switch failed", e); stopCodec() }
            }
        } else pipeline.setDisplaySurface(surface)
    }

    fun clearSurface(surface: Surface) = synchronized(lock) {
        if (displaySurface !== surface) return@synchronized
        displaySurface = null
        if (directSurface) {
            // Keep consuming into the persistent fallback while Compose replaces a SurfaceView.
            val fallback = pipeline.inputSurface
            codec?.let { c ->
                try { if (fallback != null) c.setOutputSurface(fallback) else stopCodec() }
                catch (e: Exception) { Log.w(TAG, "Fallback surface switch failed", e); stopCodec() }
            }
        } else pipeline.setDisplaySurface(null)
    }

    fun selectDecoders(w: Int, h: Int, fps: Int, h265: Boolean): Boolean = synchronized(lock) {
        avcDecoder = selector.avc()
        hevcDecoder = if (h265) selector.hevc(avcDecoder, w, h, fps) else null
        maxFps = fps
        Log.i(TAG, "decoders: avc=${avcDecoder?.name} hevc=${hevcDecoder?.name}")
        avcDecoder?.let { info ->
            runCatching {
                val caps = info.videoCaps(DecoderSelector.AVC)
                Log.i(TAG, "AVC limits: size=${caps.supportedWidths}x${caps.supportedHeights} " +
                    "requested=${w}x${h}@$fps supported=${caps.areSizeAndRateSupported(w, h, fps.toDouble())} " +
                    "achievable=${caps.getAchievableFrameRatesFor(w, h)}")
            }.onFailure { Log.i(TAG, "AVC limits unavailable for ${w}x$h: ${it.message}") }
        }
        hevcDecoder != null
    }

    // unknown limits default to 1080p
    fun maxResolution(): Pair<Int, Int> =
        listOfNotNull(avcDecoder?.let { it to DecoderSelector.AVC }, hevcDecoder?.let { it to DecoderSelector.HEVC })
            .map { (info, mime) ->
                runCatching { info.videoCaps(mime).let { it.supportedWidths.upper to it.supportedHeights.upper } }
                    .getOrDefault(1920 to 1080)
            }
            .reduceOrNull { (w1, h1), (w2, h2) -> minOf(w1, w2) to minOf(h1, h2) } ?: (1920 to 1080)

    // codec per mirror session; pipeline persists across sessions
    fun startSession() = synchronized(lock) {
        _resetStats()
        headersLogged = false
        if (useWorker) workerDelegate.value.session(true)
        Log.i(TAG, "Decoder scheduling: ${if (useWorker) "bounded worker (8 frames)" else "inline"}")
    }

    fun stopSession() = synchronized(lock) {
        if (workerDelegate.isInitialized()) workerDelegate.value.session(false)
        stopCodec()
    }

    private fun _resetStats() {
        fps = 0; bitrateBps = 0; frameCount = 0; codecName = ""
        droppedFrames = 0; framePacingJitterUs = 0
        _framesThisSec = 0; _bytesThisSec = 0
    }

    private fun _updateStats(size: Int) {
        val now = System.currentTimeMillis()
        if (now - _lastStatReset >= 1000) {
            fps = _framesThisSec
            bitrateBps = _bytesThisSec * 8
            framePacingJitterUs = _computeFramePacingJitterUs()
            _framesThisSec = 0
            _bytesThisSec = 0
            _lastStatReset = now
            if (benchmarkLog) _emitBenchmarkLine()
        }
        _framesThisSec++
        _bytesThisSec += size
        frameCount++
    }

    private fun _emitBenchmarkLine() {
        val msg = "fps=$fps bitrate=${bitrateBps / 1000}kbps " +
            "jitter=${framePacingJitterUs}us frames=$frameCount " +
            "dropped=$droppedFrames codec=$codecName " +
            "res=${videoWidth}x${videoHeight}"
        Log.i(BENCH_TAG, msg)
        benchmarkLogCallback?.invoke(msg)
    }

    fun feedFrame(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        val arrivalNs = System.nanoTime()
        if (useWorker) {
            workerDelegate.value.submit(Frame(data.copyOf(), ntpTimeNs, isH265, arrivalNs))
        } else processFrame(data, ntpTimeNs, isH265, arrivalNs)
    }

    private fun processFrame(data: ByteArray, ntpTimeNs: Long, isH265: Boolean, arrivalNs: Long) {
        synchronized(lock) {
            if (captureHeaders && !headersLogged && !isH265) {
                // Parameter sets describe the stream; never log compressed picture data.
                var foundSps = false
                var pos = 0
                while (pos + 4 < data.size) {
                    val start = when {
                        data[pos] == 0.toByte() && data[pos+1] == 0.toByte() && data[pos+2] == 1.toByte() -> pos + 3
                        data[pos] == 0.toByte() && data[pos+1] == 0.toByte() && data[pos+2] == 0.toByte() && data[pos+3] == 1.toByte() -> pos + 4
                        else -> { pos++; continue }
                    }
                    var end = start + 1
                    while (end + 2 < data.size && !(data[end] == 0.toByte() && data[end+1] == 0.toByte() &&
                        (data[end+2] == 1.toByte() || (end+3 < data.size && data[end+2] == 0.toByte() && data[end+3] == 1.toByte())))) end++
                    if (end + 2 >= data.size) end = data.size
                    val type = data[start].toInt() and 31
                    if ((type == 7 || type == 8) && end - start <= 1024) {
                        Log.i(TAG, "AVC_PARAMETER_SET type=$type base64=${Base64.encodeToString(data.copyOfRange(start, end), Base64.NO_WRAP)}")
                        if (type == 7) foundSps = true
                    }
                    pos = end
                }
                headersLogged = foundSps
            }
            timing.arrival(ntpTimeNs / 1000, arrivalNs)
            _updateStats(data.size)
            if (videoWidth == 0 || videoHeight == 0) return

            if (codec == null || isH265 != currentH265) {
                // a stale reference frame decodes to corruption, so wait for a keyframe to (re)start
                if (!_isKeyframe(data, isH265)) {
                    if (codec != null) stopCodec()
                    return
                }
                stopCodec()
            }

            try {
                if (codec == null) startCodec(isH265)
                _feedToCodec(data, ntpTimeNs)
                drainOutput()
            } catch (e: Exception) {
                Log.w(TAG, "Codec error, resetting", e)
                stopCodec()
            } finally {
                timing.callbackFinished(arrivalNs)
            }
        }
    }

    private fun _feedToCodec(data: ByteArray, ntpTimeNs: Long) {
        val c = codec ?: return
        // dropping a frame desyncs decoder until the next keyframe, but source would only send one on (re)connect
        val retries = if (firstFrameQueued) FEED_RETRIES else FIRST_FEED_RETRIES
        repeat(retries) {
            // Return decoded buffers before asking the codec for another input slot.
            drainOutput()
            val waitStart = System.nanoTime()
            val idx = c.dequeueInputBuffer(FEED_WAIT_US)
            timing.inputWait(System.nanoTime() - waitStart)
            if (idx >= 0) {
                val buf = c.getInputBuffer(idx) ?: return
                buf.clear()
                buf.put(data)
                c.queueInputBuffer(idx, 0, data.size, ntpTimeNs / 1000, 0)
                timing.queued(ntpTimeNs / 1000)
                firstFrameQueued = true
                return
            }
            drainOutput()
        }
        droppedFrames++
        Log.w(TAG, "Decoder input queue full; dropping frame. drops=$droppedFrames")
    }

    private fun _isKeyframe(data: ByteArray, isH265: Boolean): Boolean {
        if (data.size < 5) return false
        var i = 0
        while (i <= data.size - 5) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                val key = if (isH265) {
                    val type = (data[i + 4].toInt() shr 1) and 0x3F
                    type == 19 || type == 20 || type == 21 || type == 32 || type == 33
                } else {
                    val type = data[i + 4].toInt() and 0x1F
                    type == 5 || type == 7
                }
                if (key) return true
            }
            i++
        }
        return false
    }

    private fun startCodec(h265: Boolean) {
        pipeline.start()
        pipeline.setVideoSize(videoWidth, videoHeight)
        val s = (if (directSurface) displaySurface?.takeIf { it.isValid } else null)
            ?: pipeline.inputSurface ?: return
        Log.i(TAG, "Decoder surface: ${if (directSurface && s === displaySurface) "direct display" else "GL pipeline"}")
        currentH265 = h265
        val mime = if (h265) DecoderSelector.HEVC else DecoderSelector.AVC
        val info = (if (h265) hevcDecoder else avcDecoder) ?: error("no decoder selected for $mime")

        firstFrameQueued = false
        try {
            _startWithLadder(info, mime, s, h265)
        } catch (e: Exception) {
            // strict hw decoders reject configs beyond their real limits
            val sw = selector.software(mime, videoWidth, videoHeight) ?: throw e
            Log.w(TAG, "Hardware decoder failed, trying software fallback", e)
            _startWithLadder(sw, mime, s, h265)
        }
        Log.i(TAG, "Video codec started: $mime ${videoWidth}x${videoHeight} ($codecName)")
    }

    private fun _startWithLadder(info: MediaCodecInfo, mime: String, s: Surface, h265: Boolean) {
        var tryNum = 0
        while (true) {
            val format = _format(mime, info)
            val more = selector.lowLatencyOptions(format, info, mime, tryNum)
            try {
                _startDecoder(MediaCodec.createByCodecName(info.name), format, s, h265)
                return
            } catch (e: Exception) {
                if (!more) throw e
                Log.w(TAG, "configure try $tryNum failed: $format", e)
                tryNum++
            }
        }
    }

    private fun _format(mime: String, info: MediaCodecInfo) = MediaFormat.createVideoFormat(mime, videoWidth, videoHeight).apply {
        if (sonyFormatPath && info.name.startsWith("OMX.MTK.", ignoreCase = true)) {
            // This TV's MtkMediaCodecPlugin.configure reads an integer, unlike setParameters.
            // 0x100 is the plugin's OUTPUT_VIDEO_MAIN value; actual routing still needs validation.
            setInteger("X-tv-output-path", 0x100)
        }
        setInteger(MediaFormat.KEY_FRAME_RATE, maxFps)
        if (selector.adaptive(info, mime)) {
            setInteger(MediaFormat.KEY_MAX_WIDTH, videoWidth)
            setInteger(MediaFormat.KEY_MAX_HEIGHT, videoHeight)
        }
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(videoWidth * videoHeight * 3 / 4, 1024 * 1024))
        if (enforceSdr) {
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, if (keyAllowFrameDrop) 1 else 0)
        }
    }

    private fun _startDecoder(c: MediaCodec, format: MediaFormat, surface: Surface, h265: Boolean) {
        try {
            if (sonyOutputPath && c.name.startsWith("OMX.MTK.", ignoreCase = true)) {
                // Same key/value and ordering observed in Sony 6.6053's native renderer.
                // Acceptance does not prove that the vendor actually changes output paths.
                runCatching {
                    c.setParameters(Bundle().apply { putString("X-tv-output-path", "OUTPUT_VIDEO_MAIN") })
                }.onSuccess { Log.i(TAG, "Sony output-path request accepted by API") }
                    .onFailure { Log.w(TAG, "Sony output-path request rejected; using normal surface", it) }
            }
            Log.i(TAG, "Configuring ${c.name}: $format")
            c.configure(format, surface, null, 0)
            c.start()
        } catch (e: Exception) {
            try { c.release() } catch (_: Exception) {}
            throw e
        }
        codec = c
        codecName = (if (h265) "H.265" else "H.264") + " (${c.name})"
        if (pollOutput && !useWorker) {
            val executor = outputExecutor ?: Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "AirPlayOutput")
            }.also { outputExecutor = it }
            outputPoll = executor.scheduleWithFixedDelay({
                synchronized(lock) {
                    // A cancelled task may already be waiting for the codec lock.
                    if (codec === c) {
                        try { drainOutput() }
                        catch (e: Exception) {
                            Log.w(TAG, "Output polling failed", e)
                            stopCodec()
                        }
                    }
                }
            }, 2, 2, TimeUnit.MILLISECONDS)
            Log.i(TAG, "Independent output polling enabled: 2ms, no input queue")
        }
    }

    private fun stopCodec() {
        outputPoll?.cancel(false)
        outputPoll = null
        timing.reset()
        _frameIntervalIdx = 0
        _frameIntervalCount = 0
        _lastOutputFrameNs = 0L
        _ptsBaseUs = Long.MIN_VALUE
        _wallBaseNs = 0L
        codec?.let {
            try {
                it.stop()
                it.release()
            } catch (_: Exception) {}
        }
        codec = null
    }

    private fun drainOutput() {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = c.dequeueOutputBuffer(info, 0)
            if (idx < 0) break
            _recordOutputFrameTime()
            if (scheduledOutputBufferRelease) {
                // schedule frame at VSYNC matching its NTP presentation time
                val ptsUs = info.presentationTimeUs
                if (_ptsBaseUs == Long.MIN_VALUE) {
                    _ptsBaseUs = ptsUs
                    _wallBaseNs = System.nanoTime()
                }
                val releaseNs = _wallBaseNs + (ptsUs - _ptsBaseUs) * 1000L
                timing.output(ptsUs, releaseNs)
                c.releaseOutputBuffer(idx, releaseNs)
            } else {
                timing.output(info.presentationTimeUs, System.nanoTime())
                c.releaseOutputBuffer(idx, true)
            }
        }
    }

    fun release() {
        // Never join the worker while holding the codec lock it needs to finish.
        if (workerDelegate.isInitialized()) workerDelegate.value.close()
        synchronized(lock) {
            stopCodec()
            outputExecutor?.shutdown()
            outputExecutor = null
            pipeline.release()
            _resetStats()
            workerDelegate = createWorker()
        }
    }

    private fun _recordOutputFrameTime() {
        val now = System.nanoTime()
        if (_lastOutputFrameNs > 0) {
            _frameIntervalsNs[_frameIntervalIdx % _frameIntervalsNs.size] = now - _lastOutputFrameNs
            _frameIntervalIdx++
            _frameIntervalCount++
        }
        _lastOutputFrameNs = now
    }

    private fun _computeFramePacingJitterUs(): Long {
        val count = _frameIntervalCount.coerceAtMost(_frameIntervalsNs.size)
        if (count < 2) return 0

        var sum = 0.0
        var sumSq = 0.0
        for (i in 0 until count) {
            val interval = _frameIntervalsNs[i].toDouble()
            sum += interval
            sumSq += interval * interval
        }
        val mean = sum / count
        val variance = (sumSq / count) - (mean * mean)
        return (kotlin.math.sqrt(variance.coerceAtLeast(0.0)) / 1000.0).toLong()
    }

    companion object {
        private const val TAG = "VideoRenderer"
        private const val BENCH_TAG = "BENCHMARK"
        private const val FEED_WAIT_US = 1_000L
        private const val FEED_RETRIES = 200
        private const val FIRST_FEED_RETRIES = 1000
    }
}
