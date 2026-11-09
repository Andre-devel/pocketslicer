package io.github.andredevel.slicer

import io.github.andredevel.slicer.core.Bed
import io.github.andredevel.slicer.core.ObjectMesh
import io.github.andredevel.slicer.core.Placement
import android.opengl.Matrix
import io.github.andredevel.slicer.send.Http
import io.github.andredevel.slicer.send.Http.resolve
import io.github.andredevel.slicer.send.SendException
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max

/**
 * Slider de câmera da K2 (hardware/camera-slider, ver motores.md): um carrinho num trilho de
 * [SliderGeometry.TRAVEL] mm na frente da impressora, com o celular num pan de ±[SliderGeometry.PAN_LIMIT]°.
 * A cada camada o ESP32 tira a foto, espera [pauseMs] e leva o carrinho e o pan à posição da próxima.
 *
 * [start]/[end]: posição do carrinho (mm a partir da chave de fim de curso) na primeira e na
 * última camada. [followObject]: índice do objeto da placa que o pan [PanMode.FOLLOW] mantém no
 * centro. Ângulos em graus; positivo gira para +X da impressora. [espHost]: endereço do ESP32 do
 * slider (IP ou nome), para onde o plano vai ao iniciar a impressão; vazio = não manda.
 * [passes]: idas e voltas de [start] a [end] ao longo da impressão (0 = só a ida); com elas o
 * carrinho desacelera perto das pontas e fica [dwellLayers] camadas parado em cada virada.
 */
data class SliderConfig(
    val start: Int = 0,
    val end: Int = SliderGeometry.TRAVEL,
    val panMode: PanMode = PanMode.FOLLOW,
    val followObject: Int = 0,
    val fixedAngle: Int = 0,
    val sweepStart: Int = -15,
    val sweepEnd: Int = 15,
    val pauseMs: Int = 300,
    val passes: Int = 0,
    val dwellLayers: Int = 0,
    val mount: SliderMount = SliderMount(),
    val espHost: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("start", start).put("end", end).put("panMode", panMode.name).put("followObject", followObject)
        .put("fixedAngle", fixedAngle).put("sweepStart", sweepStart).put("sweepEnd", sweepEnd).put("pauseMs", pauseMs)
        .put("passes", passes).put("dwellLayers", dwellLayers)
        .put("railDistance", mount.railDistance).put("lateralOffset", mount.lateralOffset).put("espHost", espHost)

    companion object {
        fun fromJson(json: JSONObject): SliderConfig {
            val default = SliderConfig()
            return SliderConfig(
                start = json.optInt("start", default.start),
                end = json.optInt("end", default.end),
                panMode = PanMode.entries.firstOrNull { it.name == json.optString("panMode") } ?: default.panMode,
                followObject = json.optInt("followObject", default.followObject),
                fixedAngle = json.optInt("fixedAngle", default.fixedAngle),
                sweepStart = json.optInt("sweepStart", default.sweepStart),
                sweepEnd = json.optInt("sweepEnd", default.sweepEnd),
                pauseMs = json.optInt("pauseMs", default.pauseMs),
                passes = json.optInt("passes", default.passes),
                dwellLayers = json.optInt("dwellLayers", default.dwellLayers),
                mount = SliderMount(json.optInt("railDistance", default.mount.railDistance), json.optInt("lateralOffset", default.mount.lateralOffset)),
                espHost = json.optString("espHost", default.espHost),
            )
        }
    }
}

/**
 * Onde o trilho fica, medido uma vez: [railDistance] do trilho ao centro da mesa (mm, na
 * direção Y da impressora) e [lateralOffset] do meio do trilho ao centro da mesa em X
 * (0 = trilho centrado; positivo = deslocado para +X).
 */
data class SliderMount(val railDistance: Int = 510, val lateralOffset: Int = 0)

enum class PanMode { FOLLOW, FIXED, SWEEP }

object SliderGeometry {
    /** Curso útil do carrinho (mm); 0 é a ponta da chave, à esquerda de quem olha a impressora. */
    const val TRAVEL = 440
    /** Limite do pan por software (graus, para cada lado). */
    const val PAN_LIMIT = 25
    /** Comprimento do perfil V-slot. */
    const val PROFILE_LENGTH = 600f
    /** Altura da lente acima da mesa (mm) e acima do topo do perfil. */
    const val LENS_HEIGHT = 160f
    const val LENS_ABOVE_RAIL = 220f
    /** Campo de visão vertical do celular em pé e a proporção do quadro (largura / altura). */
    const val PHONE_FOV = 62f
    const val PHONE_ASPECT = 9f / 16f
    const val PAUSE_STEP = 100
    val PAUSE_RANGE = 100..2000
    val PASSES_RANGE = 0..5
    val DWELL_RANGE = 0..50
    /** Cabeçote parado no canto por foto (WAIT= da macro TIMELAPSE_FOTO). */
    const val PARK_DEFAULT = 1500
    val PARK_RANGE = 300..5000
    val RAIL_DISTANCE_RANGE = 300..900
    val LATERAL_RANGE = -200..200
}

/** Um objeto que o pan pode seguir: nome e centro da caixa (x, y, z na mesa). */
data class SliderTarget(val name: String, val center: FloatArray)

/** Centro da caixa de cada objeto na posição da placa, na ordem de [placements]. */
fun sliderTargets(meshes: List<ObjectMesh>, placements: List<Placement>): List<SliderTarget> =
    meshes.zip(placements).mapIndexed { index, (mesh, placement) ->
        val min = FloatArray(3) { Float.MAX_VALUE }
        val max = FloatArray(3) { -Float.MAX_VALUE }
        val local = floatArrayOf(0f, 0f, 0f, 1f)
        val world = FloatArray(4)
        for (v in 0 until mesh.positions.size / 3) {
            mesh.positions.copyInto(local, 0, v * 3, v * 3 + 3)
            Matrix.multiplyMV(world, 0, placement.transform, 0, local, 0)
            for (axis in 0..2) {
                min[axis] = minOf(min[axis], world[axis])
                max[axis] = maxOf(max[axis], world[axis])
            }
        }
        val center = if (min[0] <= max[0]) FloatArray(3) { (min[it] + max[it]) / 2 } else placement.offset.copyOf()
        SliderTarget(mesh.name.ifBlank { "#${index + 1}" }, center)
    }

/**
 * Onde o carrinho e o pan ficam em cada camada (de 0 a [layerCount] - 1), na mesa da
 * impressora: o trilho corre em X a [SliderMount.railDistance] mm do centro da mesa, na frente
 * dela (Y menor). [target]: centro do objeto seguido no modo [PanMode.FOLLOW].
 */
class SliderPlan(val config: SliderConfig, val layerCount: Int, bedCenter: FloatArray, val target: FloatArray?) {
    val railY = bedCenter[1] - config.mount.railDistance
    /** X da mesa do zero do trilho (ponta da chave). */
    val railZeroX = bedCenter[0] + config.mount.lateralOffset - SliderGeometry.TRAVEL / 2f
    val railTopZ = SliderGeometry.LENS_HEIGHT - SliderGeometry.LENS_ABOVE_RAIL

    /** Onde a camada está entre [SliderConfig.start] (0) e [SliderConfig.end] (1); ver [sliderFraction]. */
    fun fraction(layer: Int) = sliderFraction(layer, layerCount, config.passes, config.dwellLayers)

    /** Posição do carrinho no trilho (mm a partir da chave). */
    fun carriage(layer: Int): Float = carriageAt(fraction(layer))

    private fun carriageAt(f: Float): Float = config.start + (config.end - config.start) * f

    fun carriageX(layer: Int): Float = railZeroX + carriage(layer)

    /** Ângulo que o pan precisaria ter, sem o limite. */
    fun rawPan(layer: Int): Float = rawPanAt(fraction(layer))

    private fun rawPanAt(f: Float): Float = when (config.panMode) {
        PanMode.FIXED -> config.fixedAngle.toFloat()
        PanMode.SWEEP -> config.sweepStart + (config.sweepEnd - config.sweepStart) * f
        PanMode.FOLLOW -> target?.let { Math.toDegrees(atan2((it[0] - railZeroX - carriageAt(f)).toDouble(), (it[1] - railY).toDouble())).toFloat() } ?: 0f
    }

    fun pan(layer: Int): Float = panAt(fraction(layer))

    /** Pan (com o limite) com o carrinho em [f] do caminho: 0 no início, 1 no fim (a virada, com idas e voltas). */
    fun panAt(f: Float): Float = rawPanAt(f).coerceIn(-SliderGeometry.PAN_LIMIT.toFloat(), SliderGeometry.PAN_LIMIT.toFloat())

    /**
     * Maior ângulo pedido ao longo da impressão, se passar do limite (o pan para nele). Nos três
     * modos o ângulo só depende de [fraction] e varia num sentido só com ela, então o maior está
     * numa das pontas do trilho (com idas e voltas, a ponta [SliderConfig.end] é a virada).
     */
    val saturation: Float? = max(abs(rawPanAt(0f)), abs(rawPanAt(if (layerCount > 1) 1f else 0f))).takeIf { it > SliderGeometry.PAN_LIMIT + 0.05f }

    /** Posição da lente do celular (x, y, z na mesa). */
    fun lens(layer: Int) = floatArrayOf(carriageX(layer), railY, SliderGeometry.LENS_HEIGHT)

    /**
     * Ponto para onde o celular aponta: na direção do pan, à distância do alvo (ou do centro da
     * mesa), na altura do centro do alvo; o celular fica inclinado só o que o suporte permite, e
     * isto aproxima a inclinação dele.
     */
    fun aim(layer: Int): FloatArray {
        val lens = lens(layer)
        val distance = target?.let { it[1] - railY } ?: config.mount.railDistance.toFloat()
        val angle = Math.toRadians(pan(layer).toDouble())
        val z = target?.get(2) ?: AIM_HEIGHT
        return floatArrayOf(lens[0] + distance * kotlin.math.tan(angle).toFloat(), railY + distance, z)
    }

    /**
     * Plano para o firmware (POST /plano, hardware/camera-slider/firmware/src/timelapse.h): o
     * objeto seguido vai no sistema do trilho (X a partir do zero do trilho, distância da linha
     * do trilho), e o ESP32 refaz as contas de [carriage] e [rawPan] a cada camada. Seguir sem
     * objeto vira fixo em 0°, como [rawPan] mostra.
     */
    fun espJson(): JSONObject = JSONObject()
        .put("layers", layerCount).put("start", config.start).put("end", config.end)
        .put("panMode", if (config.panMode == PanMode.FOLLOW && target == null) PanMode.FIXED.name else config.panMode.name)
        .put("fixedAngle", if (config.panMode == PanMode.FOLLOW) 0 else config.fixedAngle)
        .put("sweepStart", config.sweepStart).put("sweepEnd", config.sweepEnd)
        .put("targetX", (target?.get(0) ?: (railZeroX + SliderGeometry.TRAVEL / 2f)) - railZeroX)
        .put("targetDistance", target?.let { it[1] - railY } ?: config.mount.railDistance.toFloat())
        .put("pauseMs", config.pauseMs).put("passes", config.passes).put("dwellLayers", config.dwellLayers)

    private companion object {
        const val AIM_HEIGHT = 30f
    }
}

/**
 * Onde a camada [layer] (de 0 a [layerCount] - 1) fica entre o início (0) e o fim (1) do curso.
 * Sem [passes]: reta do início ao fim. Com [passes] idas e voltas: 2 x [passes] trechos de mesmo
 * número de camadas, alternando o sentido, cada um com aceleração e desaceleração em cosseno, e
 * [dwellLayers] camadas paradas em cada virada (menos, se não couberem). A mesma conta está no
 * firmware (hardware/camera-slider/firmware/src/slider_math.h).
 */
fun sliderFraction(layer: Int, layerCount: Int, passes: Int, dwellLayers: Int): Float {
    if (layerCount <= 1) return 0f
    val t = layer.coerceIn(0, layerCount - 1).toFloat()
    val span = (layerCount - 1).toFloat()
    if (passes <= 0) return t / span
    val legs = 2 * passes
    val turns = legs - 1
    // As paradas ocupam no máximo metade da impressão; o resto anda.
    val dwell = dwellLayers.toFloat().coerceIn(0f, span * 0.5f / turns)
    val legLength = (span - dwell * turns) / legs
    val cycle = legLength + dwell
    var leg = (t / cycle).toInt()
    val u: Float
    if (leg >= legs) {
        leg = legs - 1
        u = 1f
    } else {
        val r = t - leg * cycle
        u = if (r >= legLength) 1f else r / legLength
    }
    val eased = ((1 - kotlin.math.cos(Math.PI * u)) / 2).toFloat()
    return if (leg % 2 == 0) eased else 1 - eased
}

/**
 * Plano do slider para a cena e o fatiamento de [state], com [targets] = [sliderTargets] da
 * cena; null sem cena ou sem fatiamento.
 */
fun sliderPlan(state: SlicerUiState, targets: List<SliderTarget>): SliderPlan? {
    val layerCount = state.preview?.toolpaths?.layerCount ?: 0
    val scene = state.scene?.takeIf { layerCount > 0 } ?: return null
    val config = state.sliderConfig
    val target = targets.getOrNull(config.followObject.coerceAtMost(targets.size - 1).coerceAtLeast(0))
    return SliderPlan(config, layerCount, scene.bed.center(), target?.center?.takeIf { config.panMode == PanMode.FOLLOW })
}

/**
 * O que o ESP32 do slider diz de si (GET /status): celular pareado por Bluetooth, macro
 * TIMELAPSE_FOTO assinada na impressora dele, camadas do plano salvo (0 = nenhum) e erro do
 * último homing (vazio se nenhum).
 */
data class SliderStatus(val ble: Boolean, val printerHost: String, val printerReady: Boolean, val planLayers: Int, val motorError: String)

/** Lê o estado do ESP32 em [host]; falha com [SendException]. */
suspend fun fetchSliderStatus(host: String): SliderStatus {
    val response = Http.get(Http.baseUrl(host).resolve("/status"), emptyMap(), readTimeoutMs = 5_000)
    if (response.code != 200) throw SendException(R.string.send_error_http, response.code, response.body.take(200))
    val json = try {
        JSONObject(response.body)
    } catch (e: org.json.JSONException) {
        throw SendException(R.string.send_error_io, e.message.orEmpty(), cause = e)
    }
    val printer = json.optJSONObject("printer")
    return SliderStatus(
        ble = json.optBoolean("ble"),
        printerHost = printer?.optString("host").orEmpty(),
        printerReady = printer?.optBoolean("subscribed") == true,
        planLayers = json.optJSONObject("plan")?.optInt("layers") ?: 0,
        motorError = json.optJSONObject("motion")?.optString("error").orEmpty(),
    )
}

/** Manda [plan] ao ESP32 em [host]; falha com [SendException] (sem resposta, recusado). */
suspend fun sendSliderPlan(host: String, plan: SliderPlan) {
    val response = Http.postJson(Http.baseUrl(host).resolve("/plano"), emptyMap(), plan.espJson().toString(), readTimeoutMs = 10_000)
    if (response.code != 200) throw SendException(R.string.send_error_http, response.code, response.body.take(200))
}

/**
 * Leva o carrinho a [mm] (a partir da chave) e o pan a [deg] (GET /mover), para conferir o
 * enquadramento; o ESP32 faz o homing antes se ainda não fez. Falha com [SendException].
 */
suspend fun moveSlider(host: String, mm: Float, deg: Float) {
    val path = String.format(java.util.Locale.US, "/mover?x=%.1f&pan=%.1f", mm, deg)
    sliderCommand(host, path)
}

/** Para os motores do slider (GET /parar). */
suspend fun stopSlider(host: String) = sliderCommand(host, "/parar")

private suspend fun sliderCommand(host: String, path: String) {
    val response = Http.get(Http.baseUrl(host).resolve(path), emptyMap(), readTimeoutMs = 5_000)
    if (response.code == 409) {
        val error = runCatching { JSONObject(response.body).optString("error") }.getOrNull().orEmpty()
        throw SendException(R.string.slider_refused, error.ifBlank { response.body.take(200) })
    }
    if (response.code != 200) throw SendException(R.string.send_error_http, response.code, response.body.take(200))
}

/** Centro do retângulo que envolve a mesa. */
fun Bed.center(): FloatArray {
    val xs = shape.filterIndexed { i, _ -> i % 2 == 0 }
    val ys = shape.filterIndexed { i, _ -> i % 2 == 1 }
    return if (xs.isEmpty()) floatArrayOf(0f, 0f, 0f) else floatArrayOf((xs.min() + xs.max()) / 2, (ys.min() + ys.max()) / 2, 0f)
}

/** Impressora do slider: o G-code de time-lapse dela chama a macro TIMELAPSE_FOTO (perfil "time-lapse slider"). */
val PresetChoice.isSliderPrinter get() = values.values["time_lapse_gcode"]?.contains("TIMELAPSE_FOTO") == true

private val SLIDER_PHOTO_CALL = Regex("""TIMELAPSE_FOTO((?: MODE=\w+)?)(?: WAIT=\d+)?""")

/** Tempo (ms) que a macro deixa o cabeçote parado no canto (WAIT=, ou wait_ms da macro: 1500). */
val PresetChoice.sliderParkMs: Int
    get() = values.values["time_lapse_gcode"]?.let { Regex("""TIMELAPSE_FOTO[^\n{}]* WAIT=(\d+)""").find(it) }
        ?.groupValues?.get(1)?.toIntOrNull() ?: SliderGeometry.PARK_DEFAULT

/** [gcode] com WAIT=[ms] em cada chamada de TIMELAPSE_FOTO. */
fun sliderGcodeWithPark(gcode: String, ms: Int): String =
    SLIDER_PHOTO_CALL.replace(gcode) { "TIMELAPSE_FOTO${it.groupValues[1]} WAIT=$ms" }
