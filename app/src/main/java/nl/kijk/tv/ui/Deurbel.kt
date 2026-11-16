package nl.kijk.tv.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import nl.kijk.tv.AppViewModel
import nl.kijk.tv.Format
import nl.kijk.tv.data.Homey
import nl.kijk.tv.data.ImageLog
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Eén live-verbinding met een Homey-video (WebRTC, alleen ontvangen). */
private class LiveSession(ctx: Context, private val homey: Homey, private val videoId: String, val renderer: SurfaceViewRenderer, private val onVideo: () -> Unit) {
    private val egl = EglBase.create()
    private val factory: PeerConnectionFactory
    private var pc: PeerConnection? = null
    @Volatile private var streamId: String? = null
    private var gathered: (() -> Unit)? = null

    init {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext).createInitializationOptions())
        factory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .createPeerConnectionFactory()
        renderer.init(egl.eglBaseContext, null)
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        renderer.setEnableHardwareScaler(true)
    }

    /** Volgorde van de media in ons aanbod; Homey antwoordt in een vaste volgorde, dus die nemen we over als het misgaat. */
    suspend fun start() {
        var order = listOf("audio", "video", "application")
        repeat(2) { attempt ->
            try { startWith(order); return }
            catch (e: MLineOrder) {
                ImageLog.add("Live: andere volgorde ${e.order}")
                runCatching { pc?.close() }; runCatching { pc?.dispose() }; pc = null
                order = e.order
                if (attempt == 1) throw IllegalStateException("volgorde past niet")
            }
        }
    }

    private class MLineOrder(val order: List<String>) : Exception("m-line volgorde")

    private suspend fun startWith(order: List<String>) {
        val cfg = PeerConnection.RTCConfiguration(listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val p = factory.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) { if (s == PeerConnection.IceGatheringState.COMPLETE) gathered?.invoke() }
            override fun onTrack(t: RtpTransceiver) {
                (t.receiver.track() as? VideoTrack)?.let { it.addSink(renderer); onVideo() }
            }
            override fun onAddTrack(r: RtpReceiver, s: Array<out MediaStream>) {}
            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) { ImageLog.add("Live: verbinding $s") }
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceCandidate(c: IceCandidate) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onDataChannel(d: DataChannel) {}
            override fun onRenegotiationNeeded() {}
        }) ?: throw IllegalStateException("WebRTC niet beschikbaar")
        pc = p
        val recv = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        order.forEach { k ->
            when (k) {
                "audio" -> p.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, recv)
                "video" -> p.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, recv)
                "application" -> p.createDataChannel("data", DataChannel.Init())
            }
        }

        val offer = suspendCoroutine<SessionDescription> { k ->
            p.createOffer(object : Sdp() {
                override fun onCreateSuccess(d: SessionDescription) { k.resume(d) }
                override fun onCreateFailure(e: String?) { k.resumeWith(Result.failure(IllegalStateException(e))) }
            }, MediaConstraints())
        }
        // alle kandidaten in het aanbod (geen losse uitwisseling achteraf): wachten tot ze er zijn, hooguit 3 s
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        gathered = { done.complete(Unit) }
        suspendCoroutine<Unit> { k ->
            p.setLocalDescription(object : Sdp() {
                override fun onSetSuccess() { k.resume(Unit) }
                override fun onSetFailure(e: String?) { k.resumeWith(Result.failure(IllegalStateException(e))) }
            }, offer)
        }
        kotlinx.coroutines.withTimeoutOrNull(3_000) { done.await() }
        val local = p.localDescription?.description ?: offer.description
        val (answer, sid) = withContext(Dispatchers.IO) { homey.videoOffer(videoId, local) }
        streamId = sid
        setAnswer(p, answer)
    }

    // wrapper om setRemoteDescription: bij een volgorde-fout de volgorde uit het antwoord teruggeven
    private suspend fun setAnswer(p: PeerConnection, answer: String) {
        try {
            suspendCoroutine<Unit> { k ->
                p.setRemoteDescription(object : Sdp() {
                    override fun onSetSuccess() { k.resume(Unit) }
                    override fun onSetFailure(e: String?) { k.resumeWith(Result.failure(IllegalStateException(e))) }
                }, SessionDescription(SessionDescription.Type.ANSWER, answer))
            }
        } catch (e: IllegalStateException) {
            if (e.message?.contains("m-lines", ignoreCase = true) == true) {
                val kinds = Regex("""(?m)^m=(\w+)""").findAll(answer).map { it.groupValues[1] }.toList()
                throw MLineOrder(kinds)
            }
            throw e
        }
    }

    suspend fun keepAlive() { streamId?.let { withContext(Dispatchers.IO) { homey.videoKeepAlive(videoId, it) } } }

    fun close() {
        runCatching { pc?.close() }; runCatching { pc?.dispose() }
        runCatching { renderer.release() }
        runCatching { factory.dispose() }
        runCatching { egl.release() }
    }

    private open class Sdp : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String?) {}
        override fun onSetFailure(e: String?) {}
    }
}

/** Live beeld van de deurbel over het hele scherm. Terug = sluiten. */
@Composable
fun DoorbellLiveScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val bell = vm.hyBell
    var state by remember { mutableStateOf("Verbinden…") }
    var playing by remember { mutableStateOf(false) }
    val renderer = remember { SurfaceViewRenderer(ctx) }
    val session = remember(bell?.video) {
        val v = bell?.video
        if (v == null) null else runCatching { LiveSession(ctx, vm.homey, v, renderer) { playing = true } }
            .onFailure { state = "Live beeld lukt niet: ${it.message}" }.getOrNull()
    }
    LaunchedEffect(session) {
        if (bell?.video == null) { state = if (bell == null) "Geen deurbel gevonden" else "Deze deurbel heeft geen live beeld in Homey"; return@LaunchedEffect }
        val s = session ?: return@LaunchedEffect
        try {
            s.start()
            delay(12_000)
            if (!playing) state = "Nog geen beeld… (Ring maakt de camera wakker)"
            while (true) { delay(20_000); s.keepAlive() }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { ImageLog.add("Live mislukt: ${e.message}"); state = "Live beeld lukt niet: ${e.message ?: e.javaClass.simpleName}" }
    }
    DisposableEffect(session) { onDispose { session?.close() } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // laatste snapshot erachter tot het live beeld er is
        if (!playing) HomeyImage(vm, bell, Modifier.fillMaxSize())
        AndroidView({ renderer }, Modifier.fillMaxSize())
        Row(Modifier.align(Alignment.TopStart).padding(start = Kijk.SafeH, top = Kijk.SafeV).background(Color(0xB3000000), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).background(if (playing) Color(0xFFFF453A) else Color(0xFF8E8E93), RoundedCornerShape(50)))
            Spacer(Modifier.width(8.dp))
            Label(if (playing) "${bell?.name ?: "Deurbel"} · live" else bell?.name ?: "Deurbel", size = Kijk.Headline, weight = Kijk.Semibold, color = Color.White)
            Spacer(Modifier.width(12.dp))
            Label(Format.time(vm.now), size = Kijk.Body, color = Color(0xFFD1D1D6))
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = Kijk.SafeV + 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (!playing) Label(state, Modifier.background(Color(0xB3000000), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 6.dp),
                size = 14.sp, color = Color.White, maxLines = 2)
            Label("Terug = sluiten", Modifier.padding(top = 6.dp), size = 12.sp, color = Color(0xFFAEAEB2))
        }
    }
}
