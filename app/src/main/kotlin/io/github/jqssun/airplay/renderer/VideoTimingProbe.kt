package io.github.jqssun.airplay.renderer

import android.util.Log

/** Receiver-local timings; no sender clock or glass-to-glass latency claim. */
internal class VideoTimingProbe {
    private data class Queued(val arrival: Long, val queued: Long)
    private val arrivals = LinkedHashMap<Long, Queued>()
    private var maxCodecResidence = 0L
    private var maxPreCodec = 0L
    private val residenceSamples = ArrayList<Long>()
    private var lastReport = System.nanoTime()
    private var maxWait = 0L
    private var maxCallback = 0L
    private var maxResidence = 0L
    private var maxAhead = 0L
    private var outputs = 0
    private var basePts = Long.MIN_VALUE
    private var baseArrival = 0L
    private var drift = 0L
    private var lastPts = 0L
    private var lastArrival = 0L

    fun arrival(pts: Long, now: Long) {
        lastPts = pts
        lastArrival = now
        if (basePts == Long.MIN_VALUE) { basePts = pts; baseArrival = now }
        // Growth of arrival delay relative to source PTS, not an absolute clock offset.
        drift = (now - baseArrival) - (pts - basePts) * 1000L
    }
    fun queued(pts: Long) {
        val now = System.nanoTime()
        arrivals[pts] = Queued(if (pts == lastPts) lastArrival else now, now)
        while (arrivals.size > 512) arrivals.remove(arrivals.keys.first())
    }
    fun inputWait(ns: Long) { maxWait = maxOf(maxWait, ns) }
    fun output(pts: Long, releaseNs: Long) {
        val now = System.nanoTime()
        arrivals.remove(pts)?.let {
            val residence = now - it.arrival
            maxResidence = maxOf(maxResidence, residence)
            maxCodecResidence = maxOf(maxCodecResidence, now - it.queued)
            maxPreCodec = maxOf(maxPreCodec, it.queued - it.arrival)
            if (residenceSamples.size < 2048) residenceSamples.add(residence)
        }
        maxAhead = maxOf(maxAhead, releaseNs - now)
        outputs++
    }
    fun callbackFinished(start: Long) {
        val now = System.nanoTime()
        maxCallback = maxOf(maxCallback, now - start)
        if (now - lastReport < 1_000_000_000L) return
        residenceSamples.sort()
        fun percentile(percent: Int): Long = if (residenceSamples.isEmpty()) 0 else
            residenceSamples[((residenceSamples.size * percent + 99) / 100 - 1).coerceAtLeast(0)] / 1000
        Log.i("AIRPLAY_TIMING", "windowMs=${(now-lastReport)/1_000_000} outputs=$outputs pending=${arrivals.size} " +
            "maxInputWaitUs=${maxWait/1000} maxCallbackUs=${maxCallback/1000} " +
            "maxReceiverResidenceUs=${maxResidence/1000} maxScheduleAheadUs=${maxAhead/1000} arrivalDriftUs=${drift/1000} " +
            "matchedFrames=${residenceSamples.size} p50ReceiverUs=${percentile(50)} p95ReceiverUs=${percentile(95)} " +
            "maxCodecResidenceUs=${maxCodecResidence/1000} maxPreCodecUs=${maxPreCodec/1000}")
        lastReport = now
        maxWait = 0; maxCallback = 0; maxResidence = 0; maxAhead = 0; outputs = 0
        maxCodecResidence = 0; maxPreCodec = 0; residenceSamples.clear()
    }
    fun reset() {
        arrivals.clear(); basePts = Long.MIN_VALUE; drift = 0
        lastReport = System.nanoTime()
        maxWait = 0; maxCallback = 0; maxResidence = 0; maxAhead = 0; outputs = 0
        maxCodecResidence = 0; maxPreCodec = 0; residenceSamples.clear()
    }
}
