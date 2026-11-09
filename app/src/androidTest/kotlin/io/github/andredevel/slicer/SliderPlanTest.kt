package io.github.andredevel.slicer

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.math.atan2

@RunWith(AndroidJUnit4::class)
class SliderPlanTest {
    private val config = SliderConfig(start = 40, end = 400, panMode = PanMode.FOLLOW, pauseMs = 500, mount = SliderMount(510, 30))
    private val plan = SliderPlan(config, 120, floatArrayOf(128f, 128f, 0f), floatArrayOf(90f, 140f, 20f))

    /** O firmware (timelapse.cpp) refaz o pan com estes campos: tem de dar o mesmo ângulo do app. */
    @Test
    fun espJsonGivesTheFirmwareTheSamePan() {
        val json = plan.espJson()
        assertEquals(120, json.getInt("layers"))
        assertEquals("FOLLOW", json.getString("panMode"))
        assertEquals(500, json.getInt("pauseMs"))
        val targetX = json.getDouble("targetX")
        val distance = json.getDouble("targetDistance")
        for (layer in listOf(0, 37, 119)) {
            val carriage = 40 + (400 - 40) * layer / 119.0
            assertEquals(plan.carriage(layer).toDouble(), carriage, 0.01)
            val firmwarePan = Math.toDegrees(atan2(targetX - carriage, distance))
            assertEquals(plan.rawPan(layer).toDouble(), firmwarePan, 0.01)
        }
    }

    /** Seguir sem objeto: o app mostra 0°, então o firmware recebe fixo em 0°. */
    @Test
    fun followWithoutTargetGoesAsFixedZero() {
        val json = SliderPlan(config, 120, floatArrayOf(128f, 128f, 0f), null).espJson()
        assertEquals("FIXED", json.getString("panMode"))
        assertEquals(0, json.getInt("fixedAngle"))
    }

    @Test
    fun sendSliderPlanPostsTheJsonToPlano() {
        ServerSocket(0).use { server ->
            var request = ""
            val reader = thread {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    val head = generateSequence { input.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                    val length = head.first { it.startsWith("Content-Length:", ignoreCase = true) }.substringAfter(':').trim().toInt()
                    val body = CharArray(length).also { var read = 0; while (read < length) read += input.read(it, read, length - read) }
                    request = head.first() + "\n" + String(body)
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 11\r\nConnection: close\r\n\r\n{\"ok\":true}".toByteArray())
                }
            }
            runBlocking { sendSliderPlan("127.0.0.1:${server.localPort}", plan) }
            reader.join(5000)
            assertEquals("POST /plano HTTP/1.1", request.lineSequence().first())
            assertEquals(plan.espJson().toString(), JSONObject(request.substringAfter('\n')).toString())
        }
    }
}
