package io.github.jqssun.airplay.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import io.github.jqssun.airplay.MainActivity
import io.github.jqssun.airplay.Prefs
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.realDisplaySize
import io.github.jqssun.airplay.audio.DacpController
import io.github.jqssun.airplay.audio.DacpPlayer
import io.github.jqssun.airplay.audio.DmapParser
import io.github.jqssun.airplay.audio.TrackInfo
import io.github.jqssun.airplay.audio.VolumeBroadcast
import io.github.jqssun.airplay.bridge.LogListener
import io.github.jqssun.airplay.bridge.NativeBridge
import io.github.jqssun.airplay.bridge.RaopCallbackHandler
import io.github.jqssun.airplay.discovery.NsdServiceManager
import io.github.jqssun.airplay.download.VideoDownloader
import io.github.jqssun.airplay.playback.PlaybackHistoryManager
import io.github.jqssun.airplay.renderer.AirPlayVideoPlayer
import io.github.jqssun.airplay.renderer.AudioRenderer
import io.github.jqssun.airplay.renderer.VideoRenderer
import io.github.jqssun.airplay.dlna.CustomAVTransportService
import io.github.jqssun.airplay.dlna.DlnaUpnpManager
import org.fourthline.cling.model.ModelUtil
import org.fourthline.cling.model.types.UnsignedIntegerFourBytes
import org.fourthline.cling.support.model.MediaInfo
import org.fourthline.cling.support.model.PositionInfo
import org.fourthline.cling.support.model.StorageMedium
import org.fourthline.cling.support.model.TransportInfo
import org.fourthline.cling.support.model.TransportState
import org.fourthline.cling.support.model.TransportStatus
import io.github.jqssun.airplay.viewmodel.DebugInfo
import java.net.NetworkInterface
import java.security.SecureRandom
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class VideoPlaybackInfo(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playing: Boolean = true,
    val speed: Float = 1f,
    val skipSilence: Boolean = false,
    val buffering: Boolean = false,
)

class AirPlayService : LifecycleService(), RaopCallbackHandler, LogListener {

    private var nativeHandle = 0L
    private var nsdManager: NsdServiceManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var screenWakeLock: PowerManager.WakeLock? = null
    private var foregroundStarted = false
    private var lastOrientation = Configuration.ORIENTATION_UNDEFINED

    val videoRenderer = VideoRenderer(this)
    val audioRenderer = AudioRenderer()
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val airPlayVideoPlayer by lazy { AirPlayVideoPlayer(this) }
    val videoDownloader by lazy { VideoDownloader(this) }
    val playbackHistoryManager by lazy { PlaybackHistoryManager(this) }

    private var _lastDiskSaveTimeMs = 0L
    private var _currentVideoPositionMs = 0L
    private var _currentVideoDurationMs = 0L
    private var pendingDlnaStartPositionSeconds: Float = 0f

    private var dlnaManager: DlnaUpnpManager? = null
    private var currentDlnaUri: String? = null
    private var currentDlnaTitle: String? = null

    private fun _flushPlaybackProgress() {
        val uri = _videoLocation.value ?: return
        if (_currentVideoPositionMs > 0L) {
            playbackHistoryManager.saveProgress(uri, _currentVideoPositionMs, _currentVideoDurationMs)
        }
    }

    // hls urls point at the native httpd, valid only while the session lives
    private val _videoLocation = MutableStateFlow<String?>(null)
    val videoLocation = _videoLocation.asStateFlow()

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
    }
    private val _serverState = MutableStateFlow(ServerState.STOPPED)
    val serverState = _serverState.asStateFlow()

    private val _dlnaRunning = MutableStateFlow(false)
    val dlnaRunning = _dlnaRunning.asStateFlow()

    private val _connectionCount = MutableStateFlow(0)
    val connectionCount = _connectionCount.asStateFlow()

    private val _videoAspect = MutableStateFlow(16f / 9f)
    val videoAspect = _videoAspect.asStateFlow()

    private val _videoResolution = MutableStateFlow("")
    val videoResolution = _videoResolution.asStateFlow()

    private val _audioOnly = MutableStateFlow(false)
    val audioOnly = _audioOnly.asStateFlow()

    private val _videoPlaybackActive = MutableStateFlow(false)
    val videoPlaybackActive = _videoPlaybackActive.asStateFlow()

    private val _videoPlaybackInfo = MutableStateFlow(VideoPlaybackInfo())
    val videoPlaybackInfo = _videoPlaybackInfo.asStateFlow()

    // bumped per /play so the ui resets transport state on back-to-back plays too
    private val _videoPlaySeq = MutableStateFlow(0L)
    val videoPlaySeq = _videoPlaySeq.asStateFlow()

    private val _videoPlaybackAspect = MutableStateFlow(16f / 9f)
    val videoPlaybackAspect = _videoPlaybackAspect.asStateFlow()

    private val _videoPlaybackSize = MutableStateFlow<Pair<Int, Int>?>(null)
    val videoPlaybackSize = _videoPlaybackSize.asStateFlow()

    // container/manifest metadata
    private val _videoTitle = MutableStateFlow("")
    val videoTitle = _videoTitle.asStateFlow()

    // recent /playback-info polls with no playback = pending video; polls start ~1s before /play
    @Volatile private var _lastVideoPollAt = 0L
    @Volatile private var _videoPollSuppressed = false
    @Volatile private var _lastDlnaHeartbeatAt = 0L

    fun videoSessionPending(): Boolean =
        !_videoPlaybackActive.value && !_audioOnly.value && !_mirroringActive.value &&
            !_videoPollSuppressed &&
            SystemClock.elapsedRealtime() - _lastVideoPollAt < VIDEO_POLL_PENDING_TIMEOUT_MS

    // set once mirroring reports a real size; stops with session
    private val _mirroringActive = MutableStateFlow(false)
    val mirroringActive = _mirroringActive.asStateFlow()

    private val _trackInfo = MutableStateFlow(TrackInfo())
    val trackInfo = _trackInfo.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs = _durationMs.asStateFlow()

    private val _playing = MutableStateFlow(true)
    val playing = _playing.asStateFlow()

    @Volatile private var _progressBaseMs = 0L
    @Volatile private var _progressBaseTime = 0L

    fun currentPositionMs(): Long {
        if (_progressBaseTime == 0L || !_playing.value) return _positionMs.value
        val elapsed = SystemClock.elapsedRealtime() - _progressBaseTime
        return (_progressBaseMs + elapsed).coerceIn(0, _durationMs.value)
    }

    var dacpController: DacpController? = null
        private set
    lateinit var dacpPlayer: DacpPlayer
        private set
    private val _mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var _coverArtBytes: ByteArray? = null
    private var mediaSession: MediaSessionCompat? = null
    private var mediaReceiver: BroadcastReceiver? = null
    private var volumeReceiver: BroadcastReceiver? = null
    private var _pendingVolEchoes = 0
    // senders park volume at -30 as route closes; session must not leave device silenced
    private var _preZeroIdx = -1
    @Volatile private var _senderFrac = -1f
    private var _volSyncTarget = -1f
    private var _volSyncHint = 0
    private var _volSyncDir = 0
    private var _volSyncSteps = 0
    private val _volSyncTimeout = Runnable { _volSyncEnd() }

    var logCallback: ((String) -> Unit)? = null

    @Volatile private var _lastPin: String? = null
    var pinCallback: ((String?) -> Unit)? = null
        set(value) {
            field = value
            // ui replay only: binding the activity must not mint a new native pin
            value?.invoke(_lastPin)
        }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        logCallback?.invoke(msg)
    }

    override fun onLog(msg: String) {
        logCallback?.invoke(msg)
    }

    inner class LocalBinder : Binder() {
        val service: AirPlayService
            get() = this@AirPlayService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return LocalBinder()
    }

    private val dlnaCallback = object : CustomAVTransportService.DlnaPlaybackCallback {
        override fun onHeartbeat() {
            _lastDlnaHeartbeatAt = SystemClock.elapsedRealtime()
        }

        override fun onSetUri(uri: String, title: String?) {
            currentDlnaUri = uri
            currentDlnaTitle = title
            pendingDlnaStartPositionSeconds = 0f
            log("DLNA onSetUri: $uri ($title)")
        }

        override fun onPlay() {
            val uri = currentDlnaUri ?: return
            _mainHandler.post {
                if (_mirroringActive.value) {
                    videoRenderer.stopSession()
                    _mirroringActive.value = false
                }
                playDlnaVideo(uri, currentDlnaTitle, pendingDlnaStartPositionSeconds)
            }
        }

        override fun onPause() {
            _mainHandler.post {
                _flushPlaybackProgress()
                airPlayVideoPlayer.setRate(0f)
            }
        }

        override fun onStop() {
            _mainHandler.post {
                stopVideoPlayback()
            }
        }

        override fun onSeek(positionMs: Long) {
            _mainHandler.post {
                if (!_videoPlaybackActive.value) {
                    pendingDlnaStartPositionSeconds = positionMs / 1000f
                } else {
                    airPlayVideoPlayer.scrub(positionMs / 1000f)
                    _currentVideoPositionMs = positionMs
                    _flushPlaybackProgress()
                }
            }
        }

        override fun getPositionInfo(): PositionInfo {
            val posSec = (_videoPlaybackInfo.value.positionMs / 1000).coerceAtLeast(0)
            val durSec = (_videoPlaybackInfo.value.durationMs / 1000).coerceAtLeast(0)
            val posStr = ModelUtil.toTimeString(posSec)
            val durStr = ModelUtil.toTimeString(durSec)
            return PositionInfo(
                1,
                durStr,
                currentDlnaUri ?: "",
                posStr,
                posStr
            )
        }

        override fun getMediaInfo(): MediaInfo {
            val durSec = (_videoPlaybackInfo.value.durationMs / 1000).coerceAtLeast(0)
            val durStr = ModelUtil.toTimeString(durSec)
            return MediaInfo(
                currentDlnaUri ?: "",
                "",
                UnsignedIntegerFourBytes(1),
                durStr,
                StorageMedium.NETWORK
            )
        }

        override fun getTransportInfo(): TransportInfo {
            val state = if (_videoPlaybackActive.value) {
                if (_videoPlaybackInfo.value.playing) TransportState.PLAYING else TransportState.PAUSED_PLAYBACK
            } else {
                TransportState.STOPPED
            }
            return TransportInfo(state, TransportStatus.OK, "1")
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        dacpController = DacpController(this)
        dacpPlayer = DacpPlayer(
            mainLooper,
            dacp = { dacpController },
            snapshot = {
                DacpPlayer.Snapshot(
                    track = _trackInfo.value,
                    artworkData = _coverArtBytes,
                    durationMs = _durationMs.value,
                    playing = _playing.value,
                    active = _audioOnly.value && _connectionCount.value > 0,
                )
            },
            positionMs = ::currentPositionMs,
            setPlaying = ::_setPlaying,
        )
        mediaSession = MediaSessionCompat(this, "AirPlay").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    if (_videoPlaybackActive.value) {
                        airPlayVideoPlayer.setPlaying(true)
                        return
                    }
                    _setPlaying(true)
                    dacpController?.play()
                }
                override fun onPause() {
                    if (_videoPlaybackActive.value) {
                        airPlayVideoPlayer.setPlaying(false)
                        return
                    }
                    _setPlaying(false)
                    dacpController?.pause()
                }
                override fun onStop() {
                    if (_videoPlaybackActive.value) stopVideoPlayback()
                }
                override fun onFastForward() {
                    if (_videoPlaybackActive.value) airPlayVideoPlayer.seekBy(VIDEO_SEEK_STEP_MS)
                }
                override fun onRewind() {
                    if (_videoPlaybackActive.value) airPlayVideoPlayer.seekBy(-VIDEO_SEEK_STEP_MS)
                }
                override fun onSeekTo(pos: Long) {
                    if (_videoPlaybackActive.value) airPlayVideoPlayer.scrub(pos / 1000f)
                }
                override fun onSkipToNext() {
                    if (!_videoPlaybackActive.value) dacpController?.nextItem()
                }
                override fun onSkipToPrevious() {
                    if (!_videoPlaybackActive.value) dacpController?.prevItem()
                }
            })
        }
        mediaReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_PLAY_PAUSE -> togglePlayPause()
                    ACTION_NEXT -> dacpController?.nextItem()
                    ACTION_PREV -> dacpController?.prevItem()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_PLAY_PAUSE)
            addAction(ACTION_NEXT)
            addAction(ACTION_PREV)
        }
        ContextCompat.registerReceiver(this, mediaReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        volumeReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getIntExtra(VolumeBroadcast.EXTRA_STREAM_TYPE, -1) != AudioManager.STREAM_MUSIC) return
                val idx = intent.getIntExtra(VolumeBroadcast.EXTRA_VALUE, -1)
                val prev = intent.getIntExtra(VolumeBroadcast.EXTRA_PREV_VALUE, -1)
                if (idx < 0 || prev < 0 || idx == prev) return
                if (_pendingVolEchoes > 0) { _pendingVolEchoes--; return }
                if (_connectionCount.value == 0) return
                val target = idx.toFloat() / audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                Log.d(TAG, "local volume $prev -> $idx, sync sender to $target")
                _preZeroIdx = -1
                val active = _volSyncTarget >= 0f
                _volSyncTarget = target
                _volSyncHint = if (idx > prev) 1 else -1
                _volSyncDir = 0
                _volSyncSteps = 0
                if (!active) _volSyncStep()
            }
        }
        ContextCompat.registerReceiver(this, volumeReceiver, IntentFilter(VolumeBroadcast.ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED)

        airPlayVideoPlayer.onVideoSize = { width, height, aspect ->
            _videoPlaybackAspect.value = aspect
            _videoPlaybackSize.value = width to height
        }
        airPlayVideoPlayer.onEnded = { isFinished ->
            _endVideoPlayback(
                if (isFinished) "AirPlay Video stopped (finished)" else "AirPlay Video stopped (error)",
                isFinished = isFinished
            )
        }
        airPlayVideoPlayer.onPlaybackInfo = { snapshot ->
            if (nativeHandle != 0L) {
                NativeBridge.nativeUpdatePlaybackInfo(nativeHandle, snapshot.position, snapshot.duration, snapshot.rate, snapshot.ready)
            }
            if (_videoPlaybackActive.value) {
                _updateVideoPlaybackState(snapshot.position, snapshot.rate)
                _videoPlaybackInfo.value = VideoPlaybackInfo(
                    positionMs = (snapshot.position * 1000).toLong(),
                    durationMs = if (snapshot.duration > 0f) (snapshot.duration * 1000).toLong() else 0L,
                    playing = snapshot.playWhenReady,
                    speed = snapshot.speed,
                    skipSilence = snapshot.skipSilence,
                    buffering = snapshot.buffering,
                )

                val uri = _videoLocation.value
                if (!uri.isNullOrBlank() && !snapshot.isLive) {
                    val posMs = (snapshot.position * 1000f).toLong()
                    val durMs = if (snapshot.duration > 0f) (snapshot.duration * 1000f).toLong() else 0L
                    _currentVideoPositionMs = posMs
                    _currentVideoDurationMs = durMs

                    val now = SystemClock.elapsedRealtime()
                    if (snapshot.playWhenReady && now - _lastDiskSaveTimeMs >= 5000L) {
                        _lastDiskSaveTimeMs = now
                        playbackHistoryManager.saveProgress(uri, posMs, durMs)
                    }
                }
            }
        }
        lifecycleScope.launch {
            prefs.audioConfigFlow()
                .debounce(AUDIO_CONFIG_DEBOUNCE_MS)
                .collect { audioRenderer.updateConfig(it) }
        }
        lifecycleScope.launch {
            combine(_videoPlaybackActive, _mirroringActive) { video, mirroring ->
                video || mirroring
            }.collect { active ->
                _updateScreenWakeLock(active)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_SERVER) {
            promoteToForeground()
            val name = prefs.getString(Prefs.SERVER_NAME, Prefs.DEF_SERVER_NAME) ?: Prefs.DEF_SERVER_NAME
            startServer(name, ensureServiceStarted = false)
            if (_serverState.value != ServerState.RUNNING) stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    fun startServer(name: String) {
        startServer(name, ensureServiceStarted = true)
    }

    private fun startServer(name: String, ensureServiceStarted: Boolean) {
        if (_serverState.value == ServerState.RUNNING) return
        val effectiveName = name.ifBlank { Prefs.DEF_SERVER_NAME }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "airplay:server").apply { acquire() }

        nsdManager = NsdServiceManager(this).apply { acquireMulticastLock() }

        val hwAddr = getHwAddr()
        val keyFile = filesDir.resolve("airplay.pem").absolutePath
        val nohold = prefs.getBoolean(Prefs.ALLOW_NEW_CONN, Prefs.DEF_ALLOW_NEW_CONN)
        val requirePin = prefs.getBoolean(Prefs.REQUIRE_PIN, Prefs.DEF_REQUIRE_PIN)

        // oboe's OpenSL ES backend (pre-AAudio devices, API < 27) can't discover native
        // rate / burst size itself; feed it AudioManager values so low-latency buffer
        // sizing works there
        // see: https://github.com/google/oboe/blob/main/docs/GettingStarted.md#obtaining-optimal-latency
        NativeBridge.nativeSetDefaultStreamValues(
            audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0,
            audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0
        )
        nativeHandle = NativeBridge.nativeInit(this, hwAddr, effectiveName, keyFile, nohold, requirePin)
        if (nativeHandle == 0L) {
            log("Native init failed")
            _failStart()
            return
        }
        audioRenderer.attachEngine(nativeHandle)

        // apply settings from preferences
        val maxFps = prefs.getInt(Prefs.MAX_FPS, Prefs.DEF_MAX_FPS)
        val overscanned = prefs.getBoolean(Prefs.OVERSCANNED, Prefs.DEF_OVERSCANNED)
        val audioLatencyMs = prefs.getInt(Prefs.AUDIO_LATENCY_MS, Prefs.DEF_AUDIO_LATENCY_MS)
        val (reqW, reqH) = _displaySize(clamp = false)
        val h265 = videoRenderer.selectDecoders(reqW, reqH, maxFps, prefs.getBoolean(Prefs.H265_ENABLED, Prefs.DEF_H265_ENABLED))
        val alac = prefs.getBoolean(Prefs.ALAC_ENABLED, Prefs.DEF_ALAC_ENABLED)
        val aac = prefs.getBoolean(Prefs.AAC_ENABLED, Prefs.DEF_AAC_ENABLED)

        videoRenderer.enforceSdr = prefs.getBoolean(Prefs.ENFORCE_SDR, Prefs.DEF_ENFORCE_SDR)
        videoRenderer.keyAllowFrameDrop = prefs.getBoolean(Prefs.KEY_ALLOW_FRAME_DROP, Prefs.DEF_KEY_ALLOW_FRAME_DROP)
        videoRenderer.selector.maxOperatingRate = when (prefs.getString(Prefs.OPERATING_RATE, Prefs.DEF_OPERATING_RATE)) {
            Prefs.ON -> true; Prefs.OFF -> false; else -> null
        }
        videoRenderer.benchmarkLog = prefs.getBoolean(Prefs.BENCHMARK_LOG, Prefs.DEF_BENCHMARK_LOG)
        videoRenderer.benchmarkLogCallback = { msg -> logCallback?.invoke(msg) }
        videoRenderer.scheduledOutputBufferRelease = prefs.getBoolean(Prefs.SCHEDULED_OUTPUT_BUFFER_RELEASE, Prefs.DEF_SCHEDULED_OUTPUT_BUFFER_RELEASE)
        NativeBridge.nativeSetH265Enabled(nativeHandle, h265)
        NativeBridge.nativeSetCodecs(nativeHandle, alac, aac)
        val advertiseVideo = prefs.getBoolean(Prefs.ADVERTISE_VIDEO, Prefs.DEF_ADVERTISE_VIDEO)
        val advertiseAudio = prefs.getBoolean(Prefs.ADVERTISE_AUDIO, Prefs.DEF_ADVERTISE_AUDIO)
        NativeBridge.nativeSetHlsEnabled(nativeHandle, advertiseVideo)
        NativeBridge.nativeSetLang(nativeHandle, "", "", resources.configuration.locales.toLanguageTags().replace(',', ':'))
        NativeBridge.nativeSetAudioEnabled(nativeHandle, advertiseAudio)
        NativeBridge.nativeSetPlist(nativeHandle, "maxFPS", maxFps)
        NativeBridge.nativeSetPlist(nativeHandle, "overscanned", if (overscanned) 1 else 0)
        if (audioLatencyMs >= 0) {
            NativeBridge.nativeSetPlist(nativeHandle, "audio_delay_micros", audioLatencyMs * 1000)
        }

        // set display params
        lastOrientation = resources.configuration.orientation
        val (w, h) = _displaySize()
        videoRenderer.setResolution(w, h)
        _videoResolution.value = "${w}x${h}"
        _videoAspect.value = w.toFloat() / h
        NativeBridge.nativeSetDisplaySize(nativeHandle, w, h, maxFps)

        val requestedPort = prefs.getInt(Prefs.SERVER_PORT, Prefs.DEF_SERVER_PORT).coerceIn(1, 65535)
        val port = NativeBridge.nativeStart(nativeHandle, requestedPort)
        if (port < 0) {
            log("Failed to start on port $requestedPort")
            _failStart()
            return
        }

        // register mdns services
        val raopTxt = NativeBridge.nativeGetRaopTxtRecords(nativeHandle) ?: emptyMap()
        val airplayTxt = NativeBridge.nativeGetAirplayTxtRecords(nativeHandle) ?: emptyMap()
        val raopName = NativeBridge.nativeGetRaopServiceName(nativeHandle) ?: "AirPlay"
        val resolvedName = NativeBridge.nativeGetServerName(nativeHandle) ?: effectiveName

        if (prefs.getBoolean(Prefs.ADVERTISE_AUDIO, Prefs.DEF_ADVERTISE_AUDIO)) {
            nsdManager?.registerRaop(raopName, port, raopTxt)
        }
        nsdManager?.registerAirplay(resolvedName, port, airplayTxt)

        _serverState.value = ServerState.RUNNING
        if (ensureServiceStarted) {
            ContextCompat.startForegroundService(this, Intent(this, AirPlayService::class.java))
        }
        promoteToForeground()
        dlnaManager = DlnaUpnpManager(this, dlnaCallback).apply { start(resolvedName) }
        _dlnaRunning.value = dlnaManager?.isRunning == true
        log("Server started on port $port")
    }

    private fun _orientationFollowsDevice(): Boolean =
        prefs.getString(Prefs.RESOLUTION, Prefs.DEF_RESOLUTION) == Prefs.AUTO

    private fun _displaySize(clamp: Boolean = true): Pair<Int, Int> {
        val res = prefs.getString(Prefs.RESOLUTION, Prefs.DEF_RESOLUTION)!!
        val portrait = when (res) {
            "portrait" -> true
            "landscape" -> false
            else -> resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        }
        val (rawW, rawH) = realDisplaySize()
        val device = if (portrait != rawH >= rawW) rawH to rawW else rawW to rawH
        if (res == "portrait" || res == "landscape") return device
        val (w, h) = if (res.contains("x")) {
            res.split("x").let { it[0].toInt() to it[1].toInt() }
        } else device
        if (!clamp) return w to h
        // strict decoders black-screen past their limits; advertised size is only upper bound for senders
        val (maxW, maxH) = videoRenderer.maxResolution()
        return w.coerceAtMost(maxW) to h.coerceAtMost(maxH)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation == lastOrientation) return
        lastOrientation = newConfig.orientation
        if (nativeHandle == 0L || _serverState.value != ServerState.RUNNING) return
        if (!_orientationFollowsDevice()) return
        val (w, h) = _displaySize()
        NativeBridge.nativeSetDisplaySize(nativeHandle, w, h, prefs.getInt(Prefs.MAX_FPS, Prefs.DEF_MAX_FPS))
        log("Advertising ${w}x${h} from next session")
    }

    private fun _failStart() {
        audioRenderer.detachEngine()
        if (nativeHandle != 0L) {
            NativeBridge.nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
        nsdManager?.release()
        nsdManager = null
        wakeLock?.release()
        wakeLock = null
        dlnaManager?.stop()
        dlnaManager = null
        _dlnaRunning.value = false
        _serverState.value = ServerState.ERROR
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
    }

    fun stopServer() {
        dlnaManager?.stop()
        dlnaManager = null
        _dlnaRunning.value = false
        audioRenderer.detachEngine()
        if (nativeHandle != 0L) {
            NativeBridge.nativeStop(nativeHandle)
            NativeBridge.nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
        dacpController?.reset()
        nsdManager?.release()
        nsdManager = null
        wakeLock?.release()
        wakeLock = null
        videoRenderer.release()
        airPlayVideoPlayer.stop()
        mediaSession?.isActive = false
        _audioOnly.value = false
        _videoPlaybackActive.value = false
        _mirroringActive.value = false
        _videoPlaybackInfo.value = VideoPlaybackInfo()
        _lastVideoPollAt = 0
        _videoPollSuppressed = false
        _coverArtBytes = null
        _trackInfo.value = TrackInfo()
        _positionMs.value = 0
        _durationMs.value = 0
        _serverState.value = ServerState.STOPPED
        _connectionCount.value = 0
        _refreshDacpPlayer()
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        stopSelf()
        log("Server stopped")
    }

    fun setVideoSurface(surface: Surface) {
        videoRenderer.setSurface(surface)
    }

    fun clearVideoSurface(surface: Surface) {
        videoRenderer.clearSurface(surface)
    }

    fun setVideoPlaybackSurface(surface: Surface) {
        airPlayVideoPlayer.setSurface(surface)
    }

    fun clearVideoPlaybackSurface(surface: Surface) {
        airPlayVideoPlayer.clearSurface(surface)
    }

    fun setVideoPlaying(playing: Boolean) {
        airPlayVideoPlayer.setPlaying(playing)
        if (!playing) {
            _flushPlaybackProgress()
        }
    }

    fun seekVideoTo(positionMs: Long) {
        airPlayVideoPlayer.scrub(positionMs / 1000f)
        _currentVideoPositionMs = positionMs
        _flushPlaybackProgress()
    }

    fun setVideoScrubbing(enabled: Boolean) {
        airPlayVideoPlayer.setScrubbing(enabled)
    }

    fun setVideoSpeed(speed: Float) {
        airPlayVideoPlayer.setSpeed(speed)
    }

    fun setVideoSkipSilence(enabled: Boolean) {
        airPlayVideoPlayer.setSkipSilence(enabled)
    }

    fun seekVideoBy(deltaMs: Long) {
        airPlayVideoPlayer.seekBy(deltaMs)
    }

    fun stopVideoPlayback() = _endVideoPlayback("AirPlay Video stopped (local)")

    fun playDlnaVideo(uri: String, title: String? = null, startPositionSeconds: Float = 0f) {
        if (shouldLaunchOnConnect()) launchMainActivity()
        _lastDlnaHeartbeatAt = SystemClock.elapsedRealtime()
        _videoLocation.value = uri
        _videoPlaySeq.value++
        _videoPollSuppressed = false

        val savedMs = playbackHistoryManager.getProgressMs(uri)
        val castMs = (startPositionSeconds * 1000f).toLong()
        val effectiveStartSeconds = if (savedMs != null && savedMs > castMs) {
            log("DLNA Video: using saved progress ${savedMs / 1000f}s (cast: ${startPositionSeconds}s)")
            savedMs / 1000f
        } else {
            log("DLNA Video: using cast progress ${startPositionSeconds}s (saved: ${savedMs?.let { it / 1000f } ?: "none"})")
            startPositionSeconds
        }

        _currentVideoPositionMs = (effectiveStartSeconds * 1000f).toLong()
        _currentVideoDurationMs = 0L
        _lastDiskSaveTimeMs = SystemClock.elapsedRealtime()

        _videoPlaybackInfo.value = VideoPlaybackInfo(positionMs = _currentVideoPositionMs)
        _videoPlaybackAspect.value = 16f / 9f
        _videoPlaybackSize.value = null
        _videoTitle.value = title ?: "DLNA 投屏"
        _videoPlaybackActive.value = true
        airPlayVideoPlayer.play(uri, effectiveStartSeconds)
        mediaSession?.isActive = true
        log("DLNA Video play: $uri ($title) @ ${effectiveStartSeconds}s")
    }

    fun downloadVideo() {
        _videoLocation.value?.let { videoDownloader.start(it) }
    }

    private fun _endVideoPlayback(message: String, isFinished: Boolean = false) {
        if (!_videoPlaybackActive.value) return
        val currentUri = _videoLocation.value
        if (currentUri != null) {
            if (isFinished) {
                playbackHistoryManager.clearProgress(currentUri)
            } else {
                _flushPlaybackProgress()
            }
        }
        // lingering polls after a stop must not bounce the UI back to a pending session
        _videoPollSuppressed = true
        _videoPlaybackActive.value = false
        airPlayVideoPlayer.stop()
        if (!_audioOnly.value) mediaSession?.isActive = false
        log(message)
    }

    override fun onDestroy() {
        _flushPlaybackProgress()
        stopServer()
        dacpPlayer.release()
        mediaReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        mediaReceiver = null
        volumeReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        volumeReceiver = null
        dacpController?.release()
        dacpController = null
        mediaSession?.release()
        mediaSession = null
        _updateScreenWakeLock(false)
        super.onDestroy()
    }

    // RaopCallbackHandler (called from native threads)

    override fun onVideoData(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        videoRenderer.feedFrame(data, ntpTimeNs, isH265)
    }

    override fun onVideoSessionPoll() {
        _lastVideoPollAt = SystemClock.elapsedRealtime()
    }

    override fun onVideoPlay(location: String, startPositionSeconds: Float) {
        if (shouldLaunchOnConnect()) launchMainActivity()
        _lastVideoPollAt = SystemClock.elapsedRealtime()
        _videoLocation.value = location
        _videoPlaySeq.value++
        _videoPollSuppressed = false

        val savedMs = playbackHistoryManager.getProgressMs(location)
        val castMs = (startPositionSeconds * 1000f).toLong()
        val effectiveStartSeconds = if (savedMs != null && savedMs > castMs) {
            log("AirPlay Video: using saved progress ${savedMs / 1000f}s (cast: ${startPositionSeconds}s)")
            savedMs / 1000f
        } else {
            log("AirPlay Video: using cast progress ${startPositionSeconds}s (saved: ${savedMs?.let { it / 1000f } ?: "none"})")
            startPositionSeconds
        }

        _currentVideoPositionMs = (effectiveStartSeconds * 1000f).toLong()
        _currentVideoDurationMs = 0L
        _lastDiskSaveTimeMs = SystemClock.elapsedRealtime()

        _videoPlaybackInfo.value = VideoPlaybackInfo(positionMs = _currentVideoPositionMs)
        _videoPlaybackAspect.value = 16f / 9f
        _videoPlaybackSize.value = null
        _videoTitle.value = ""
        _videoPlaybackActive.value = true
        airPlayVideoPlayer.play(location, effectiveStartSeconds)
        // claim media-button routing for keys that arrive as media-session events
        mediaSession?.isActive = true
        log("AirPlay Video play: $location @ ${effectiveStartSeconds}s")
    }

    override fun onVideoScrub(positionSeconds: Float) {
        airPlayVideoPlayer.scrub(positionSeconds)
        _currentVideoPositionMs = (positionSeconds * 1000f).toLong()
        _flushPlaybackProgress()
    }

    override fun onVideoRate(rate: Float) {
        airPlayVideoPlayer.setRate(rate)
        if (rate <= 0f) {
            _flushPlaybackProgress()
        }
    }

    override fun onVideoStop() = _endVideoPlayback("AirPlay Video stopped")

    override fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean) {
        clearPin()
        audioRenderer.start()
        audioRenderer.setFormat(ct, spf)
        if (!usingScreen) _setPlaying(true)
        if (!usingScreen && !_audioOnly.value) {
            // pure music streaming (not screen mirroring audio)
            _setAudioOnly(true)
        }
        log("Audio format: ct=$ct spf=$spf screen=$usingScreen")
    }

    override fun onVideoSize(srcW: Float, srcH: Float, w: Float, h: Float) {
        clearPin()
        if (w > 0 && h > 0) {
            _videoAspect.value = w / h
            _videoResolution.value = "${w.toInt()}x${h.toInt()}"
            videoRenderer.setResolution(w.toInt(), h.toInt())
            _mirroringActive.value = true
            if (shouldLaunchOnConnect()) launchMainActivity()
        }
        log("Video size: ${srcW}x${srcH} -> ${w}x${h}")
    }

    override fun onVolumeChange(volume: Float) {
        val frac = if (volume <= -144f) 0f else ((volume + 30f) / 30f).coerceIn(0f, 1f)
        _senderFrac = frac
        Log.d(TAG, "volume ${volume}dB, frac $frac")
        _mainHandler.post {
            if (_volSyncTarget >= 0f) {
                _volSyncStep()
                return@post
            }
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val idx = (frac * max).roundToInt()
            _preZeroIdx = if (idx == 0) (if (_preZeroIdx < 0) cur else _preZeroIdx) else -1
            if (idx != cur) {
                _pendingVolEchoes++
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, idx, 0)
            }
        }
    }

    override fun onClientVolume(): Float {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val vol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val db = if (vol == 0) -144f else -30f + 30f * vol / max
        Log.d(TAG, "client volume query: $vol/$max -> ${db}dB")
        return db
    }

    private fun _volSyncStep() {
        _mainHandler.removeCallbacks(_volSyncTimeout)
        val target = _volSyncTarget
        if (target < 0f) return
        val frac = _senderFrac
        val want = when {
            frac < 0f -> _volSyncHint
            abs(target - frac) <= VOL_SYNC_EPS -> 0
            target > frac -> 1
            else -> -1
        }
        if (want == 0 || (_volSyncDir != 0 && want != _volSyncDir) || _volSyncSteps >= VOL_SYNC_MAX_STEPS) {
            _volSyncEnd()
            return
        }
        _volSyncDir = want
        _volSyncSteps++
        if (want > 0) dacpController?.volumeUp() else dacpController?.volumeDown()
        _mainHandler.postDelayed(_volSyncTimeout, VOL_SYNC_TIMEOUT_MS)
    }

    private fun _volSyncEnd() {
        _mainHandler.removeCallbacks(_volSyncTimeout)
        if (_volSyncTarget >= 0f) Log.d(TAG, "volume sync done: sender $_senderFrac, target $_volSyncTarget")
        _volSyncTarget = -1f
        _volSyncDir = 0
    }

    override fun onConnectionInit() {
        val firstConnection = _connectionCount.value == 0
        _connectionCount.value++
        log("Client connected (${_connectionCount.value})")
        if (!firstConnection) return
        // conn_init is only a tcp pre-auth signal. pin-required sessions must wait for
        // onDisplayPin, otherwise the server ui can move before the client pin is current
        if (requiresPin()) return
        if (!shouldLaunchOnConnect()) return
        launchMainActivity()
    }

    override fun onConnectionDestroy() {
        _connectionCount.value = (_connectionCount.value - 1).coerceAtLeast(0)
        if (_connectionCount.value == 0) {
            if (_mirroringActive.value) {
                videoRenderer.stopSession()
                _mirroringActive.value = false
            }
            // clients may drop without POST /stop; must run before the poll-state reset
            _endVideoPlayback("AirPlay Video stopped (disconnect)")
            // last client gone: release audio output devices to save power
            audioRenderer.stop()
            _audioOnly.value = false
            _lastVideoPollAt = 0
            _videoPollSuppressed = false
            _coverArtBytes = null
            _trackInfo.value = TrackInfo()
            _positionMs.value = 0
            _durationMs.value = 0
            dacpController?.reset()
            _senderFrac = -1f
            _mainHandler.post {
                _volSyncEnd()
                if (_preZeroIdx >= 0) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, _preZeroIdx, 0)
                    _preZeroIdx = -1
                }
            }
            mediaSession?.isActive = false
            _refreshDacpPlayer()
            _updateMediaNotification()
        }
        log("Client disconnected (${_connectionCount.value})")
    }

    override fun onConnectionReset(reason: Int) {
        log("Connection reset: $reason")
        if (_mirroringActive.value) {
            videoRenderer.stopSession()
            _mirroringActive.value = false
        }
        if (_videoPlaybackActive.value) {
            _endVideoPlayback("AirPlay Video stopped (reset)")
        }
    }

    override fun onDisplayPin(pin: String) {
        // a new pin is the sync point with the client prompt: show every new value immediately
        if (_lastPin == pin) return
        _lastPin = pin
        pinCallback?.invoke(pin)
        _updateMediaNotification()
        if (shouldLaunchOnConnect()) launchMainActivity()
    }

    override fun onMetadata(data: ByteArray) {
        val map = DmapParser.parse(data)
        val info = TrackInfo.fromDmap(map, _trackInfo.value.coverArt)
        _trackInfo.value = info
        if (info.durationMs > 0) _durationMs.value = info.durationMs
        _updateMediaMetadata()
        _refreshDacpPlayer()
        log("Track: ${info.artist} - ${info.title}")
    }

    override fun onCoverArt(data: ByteArray) {
        val bmp = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return
        _coverArtBytes = data
        _trackInfo.value = _trackInfo.value.copy(coverArt = bmp)
        _updateMediaMetadata()
        _refreshDacpPlayer()
    }

    override fun onProgress(start: Long, curr: Long, end: Long) {
        val rate = 44100.0
        val posMs = ((curr - start) / rate * 1000).toLong().coerceAtLeast(0)
        val durMs = ((end - start) / rate * 1000).toLong().coerceAtLeast(0)
        // pause/resume transitions emit degenerate progress; keep the last good value
        if (durMs <= 0) return
        _positionMs.value = posMs
        _durationMs.value = durMs
        _progressBaseMs = posMs
        _progressBaseTime = SystemClock.elapsedRealtime()
        _playing.value = true
        _updatePlaybackState()
        _refreshDacpPlayer()
    }

    override fun onAudioTeardown() {
        _setPlaying(false)
    }

    override fun onDacpId(dacpId: String, activeRemote: String) {
        dacpController?.update(dacpId, activeRemote)
        log("DACP: $dacpId")
    }

    override fun onMirrorRunning(running: Boolean) {
        if (running) {
            if (_videoPlaybackActive.value) {
                stopVideoPlayback()
            }
            videoRenderer.startSession()
        } else {
            videoRenderer.stopSession()
            _mirroringActive.value = false
        }
        _setAudioOnly(!running)
    }

    private fun _setAudioOnly(audioOnly: Boolean) {
        val prev = _audioOnly.value
        _audioOnly.value = audioOnly
        _refreshDacpPlayer()
        if (audioOnly && !prev) {
            mediaSession?.isActive = true
            log("Audio mode")
        } else if (!audioOnly && prev) {
            mediaSession?.isActive = false
            _coverArtBytes = null
            _trackInfo.value = TrackInfo()
            _positionMs.value = 0
            _durationMs.value = 0
            _updateMediaNotification()
            log("Mirror mode")
        }
    }

    private fun _updateMediaMetadata() {
        val info = _trackInfo.value
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, info.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, info.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, info.album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, _durationMs.value)
        info.coverArt?.let { builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
        mediaSession?.setMetadata(builder.build())
        _updateMediaNotification()
    }

    fun togglePlayPause() {
        val nowPlaying = !_playing.value
        _setPlaying(nowPlaying)
        dacpController?.let { if (nowPlaying) it.play() else it.pause() }
    }

    private fun _setPlaying(playing: Boolean) {
        _playing.value = playing
        _refreshDacpPlayer()
        if (playing) {
            // resume extrapolation from current position
            _progressBaseMs = _positionMs.value
            _progressBaseTime = SystemClock.elapsedRealtime()
        } else {
            // freeze position
            _positionMs.value = currentPositionMs()
            _progressBaseTime = 0
        }
        _updatePlaybackState()
    }

    private fun _updatePlaybackState() {
        // the sender's silent raop audio session must not overwrite video session state
        if (_videoPlaybackActive.value) return
        val isPlaying = _playing.value
        val pbState = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val speed = if (isPlaying) 1f else 0f
        val state = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            )
            .setState(pbState, _positionMs.value, speed, SystemClock.elapsedRealtime())
            .build()
        mediaSession?.setPlaybackState(state)
        _updateMediaNotification()
    }

    // consumers extrapolate position from (position, speed, updateTime): push only discontinuities
    private fun _updateVideoPlaybackState(positionSeconds: Float, rate: Float) {
        val playing = rate > 0f
        val posMs = (positionSeconds * 1000).toLong()
        val now = SystemClock.elapsedRealtime()
        val expectedMs = _lastVideoStatePosMs +
            if (_lastVideoStateRate > 0f) ((now - _lastVideoStateAtMs) * _lastVideoStateRate).toLong() else 0L
        if (rate == _lastVideoStateRate && abs(posMs - expectedMs) < 1000) return
        _lastVideoStateRate = rate
        _lastVideoStatePosMs = posMs
        _lastVideoStateAtMs = now
        val state = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_FAST_FORWARD or
                    PlaybackStateCompat.ACTION_REWIND or
                    PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(
                if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                posMs,
                rate,
                now
            )
            .build()
        mediaSession?.setPlaybackState(state)
    }

    private var _lastVideoStateRate = -1f
    private var _lastVideoStatePosMs = 0L
    private var _lastVideoStateAtMs = 0L

    private fun _refreshDacpPlayer() {
        _mainHandler.post { dacpPlayer.refresh() }
    }

    private fun clearPin() {
        _lastPin = null
        pinCallback?.invoke(null)
        _updateMediaNotification()
    }

    fun collectDebugInfo() = DebugInfo(
        videoCodec = videoRenderer.codecName,
        videoRes = _videoResolution.value,
        videoFps = videoRenderer.fps,
        videoBitrate = videoRenderer.bitrateBps,
        videoFrames = videoRenderer.frameCount,
        droppedFrames = videoRenderer.droppedFrames,
        framePacingJitterUs = videoRenderer.framePacingJitterUs,
        audioCodec = audioRenderer.codecLabel,
        audioVolume = 100 * audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) /
            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
        audio = audioRenderer.audioDebug(),
        connections = _connectionCount.value,
    )

    // helpers

    private fun getHwAddr(): ByteArray {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (iface in interfaces) {
                if (iface.name.startsWith("wlan") || iface.name.startsWith("eth")) {
                    val mac = iface.hardwareAddress
                    if (isUsableMac(mac)) return mac!!
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get hardware address", e)
        }

        // fall back to stable per-install random address
        return persistedRandomMac()
            ?: byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte())
    }

    private fun isUsableMac(mac: ByteArray?): Boolean =
        mac != null && mac.size == 6 &&
            mac.any { it != 0.toByte() } &&
            !(mac[0] == 0x02.toByte() && mac.drop(1).all { it == 0.toByte() })

    private fun persistedRandomMac(): ByteArray? {
        macFromString(prefs.getString(Prefs.FALLBACK_MAC_ADDRESS, null))
            ?.takeIf { isUsableMac(it) }?.let { return it }
        repeat(10) {
            val mac = randomAaiMac()
            if (isUsableMac(mac)) {
                prefs.edit().putString(Prefs.FALLBACK_MAC_ADDRESS, macToString(mac)).apply()
                return mac
            }
        }
        return null
    }

    // random locally-administered unicast MAC in AAI SLAP quadrant
    private fun randomAaiMac(): ByteArray {
        val mac = ByteArray(6).also { SecureRandom().nextBytes(it) }
        mac[0] = ((mac[0].toInt() and 0xF0) or 0x0A).toByte()
        return mac
    }

    private fun macToString(mac: ByteArray): String = mac.joinToString(":") { "%02x".format(it) }

    private fun macFromString(s: String?): ByteArray? {
        if (s == null) return null
        return try {
            s.split(":").map { it.toInt(16).toByte() }.toByteArray()
        } catch (e: Exception) { null }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return _buildMediaNotification()
    }

    private fun promoteToForeground() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
        foregroundStarted = true
    }

    private fun requiresPin(): Boolean {
        return prefs.getBoolean(Prefs.REQUIRE_PIN, Prefs.DEF_REQUIRE_PIN)
    }

    private fun shouldLaunchOnConnect(): Boolean {
        return prefs.getBoolean(Prefs.LAUNCH_ON_CONNECT, Prefs.DEF_LAUNCH_ON_CONNECT)
    }

    private fun _buildMediaNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val info = _trackInfo.value
        val isAudio = _audioOnly.value && info.title.isNotEmpty()

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .setOngoing(true)

        if (isAudio) {
            builder.setContentTitle(info.title).setContentText(info.artist).setSubText(info.album)
            info.coverArt?.let { builder.setLargeIcon(it) }
            mediaSession?.sessionToken?.let { token ->
                builder.setStyle(
                    MediaNotificationCompat.MediaStyle()
                        .setMediaSession(token)
                        .setShowActionsInCompactView(0, 1, 2)
                )
                // transport action buttons
                builder.addAction(android.R.drawable.ic_media_previous, "Prev", _mediaAction(ACTION_PREV))
                builder.addAction(android.R.drawable.ic_media_pause, "Pause", _mediaAction(ACTION_PLAY_PAUSE))
                builder.addAction(android.R.drawable.ic_media_next, "Next", _mediaAction(ACTION_NEXT))
            }
        } else {
            if (_lastPin != null) {
                // passive handoff only: do not launch/reorder the activity during pin auth
                builder.setContentTitle(getString(R.string.notification_pin_title))
                    .setContentText(getString(R.string.notification_pin_text, _lastPin))
            } else {
                builder.setContentTitle(getString(R.string.notification_title))
                    .setContentText(getString(R.string.notification_text))
            }
        }
        return builder.build()
    }

    private fun launchMainActivity() {
        Handler(Looper.getMainLooper()).post {
            val launchIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            // 1. Direct startActivity (succeeds when SYSTEM_ALERT_WINDOW is granted or app is exempt)
            try {
                startActivity(launchIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Direct startActivity failed", e)
            }

            // 2. Full-Screen Intent via High-Priority Notification for background wake up
            try {
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (nm.getNotificationChannel(FSI_CHANNEL_ID) == null) {
                        val channel = NotificationChannel(
                            FSI_CHANNEL_ID,
                            "AirPlay Casting Wakeup",
                            NotificationManager.IMPORTANCE_HIGH
                        ).apply {
                            description = "后台投屏呼叫与全屏唤醒"
                            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                        }
                        nm.createNotificationChannel(channel)
                    }
                }

                val fsiPendingIntent = PendingIntent.getActivity(
                    this,
                    FSI_REQUEST_CODE,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val fsiNotification = NotificationCompat.Builder(this, FSI_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle("投屏已连接")
                    .setContentText("正在唤起投屏画面...")
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setFullScreenIntent(fsiPendingIntent, true)
                    .setAutoCancel(true)
                    .build()

                nm.notify(FSI_NOTIFICATION_ID, fsiNotification)
                try {
                    fsiPendingIntent.send()
                } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.w(TAG, "FullScreenIntent launch failed", e)
            }
        }
    }

    private fun _mediaAction(action: String): PendingIntent {
        val intent = Intent(action).setPackage(packageName)
        return PendingIntent.getBroadcast(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun _updateMediaNotification() {
        if (_serverState.value != ServerState.RUNNING) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, _buildMediaNotification())
    }

    private fun _updateScreenWakeLock(active: Boolean) {
        _mainHandler.post {
            if (active) {
                if (screenWakeLock == null) {
                    try {
                        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                        @Suppress("DEPRECATION")
                        screenWakeLock = pm.newWakeLock(
                            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
                            "airplay:screen_playback"
                        ).apply {
                            setReferenceCounted(false)
                            acquire()
                        }
                        log("Screen wake lock acquired for playback")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to acquire screen wake lock", e)
                    }
                }
            } else {
                screenWakeLock?.let {
                    try {
                        if (it.isHeld) it.release()
                        log("Screen wake lock released")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to release screen wake lock", e)
                    }
                }
                screenWakeLock = null
            }
        }
    }

    enum class ServerState {
        STOPPED,
        RUNNING,
        ERROR
    }

    companion object {
        private const val TAG = "AirPlayService"
        private const val VOL_SYNC_EPS = 0.033f
        private const val VOL_SYNC_MAX_STEPS = 32
        private const val VOL_SYNC_TIMEOUT_MS = 800L
        private const val CHANNEL_ID = "airplay_service"
        private const val NOTIFICATION_ID = 1
        const val FSI_CHANNEL_ID = "airplay_fsi"
        const val FSI_NOTIFICATION_ID = 2
        const val FSI_REQUEST_CODE = 1001
        const val ACTION_PLAY_PAUSE = "io.github.jqssun.airplay.PLAY_PAUSE"
        const val ACTION_NEXT = "io.github.jqssun.airplay.NEXT"
        const val ACTION_PREV = "io.github.jqssun.airplay.PREV"
        const val ACTION_START_SERVER = "io.github.jqssun.airplay.START_SERVER"
        // shared with dpad/double-tap seeks
        const val VIDEO_SEEK_STEP_MS = 10_000L
        const val VIDEO_POLL_PENDING_TIMEOUT_MS = 3_000L

        private const val AUDIO_CONFIG_DEBOUNCE_MS = 500L
    }
}
