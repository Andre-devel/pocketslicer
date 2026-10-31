package io.github.andredevel.slicer

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.andredevel.slicer.send.Camera
import org.json.JSONObject
import java.net.URL

/**
 * Vídeo da câmera da impressora num WebView: o MJPEG e a foto vão num `<img>`, e o WebRTC da
 * Creality pelo `RTCPeerConnection` do próprio WebView (só recebe, sem pedir permissão), como o
 * k2dash (github.com/pdromnt/k2dash, useWebcam.ts). A página carrega com a origem da impressora,
 * então o POST da oferta não esbarra em CORS.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CameraView(camera: Camera, modifier: Modifier = Modifier) {
    // Erro que a página informou; sem ele e sem [playing], carregando.
    var problem by remember(camera) { mutableStateOf<String?>(null) }
    var playing by remember(camera) { mutableStateOf(false) }
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    setBackgroundColor(android.graphics.Color.BLACK)
                    settings.javaScriptEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    val main = Handler(Looper.getMainLooper())
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun playing() = main.post { playing = true; problem = null }

                        @JavascriptInterface
                        fun failed(message: String) = main.post { playing = false; problem = message }
                    }, "Camera")
                    loadDataWithBaseURL(origin(camera), page(camera), "text/html", "utf-8", null)
                }
            },
            onRelease = { it.destroy() },
            modifier = Modifier.fillMaxSize(),
        )
        val error = problem
        if (error != null) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(R.drawable.ic_error), null, tint = MaterialTheme.colorScheme.error)
                Text(
                    stringResource(R.string.camera_error, error),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
            }
        } else if (!playing) {
            CircularProgressIndicator(Modifier.size(32.dp))
        }
    }
}

/** Origem (`http://ip:porta/`) do endereço da câmera, base da página. */
private fun origin(camera: Camera): String {
    val url = URL(
        when (camera) {
            is Camera.Image -> camera.url
            is Camera.CrealityWebRtc -> camera.signalingUrl
        },
    )
    return URL(url.protocol, url.host, url.port, "/").toString()
}

private fun page(camera: Camera): String {
    val config = when (camera) {
        is Camera.Image -> JSONObject()
            .put("type", "image").put("url", camera.url).put("snapshot", camera.snapshot)
            .put("transform", transform(camera))
        is Camera.CrealityWebRtc -> JSONObject().put("type", "webrtc").put("url", camera.signalingUrl)
    }
    return PAGE.replace("/*CONFIG*/", config.toString())
}

/** `transform` CSS da rotação e dos espelhamentos do Moonraker. */
private fun transform(camera: Camera.Image) = buildString {
    if (camera.rotation != 0) append("rotate(").append(camera.rotation).append("deg) ")
    if (camera.flipHorizontal) append("scaleX(-1) ")
    if (camera.flipVertical) append("scaleY(-1)")
}.trim()

/** Página da câmera; o marcador CONFIG recebe o tipo e a URL. A foto ([Camera.Image.snapshot]) recarrega a cada 1 s. */
private const val PAGE = """<!doctype html>
<html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>
html,body{margin:0;height:100%;background:#000;overflow:hidden}
video,img{position:absolute;inset:0;width:100%;height:100%;object-fit:contain}
</style></head>
<body><video id="v" autoplay muted playsinline hidden></video><img id="i" hidden>
<script>
const c = /*CONFIG*/;
const fail = m => Camera.failed(String(m));
if (c.type === 'image') {
  const img = document.getElementById('i');
  img.hidden = false;
  img.style.transform = c.transform;
  let shown = false;
  img.onload = () => {
    if (!shown) { shown = true; Camera.playing(); }
    if (c.snapshot) setTimeout(load, 1000);
  };
  img.onerror = () => fail('HTTP');
  const load = () => { img.src = c.snapshot ? c.url + (c.url.includes('?') ? '&' : '?') + 't=' + Date.now() : c.url; };
  load();
} else {
  start().catch(e => fail(e && e.message || e));
}
async function start() {
  const pc = new RTCPeerConnection();
  pc.addTransceiver('video', { direction: 'recvonly' });
  const video = document.getElementById('v');
  pc.ontrack = e => {
    if (e.track.kind !== 'video') return;
    video.srcObject = e.streams[0];
    video.hidden = false;
  };
  video.onplaying = () => Camera.playing();
  pc.oniceconnectionstatechange = () => {
    if (pc.iceConnectionState === 'failed' || pc.iceConnectionState === 'disconnected') fail('ICE ' + pc.iceConnectionState);
  };
  await pc.setLocalDescription(await pc.createOffer());
  // A K2 só lê a oferta com os candidatos, então espera a coleta terminar (ou 5 s).
  await new Promise(resolve => {
    if (pc.iceGatheringState === 'complete') return resolve();
    const timer = setTimeout(resolve, 5000);
    pc.onicegatheringstatechange = () => { if (pc.iceGatheringState === 'complete') { clearTimeout(timer); resolve(); } };
  });
  const offer = btoa(String.fromCharCode(...new TextEncoder().encode(JSON.stringify({ type: 'offer', sdp: pc.localDescription.sdp }))));
  const response = await fetch(c.url, { method: 'POST', headers: { 'Content-Type': 'text/plain' }, body: offer });
  const text = (await response.text()).trim();
  if (!response.ok || !text || text === '{}') throw new Error('HTTP ' + response.status);
  let sdp = atob(text);
  try { sdp = JSON.parse(sdp).sdp || sdp; } catch (e) {}
  await pc.setRemoteDescription({ type: 'answer', sdp });
}
</script></body></html>"""
