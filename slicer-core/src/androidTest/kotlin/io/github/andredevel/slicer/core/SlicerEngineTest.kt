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
