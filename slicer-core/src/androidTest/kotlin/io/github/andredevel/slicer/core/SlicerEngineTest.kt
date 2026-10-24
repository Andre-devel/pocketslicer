package io.github.andredevel.slicer.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.andredevel.slicer.profiles.OrcaResources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

/** Fatia modelos de testdata/ no aparelho com os perfis empacotados no APK. */
@RunWith(AndroidJUnit4::class)
class SlicerEngineTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var engine: SlicerEngine
    private lateinit var work: File

    @Before
    fun setUp() = runBlocking {
        work = File(context.cacheDir, "slicer-test").apply { deleteWithoutFollowingLinks(); mkdirs() }
        engine = SlicerEngine.create(OrcaResources.install(context), File(work, "data"))
    }

    @After
    fun tearDown() {
        engine.close()
        work.deleteWithoutFollowingLinks()
    }

    @Test
    fun selectingPrinterAppliesItsDefaultPresets() = runBlocking {
        assertTrue("BBL" in engine.vendors())
        engine.loadVendor("BBL")
        engine.select(PresetKind.PRINTER, "Bambu Lab A1 0.4 nozzle")

        assertEquals("0.20mm Standard @BBL A1", engine.selected(PresetKind.PROCESS))
        assertEquals("Bambu PLA Basic @BBL A1", engine.selected(PresetKind.FILAMENT))
        assertTrue(engine.presets(PresetKind.FILAMENT).all { "@BBL X1" !in it })
    }

    @Test
    fun modelIsCenteredOnBed() = runBlocking {
        engine.loadVendor("BBL")
        engine.select(PresetKind.PRINTER, "Bambu Lab A1 0.4 nozzle")
        val bed = engine.bed()
        assertEquals(256f, bed.shape.filterIndexed { i, _ -> i % 2 == 0 }.max(), 0.01f)
        assertEquals(256f, bed.maxHeight, 0.01f)

        engine.loadModel(testAsset("cube20.stl"))
        val mesh = engine.objects().single()
        assertEquals(12, mesh.indices.size / 3)
        // Cubo de 20 mm apoiado em z = 0 no centro.
        val world = worldVertices(mesh, engine.placements().single())
        assertEquals(118f, world.minOf { it[0] }, 0.01f)
        assertEquals(138f, world.maxOf { it[1] }, 0.01f)
        assertEquals(0f, world.minOf { it[2] }, 0.01f)
        // A posição do objeto é o centro da malha (pivô de rotação e escala).
        val offset = engine.placements().single().offset
        assertEquals(128f, offset[0], 0.01f)
        assertEquals(128f, offset[1], 0.01f)
    }

    @Test
    fun placementChangesKeepObjectOnBed() = runBlocking {
        engine.loadVendor("BBL")
        engine.select(PresetKind.PRINTER, "Bambu Lab A1 0.4 nozzle")
        engine.loadModel(testAsset("cube20.stl"))
        val mesh = engine.objects().single()

        // Offset z pedido é ignorado: o objeto volta a apoiar na mesa.
        engine.setPlacement(0, floatArrayOf(50f, 60f, 30f), floatArrayOf(0f, 0f, 45f), floatArrayOf(2f, 2f, 2f))
        var placement = engine.placements().single()
        assertEquals(45f, placement.rotation[2], 0.01f)
        assertEquals(2f, placement.scale[0], 0.001f)
        var world = worldVertices(mesh, placement)
        assertEquals(0f, world.minOf { it[2] }, 0.01f)
        assertEquals(40f, world.maxOf { it[2] }, 0.01f)
        // Diagonal de um quadrado de 40 mm girado 45°.
        assertEquals(40f * Math.sqrt(2.0).toFloat(), world.maxOf { it[0] } - world.minOf { it[0] }, 0.01f)

        // Triângulo cuja normal aponta para +X (no cubo sem rotação): vira a base.
        engine.setPlacement(0, floatArrayOf(50f, 60f, 0f), floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f))
        val side = (0 until mesh.indices.size / 3).first { t ->
            val xs = (0..2).map { mesh.positions[mesh.indices[t * 3 + it] * 3] }
            xs.all { it == xs.max() } && xs.max() > mesh.positions.filterIndexed { i, _ -> i % 3 == 0 }.min()
        }
        engine.layOnFace(0, side)
        placement = engine.placements().single()
        assertEquals(90f, Math.abs(placement.rotation[1]), 0.01f)
        world = worldVertices(mesh, placement)
        assertEquals(0f, world.minOf { it[2] }, 0.01f)
    }

    // Mesmo caso "cube20" de testdata/cases.tsv (comparado camada a camada por scripts/test-device.sh).
    @Test
    fun slicesCubeLikeDesktopOrca() = runBlocking {
        engine.loadVendor("BBL")
        engine.select(PresetKind.PRINTER, "Bambu Lab A1 0.4 nozzle")
        engine.select(PresetKind.PROCESS, "0.20mm Standard @BBL A1")
        engine.select(PresetKind.FILAMENT, "Bambu PLA Basic @BBL A1")
        engine.loadModel(testAsset("cube20.stl"))

        val progress = mutableListOf<Int>()
        val gcode = File(work, "cube20.gcode")
        val stats = engine.slice(gcode) { percent, _ -> progress += percent }

        val reference = referenceGcode("cube20")
        val referenceCm3 = Regex("; filament used \\[cm3\\] = ([0-9.]+)").find(reference)!!.groupValues[1].toDouble()
        assertEquals(referenceCm3, stats.filamentMm3 / 1000.0, referenceCm3 * 0.005)
        val layers = Regex("; total layer number: [0-9]+").find(reference)!!.value
        assertTrue(layers in gcode.readText())
        assertTrue(progress.isNotEmpty())
    }

    @Test
    fun cancelStopsSlicing() = runBlocking {
        engine.loadVendor("BBL")
        engine.loadModel(testAsset("voron_cube_v7.stl"))
        val started = CompletableDeferred<Unit>()
        val job = async { engine.slice(File(work, "voron.gcode")) { _, _ -> started.complete(Unit) } }
        started.await()
        job.cancel()
        val result = runCatching { job.await() }
        assertTrue(result.exceptionOrNull() is CancellationException)

        // O motor continua utilizável depois de cancelar.
        engine.loadModel(testAsset("cube20.stl"))
        assertTrue(engine.slice(File(work, "cube20.gcode")) { _, _ -> }.filamentMm3 > 0)
    }

    private fun worldVertices(mesh: ObjectMesh, placement: Placement): List<FloatArray> {
        val m = placement.transform
        return (0 until mesh.positions.size / 3).map { v ->
            FloatArray(3) { row -> (0 until 3).sumOf { col -> (m[col * 4 + row] * mesh.positions[v * 3 + col]).toDouble() }.toFloat() + m[12 + row] }
        }
    }

    private fun testAsset(name: String): File {
        val file = File(work, name)
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return file
    }

    /** G-code do OrcaSlicer desktop para o caso (scripts/make-reference.sh). */
    // O empacotador de assets descompacta testdata/reference/*.gcode.gz e tira o ".gz".
    private fun referenceGcode(case: String): String =
        InstrumentationRegistry.getInstrumentation().context.assets.open("reference/$case.gcode").use { input ->
            input.bufferedReader().readText()
        }
}

/**
 * O diretório de dados do motor tem symlinks para os perfis extraídos;
 * File.deleteRecursively() os seguiria e apagaria os perfis.
 */
private fun File.deleteWithoutFollowingLinks() {
    if (!toPath().let { Files.exists(it) || Files.isSymbolicLink(it) }) return
    Files.walk(toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
}
