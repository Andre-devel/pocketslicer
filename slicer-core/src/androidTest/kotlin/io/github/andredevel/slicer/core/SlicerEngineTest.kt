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
        // Se setUp falhou, o erro dele é o que importa: sem isto, o do engine não inicializado o esconde.
        if (::engine.isInitialized) {
            // O idioma vale para o processo todo.
            runBlocking { engine.setLanguage("en") }
            engine.close()
        }
        if (::work.isInitialized) work.deleteWithoutFollowingLinks()
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
        assertTrue(mesh.name, mesh.name.startsWith("cube20"))
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
        // Cubo de 40 mm girado 45° em z: diagonal na largura e na profundidade.
        assertEquals(40f * Math.sqrt(2.0).toFloat(), placement.size[0], 0.01f)
        assertEquals(40f, placement.size[2], 0.01f)
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

    @Test
    fun multipleObjectsAreArrangedAndCheckedAgainstBed() = runBlocking {
        engine.loadVendor("BBL")
        engine.select(PresetKind.PRINTER, "Bambu Lab A1 0.4 nozzle")
        // Mesma sequência do app: adicionar dois modelos e duplicar o segundo.
        engine.addModel(testAsset("voron_cube_v7.stl"))
        engine.addModel(testAsset("cube20.stl"))
        engine.duplicate(1)
        assertEquals(3, engine.objects().size)

        assertArrangedWithoutOverlap()

        engine.remove(1)
        assertEquals(2, engine.objects().size)

        // Fora da mesa: aviso e fatiamento recusado; o arranjo traz de volta.
        val p = engine.placements()[1]
        engine.setPlacement(1, floatArrayOf(300f, 128f, 0f), p.rotation, p.scale)
        assertTrue(engine.placements()[1].outside)
        assertTrue(runCatching { engine.slice(File(work, "out.gcode")) { _, _ -> } }.exceptionOrNull() is SlicerException)
        engine.arrange()
        assertArrangedWithoutOverlap()
    }

    /** Caixas XY sem sobreposição e todos os objetos dentro da mesa. */
    private suspend fun assertArrangedWithoutOverlap() {
        val boxes = engine.objects().zip(engine.placements()).map { (mesh, placement) ->
            val world = worldVertices(mesh, placement)
            floatArrayOf(world.minOf { it[0] }, world.minOf { it[1] }, world.maxOf { it[0] }, world.maxOf { it[1] })
        }
        for (i in boxes.indices) for (j in i + 1 until boxes.size) {
            val a = boxes[i]
            val b = boxes[j]
            assertTrue("objetos $i e $j se sobrepõem: ${boxes.map { it.toList() }}", a[2] <= b[0] || b[2] <= a[0] || a[3] <= b[1] || b[3] <= a[1])
        }
        assertTrue(engine.placements().none { it.outside })
    }

    @Test
    fun optionsComeFromPrintConfigDefAndAffectSlicing() = runBlocking {
        engine.loadVendor("BBL")
        engine.select(PresetKind.PRINTER, "Bambu Lab A1 0.4 nozzle")
        val options = engine.options(PresetKind.PROCESS).associateBy { it.key }
        val layerHeight = options.getValue("layer_height")
        assertEquals(OptionType.FLOAT, layerHeight.type)
        assertTrue(layerHeight.simple)
        assertTrue(options.getValue("sparse_infill_pattern").enumValues.contains("gyroid"))
        assertTrue(options.values.any { !it.simple })
        assertTrue("inherits" !in options)

        assertEquals("0.2", engine.values(PresetKind.PROCESS).values["layer_height"])
        engine.setOption(PresetKind.PROCESS, "layer_height", "0.1")
        val values = engine.values(PresetKind.PROCESS)
        assertEquals("0.1", values.values["layer_height"])
        assertTrue("layer_height" in values.dirty)
        // Valor do preset salvo, só das alteradas; restaurá-lo limpa a alteração.
        assertEquals(mapOf("layer_height" to "0.2"), values.saved)
        engine.setOption(PresetKind.PROCESS, "layer_height", values.saved.getValue("layer_height"))
        assertTrue(engine.values(PresetKind.PROCESS).dirty.isEmpty())
        engine.setOption(PresetKind.PROCESS, "layer_height", "0.1")
        assertTrue(runCatching { engine.setOption(PresetKind.PROCESS, "layer_height", "abc") }.exceptionOrNull() is SlicerException)

        // O valor editado vale no fatiamento: 1ª camada de 0.2 mm e as outras de 0.1 mm
        // num cubo de 20 mm (eram 100 camadas).
        engine.loadModel(testAsset("cube20.stl"))
        val gcode = File(work, "fine.gcode")
        engine.slice(gcode) { _, _ -> }
        assertTrue("; total layer number: 199" in gcode.readText())

        engine.discardChanges(PresetKind.PROCESS)
        assertEquals("0.2", engine.values(PresetKind.PROCESS).values["layer_height"])
    }

    @Test
    fun textsFollowLanguage() = runBlocking {
        engine.loadVendor("BBL")
        engine.setLanguage("pt_BR")
        val layerHeight = engine.options(PresetKind.PROCESS).first { it.key == "layer_height" }
        assertEquals("Altura da camada", layerHeight.label)
        assertEquals("Parede interna", engine.translate("Inner wall"))
        // Erros do motor (aqui, valor inválido) e mensagens do libslic3r vêm traduzidos.
        val error = runCatching { engine.setOption(PresetKind.PROCESS, "layer_height", "abc") }.exceptionOrNull()
        assertEquals("Valor inválido fornecido para o parâmetro Altura da camada: abc", error?.message)
        val empty = File(work, "empty.stl").apply { writeText("") }
        assertEquals("Falha ao carregar um arquivo de modelo.", runCatching { engine.loadModel(empty) }.exceptionOrNull()?.message)

        engine.setLanguage("en")
        assertEquals("Layer height", engine.options(PresetKind.PROCESS).first { it.key == "layer_height" }.label)
    }

    @Test
    fun userPresetsAreSavedDeletedAndImported() = runBlocking {
        engine.loadVendor("BBL")
        engine.select(PresetKind.PRINTER, "Bambu Lab A1 0.4 nozzle")
        engine.setOption(PresetKind.PROCESS, "sparse_infill_density", "35%")
        engine.savePreset(PresetKind.PROCESS, "Meu processo")
        assertEquals("Meu processo", engine.selected(PresetKind.PROCESS))
        assertEquals(listOf("Meu processo"), engine.userPresets(PresetKind.PROCESS))
        assertTrue(engine.values(PresetKind.PROCESS).dirty.isEmpty())
        assertTrue(runCatching { engine.savePreset(PresetKind.PROCESS, "0.20mm Standard @BBL A1") }.isFailure)

        // Persistência: um motor novo sobre o mesmo diretório de dados vê o preset.
        val exported = File(work, "export.json")
        engine.exportPreset(PresetKind.PROCESS, exported)
        engine.close()
        engine = SlicerEngine.create(OrcaResources.install(context), File(work, "data"))
        engine.loadVendor("BBL")
        engine.select(PresetKind.PRINTER, "Bambu Lab A1 0.4 nozzle")
        assertTrue("Meu processo" in engine.presets(PresetKind.PROCESS))
        engine.select(PresetKind.PROCESS, "Meu processo")
        assertEquals("35%", engine.values(PresetKind.PROCESS).values["sparse_infill_density"])

        engine.deletePreset(PresetKind.PROCESS, "Meu processo")
        assertTrue(engine.userPresets(PresetKind.PROCESS).isEmpty())
        assertEquals("0.20mm Standard @BBL A1", engine.selected(PresetKind.PROCESS))

        assertEquals(listOf("Meu processo"), engine.importPreset(exported))
        assertTrue("Meu processo" in engine.userPresets(PresetKind.PROCESS))
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
        assertTrue(stats.filamentMeters > 0 && stats.filamentGrams > 0 && stats.cost > 0)
        // Um filamento: o gasto dele é o total.
        val use = stats.filaments.single()
        assertEquals(0, use.index)
        assertEquals(stats.filamentGrams, use.grams, stats.filamentGrams * 0.01)
        assertEquals(stats.filamentMeters, use.meters, stats.filamentMeters * 0.01)
        assertEquals(stats.cost, use.cost, 1e-9)
        // Tempo por tipo de extrusão: um por nome; no cubo pequeno, deslocamentos e trocas de
        // camada levam mais da metade do tempo total.
        assertEquals(SlicerEngine.roleNames.size, stats.roleTimes.size)
        assertTrue(stats.roleTimes.sum() in 0.2 * stats.printTimeSeconds..stats.printTimeSeconds)
        assertTrue(stats.roleTimes[SlicerEngine.roleNames.indexOf("Outer wall")] > 0)

        // Caminhos para a pré-visualização: 100 camadas até 20 mm, com paredes externas.
        val toolpaths = engine.toolpaths()
        assertEquals(100, toolpaths.layerCount)
        assertEquals(20f, toolpaths.layerZ.last(), 0.01f)
        assertEquals(toolpaths.segmentCount * 9, toolpaths.extrusions.size)
        val outerWall = SlicerEngine.roleNames.indexOf("Outer wall")
        assertTrue(toolpaths.roles.any { it.toInt() == outerWall })
        assertTrue(toolpaths.travels.isNotEmpty())

        // "Fatiar de novo" sem mudança: o Orca não refaz o G-code, e o resultado não pode sumir.
        val again = engine.slice(gcode) { _, _ -> }
        assertEquals(stats.filamentMm3, again.filamentMm3, 0.001)
        assertEquals(toolpaths.segmentCount, engine.toolpaths().segmentCount)
    }

    @Test
    fun projectKeepsObjectsPresetsAndChanges() = runBlocking {
        engine.loadVendor("BBL")
        engine.select(PresetKind.PRINTER, "Bambu Lab A1 0.4 nozzle")
        engine.setOption(PresetKind.PROCESS, "sparse_infill_density", "35%")
        engine.loadModel(testAsset("cube20.stl"))
        engine.addModel(testAsset("voron_cube_v7.stl"))
        engine.setPlacement(0, floatArrayOf(60f, 70f, 10f), floatArrayOf(0f, 0f, 30f), floatArrayOf(1f, 1f, 1f))
        val placements = engine.placements()
        val project = File(work, "project.3mf")
        engine.saveProject(project, Thumbnail(64, 64, ByteArray(64 * 64 * 4) { 0x7f }))

        val entries = java.util.zip.ZipFile(project).use { zip -> zip.entries().asSequence().map { it.name }.toSet() }
        assertTrue("Metadata/plate_1.png" in entries)
        assertTrue("Metadata/project_settings.config" in entries)

        // Outro fabricante carregado: abrir o projeto volta para o da impressora dele.
        engine.loadVendor("Prusa")
        assertEquals("BambuStudio/BBL", engine.openProject(project))
        assertEquals("Bambu Lab A1 0.4 nozzle", engine.selected(PresetKind.PRINTER))
        // Um preset por filamento do projeto, o primeiro é o selecionado.
        assertEquals(engine.filamentColors().size, engine.filamentPresets().size)
        assertEquals(engine.selected(PresetKind.FILAMENT), engine.filamentPresets().first())
        val process = engine.values(PresetKind.PROCESS)
        assertEquals("35%", process.values["sparse_infill_density"])
        assertTrue("sparse_infill_density" in process.dirty)
        val reopened = engine.placements()
        assertEquals(placements.size, reopened.size)
        for ((before, after) in placements.zip(reopened))
            for (i in 0 until 16) assertEquals(before.transform[i], after.transform[i], 0.01f)
        assertTrue(engine.slice(File(work, "project.gcode")) { _, _ -> }.filamentMm3 > 0)
    }

    @Test
    fun projectFilamentsAreAddedAssignedAndRemoved() = runBlocking {
        engine.loadVendor("BBL")
        engine.select(PresetKind.PRINTER, "Bambu Lab A1 0.4 nozzle")
        engine.loadModel(testAsset("cube20.stl"))
        engine.addModel(testAsset("voron_cube_v7.stl"))
        // A1 com AMS lite: um extrusor para vários filamentos.
        assertEquals(1..64, engine.filamentLimits())
        assertEquals(1, engine.filamentColors().size)

        engine.addFilament()
        assertEquals(2, engine.filamentColors().size)
        assertEquals(engine.filamentPresets()[0], engine.filamentPresets()[1])
        // Purga calculada pelas cores; nada na troca para o mesmo filamento.
        val flush = engine.flushVolumes()
        assertEquals(4, flush.size)
        assertEquals(0.0, flush[0], 0.0)
        assertTrue(flush[1] > 0 && flush[2] > 0)
        engine.setFilamentColor(1, "#000000")
        assertEquals("#000000", engine.filamentColors()[1])
        assertTrue("claro para preto purga menos que preto para claro", engine.flushVolumes()[1] < engine.flushVolumes()[2])
        engine.setFlushVolumes(doubleArrayOf(0.0, 100.0, 200.0, 0.0))
        assertEquals(200.0, engine.flushVolumes()[2], 0.0)

        engine.setFilamentPreset(1, "Bambu PLA Matte @BBL A1")
        engine.setObjectFilament(1, 1)
        assertEquals(listOf(0, 1), engine.objects().map { it.filament })
        assertEquals(1, engine.objects()[1].filaments.distinct().single().toInt())
        val stats = engine.slice(File(work, "two.gcode")) { _, _ -> }
        assertEquals(listOf(0, 1), stats.filaments.map { it.index })

        // O projeto guarda filamentos, cores e o filamento de cada objeto.
        val project = File(work, "two.3mf")
        engine.saveProject(project, Thumbnail(8, 8, ByteArray(8 * 8 * 4)))
        engine.openProject(project)
        assertEquals(listOf("Bambu PLA Basic @BBL A1", "Bambu PLA Matte @BBL A1"), engine.filamentPresets())
        assertEquals("#000000", engine.filamentColors()[1])
        assertEquals(listOf(0, 1), engine.objects().map { it.filament })

        // Excluir o primeiro: o objeto dele passa para o que era o segundo, agora o primeiro.
        engine.removeFilament(0)
        assertEquals(listOf("Bambu PLA Matte @BBL A1"), engine.filamentPresets())
        assertEquals("Bambu PLA Matte @BBL A1", engine.selected(PresetKind.FILAMENT))
        assertEquals(listOf(0, 0), engine.objects().map { it.filament })
        assertEquals(1, engine.flushVolumes().size)
    }

    // Projetos do Bambu Studio 2.x (MakerWorld) gravam filament_notes como texto, lista no Orca.
    @Test
    fun opensBambuStudioProjectWithTextFilamentNotes() = runBlocking {
        engine.loadVendor("BBL")
        engine.select(PresetKind.PRINTER, "Bambu Lab A1 0.4 nozzle")
        engine.loadModel(testAsset("cube20.stl"))
        val saved = File(work, "saved.3mf")
        engine.saveProject(saved, Thumbnail(64, 64, ByteArray(64 * 64 * 4) { 0x7f }))

        val project = File(work, "bambu-studio.3mf")
        java.util.zip.ZipFile(saved).use { zip ->
            java.util.zip.ZipOutputStream(project.outputStream()).use { out ->
                for (entry in zip.entries()) {
                    out.putNextEntry(java.util.zip.ZipEntry(entry.name))
                    var bytes = zip.getInputStream(entry).use { it.readBytes() }
                    if (entry.name == "Metadata/project_settings.config")
                        bytes = Regex("\"filament_notes\": \\[[^\\]]*\\]").replace(bytes.decodeToString(), "\"filament_notes\": \"\"").encodeToByteArray()
                    out.write(bytes)
                    out.closeEntry()
                }
            }
        }
        assertTrue("\"filament_notes\": \"\"" in java.util.zip.ZipFile(project).use { zip ->
            zip.getInputStream(zip.getEntry("Metadata/project_settings.config")).use { it.readBytes().decodeToString() }
        })
        assertEquals("BambuStudio/BBL", engine.openProject(project))
        assertEquals(1, engine.placements().size)
    }

    @Test
    fun gcodeHasThumbnailsOfPrinterProfile() = runBlocking {
        engine.loadVendor("BBL")
        engine.select(PresetKind.PRINTER, "Bambu Lab A1 0.4 nozzle")
        assertTrue(engine.gcodeThumbnailSizes().isEmpty()) // Bambu: configuração no lugar

        engine.loadVendor("Prusa")
        engine.select(PresetKind.PRINTER, engine.presets(PresetKind.PRINTER).first { it.startsWith("Prusa MK4") })
        val sizes = engine.gcodeThumbnailSizes()
        assertTrue(sizes.isNotEmpty())
        engine.loadModel(testAsset("cube20.stl"))
        val gcode = File(work, "thumb.gcode")
        engine.slice(gcode, sizes.map { (w, h) -> Thumbnail(w, h, ByteArray(w * h * 4) { 0x40 }) }) { _, _ -> }
        val text = gcode.readText()
        for ((w, h) in sizes) assertTrue("${w}x$h", Regex("; thumbnail(_[A-Z]+)? begin ${w}x$h ").containsMatchIn(text))
    }

    @Test
    fun loadsGcodeForPreview() = runBlocking {
        engine.loadVendor("BBL")
        engine.select(PresetKind.PRINTER, "Bambu Lab A1 0.4 nozzle")
        engine.loadModel(testAsset("cube20.stl"))
        val gcode = File(work, "cube20.gcode")
        val sliced = engine.slice(gcode) { _, _ -> }

        engine.close()
        engine = SlicerEngine.create(OrcaResources.install(context), File(work, "data"))
        val loaded = engine.loadGcode(gcode)
        assertEquals(sliced.printTimeSeconds, loaded.printTimeSeconds, sliced.printTimeSeconds * 0.01)
        assertEquals(sliced.filamentGrams, loaded.filamentGrams, sliced.filamentGrams * 0.01)
        assertEquals(sliced.cost, loaded.cost, sliced.cost * 0.01)
        assertEquals(100, engine.toolpaths().layerCount)
    }

    // Regressão: com uma thread nativa por motor, motores criados depois do primeiro
    // falhavam ao fatiar ("Coordinate outside allowed range") na maioria das vezes.
    @Test
    fun consecutiveEnginesSlice() = runBlocking {
        val model = testAsset("cube20.stl")
        repeat(4) { i ->
            engine.close()
            engine = SlicerEngine.create(OrcaResources.install(context), File(work, "data"))
            engine.loadVendor("BBL")
            engine.loadModel(model)
            assertTrue(engine.slice(File(work, "cube$i.gcode")) { _, _ -> }.filamentMm3 > 0)
        }
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
    // SlicerEngine.close() destrói o motor na thread dele, e o Model nativo apaga seus
    // temporários nisso, possivelmente durante a varredura: tenta de novo.
    repeat(5) {
        if (!toPath().let { Files.exists(it) || Files.isSymbolicLink(it) }) return
        try {
            Files.walk(toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        } catch (e: java.io.IOException) {
            Thread.sleep(100)
        } catch (e: java.io.UncheckedIOException) {
            Thread.sleep(100)
        }
    }
}
