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
import org.junit.Assert.assertThrows
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

    // Como no Bambu Studio, trocar de impressora (mesmo de fabricante) mantém a altura de camada,
    // as alterações do processo e o tipo de cada filamento do projeto.
    @Test
    fun switchingPrinterKeepsProjectChoices() = runBlocking {
        engine.loadVendor("BBL")
        engine.select(PresetKind.PRINTER, "Bambu Lab A1 0.4 nozzle")
        engine.setOption(PresetKind.PROCESS, "wall_loops", "5")
        engine.setFilamentPreset(0, "Bambu PETG HF @BBL A1")

        engine.select(PresetKind.PRINTER, "Bambu Lab X1 Carbon 0.4 nozzle")
        assertEquals("0.20mm Standard @BBL X1C", engine.selected(PresetKind.PROCESS))
        assertEquals("5", engine.values(PresetKind.PROCESS).values["wall_loops"])
        assertEquals("Bambu PETG HF @BBL X1C", engine.filamentPresets()[0])

        engine.loadVendor("CrealityPrint/Creality")
        engine.select(PresetKind.PRINTER, "Creality K2 0.4 nozzle")
        assertEquals("0.2", engine.values(PresetKind.PROCESS).values["layer_height"])
        assertEquals("5", engine.values(PresetKind.PROCESS).values["wall_loops"])
        assertEquals(listOf("PETG"), engine.filamentTypes().take(1))
    }

    // O processo de um projeto aberto é escolha do criador, mesmo sendo o padrão da impressora:
    // no bico 0.2 vai para a altura mais próxima (0.14), não para o padrão dele (0.10).
    @Test
    fun projectProcessIsKeptOnPrinterSwitch() = runBlocking {
        engine.loadVendor("BBL")
        engine.select(PresetKind.PRINTER, "Bambu Lab A1 0.4 nozzle")
        engine.loadModel(testAsset("cube20.stl"))
        val project = File(work, "a1.3mf")
        engine.saveProject(project, Thumbnail(8, 8, ByteArray(8 * 8 * 4)))
        engine.openProject(project)

        engine.select(PresetKind.PRINTER, "Bambu Lab A1 0.2 nozzle")
        assertEquals("0.14mm Extra Draft @BBL A1 0.2 nozzle", engine.selected(PresetKind.PROCESS))
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

    // screw.step: exemplo que acompanha o OpenCASCADE (data/step), malhado como no Orca.
    @Test
    fun stepModelIsMeshedAndSliced() = runBlocking {
        engine.loadVendor("BBL")
        engine.select(PresetKind.PRINTER, "Bambu Lab A1 0.4 nozzle")
        engine.loadModel(testAsset("screw.step"))
        val mesh = engine.objects().single()
        assertTrue(mesh.indices.size / 3 > 100)
        assertEquals(0f, worldVertices(mesh, engine.placements().single()).minOf { it[2] }, 0.01f)
        assertTrue(engine.slice(File(work, "screw.gcode")) { _, _ -> }.filamentMm3 > 0)
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
    fun objectAndRangeSettingsChangeSlicingAndAreSavedInProject() = runBlocking {
        engine.loadVendor("BBL")
        engine.select(PresetKind.PRINTER, "Bambu Lab A1 0.4 nozzle")
        engine.loadModel(testAsset("cube20.stl"))
        val layers = { gcode: File -> gcode.readLines().count { it.startsWith("; CHANGE_LAYER") } }
        val base = File(work, "base.gcode").also { engine.slice(it) { _, _ -> } }
        assertEquals(100, layers(base))

        val objectKeys = engine.settingsKeys(SettingsTarget.Kind.OBJECT)
        val volumeKeys = engine.settingsKeys(SettingsTarget.Kind.VOLUME)
        val rangeKeys = engine.settingsKeys(SettingsTarget.Kind.RANGE)
        assertTrue("enable_support" in objectKeys && "enable_support" !in volumeKeys && "enable_support" !in rangeKeys)
        assertTrue("layer_height" in objectKeys && "layer_height" in rangeKeys && "layer_height" !in volumeKeys)
        assertTrue("wall_loops" in volumeKeys && "extruder" !in objectKeys)

        val obj = SettingsTarget(0)
        engine.setSetting(obj, "sparse_infill_density", "40%")
        assertThrows(SlicerException::class.java) { runBlocking { engine.setSetting(obj, "wall_loops", "abc") } }
        assertThrows(SlicerException::class.java) { runBlocking { engine.setSetting(SettingsTarget(0, SettingsTarget.Kind.VOLUME), "enable_support", "1") } }
        assertEquals(mapOf("sparse_infill_density" to "40%"), engine.settings(obj))

        assertEquals(0, engine.addHeightRange(0, HeightRange(14.0, 20.0)))
        assertThrows(SlicerException::class.java) { runBlocking { engine.addHeightRange(0, HeightRange(10.0, 15.0)) } }
        assertEquals(0, engine.addHeightRange(0, HeightRange(2.0, 6.0)))
        val range = SettingsTarget(0, SettingsTarget.Kind.RANGE, 1)
        // A faixa nova vem com a altura de camada do processo, como na GUI.
        assertEquals("0.2", engine.settings(range)["layer_height"])
        engine.setSetting(range, "layer_height", "0.1")
        engine.removeHeightRange(0, 0)
        assertEquals(listOf(HeightRange(14.0, 20.0)), engine.heightRanges(0))
        assertEquals(0, engine.setHeightRange(0, 0, HeightRange(10.0, 20.0)))
        assertEquals("0.1", engine.settings(SettingsTarget(0, SettingsTarget.Kind.RANGE, 0))["layer_height"])

        // 0-10 mm em 0.2 e 10-20 mm em 0.1; o objeto com 40% de preenchimento.
        val changed = File(work, "changed.gcode").also { engine.slice(it) { _, _ -> } }
        assertEquals(150, layers(changed))
        assertTrue(changed.readText().length > base.readText().length)

        val project = File(work, "settings.3mf")
        engine.saveProject(project, Thumbnail(8, 8, ByteArray(8 * 8 * 4)))
        engine.loadModel(testAsset("cube20.stl"))
        engine.openProject(project)
        assertEquals(listOf(HeightRange(10.0, 20.0)), engine.heightRanges(0))
        assertEquals("40%", engine.settings(obj)["sparse_infill_density"])
        engine.eraseSetting(obj, "sparse_infill_density")
        assertTrue(engine.settings(obj).isEmpty())
    }

    @Test
    fun partsAreAddedMovedAndRemovedAndChangeSlicing() = runBlocking {
        engine.loadVendor("BBL")
        engine.select(PresetKind.PRINTER, "Bambu Lab A1 0.4 nozzle")
        engine.loadModel(testAsset("cube20.stl"))
        suspend fun volume(name: String) = engine.slice(File(work, "$name.gcode")) { _, _ -> }.filamentMm3
        val base = volume("base")
        val size = engine.placements()[0].size.copyOf()
        assertEquals(listOf(Part.Type.MODEL), engine.parts(0).map { it.type })
        assertTrue(engine.parts(0)[0].positions.isEmpty())

        // Um décimo do maior lado da mesa (256 mm), fora do objeto: não muda o objeto.
        assertEquals(1, engine.addPart(0, Part.Type.MODIFIER, Part.Shape.CUBE))
        val modifier = engine.parts(0)[1]
        assertTrue(modifier.positions.isNotEmpty() && modifier.name.isNotEmpty())
        for (axis in 0..2) assertEquals(25.6f, modifier.placement.size[axis], 0.01f)
        for (axis in 0..2) assertEquals(size[axis], engine.placements()[0].size[axis], 0.01f)

        // No centro do cubo, com metade do tamanho e preenchimento cheio.
        val half = floatArrayOf(0.5f, 0.5f, 0.5f)
        engine.setPartPlacement(0, 1, FloatArray(3), FloatArray(3), half)
        engine.setSetting(SettingsTarget(0, SettingsTarget.Kind.VOLUME, 1), "sparse_infill_density", "100%")
        val filled = volume("modifier")
        assertTrue("$filled > $base", filled > base)

        // Volumes negativos vêm antes dos modificadores, como no Orca.
        assertEquals(1, engine.addPart(0, Part.Type.NEGATIVE, Part.Shape.CYLINDER))
        assertEquals(listOf(Part.Type.MODEL, Part.Type.NEGATIVE, Part.Type.MODIFIER), engine.parts(0).map { it.type })
        val transform = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
        engine.setPartTransform(0, 1, transform)
        assertTrue(volume("negative") < filled)

        assertThrows(SlicerException::class.java) { runBlocking { engine.removePart(0, 0) } }
        val project = File(work, "parts.3mf")
        engine.saveProject(project, Thumbnail(8, 8, ByteArray(8 * 8 * 4)))
        engine.loadModel(testAsset("cube20.stl"))
        engine.openProject(project)
        val reopened = engine.parts(0)
        assertEquals(listOf(Part.Type.MODEL, Part.Type.NEGATIVE, Part.Type.MODIFIER), reopened.map { it.type })
        assertEquals(0.5f, reopened[2].placement.scale[0], 0.001f)
        assertEquals("100%", engine.settings(SettingsTarget(0, SettingsTarget.Kind.VOLUME, 2))["sparse_infill_density"])

        // Como no Orca: a peça vai para o lugar do novo tipo.
        assertEquals(1, engine.setPartType(0, 2, Part.Type.MODEL))
        assertEquals(listOf(Part.Type.MODEL, Part.Type.MODEL, Part.Type.NEGATIVE), engine.parts(0).map { it.type })

        engine.removePart(0, 1)
        engine.removePart(0, 1)
        assertEquals(listOf(Part.Type.MODEL), engine.parts(0).map { it.type })
        // A última peça do objeto não muda de tipo.
        assertThrows(SlicerException::class.java) { runBlocking { engine.setPartType(0, 0, Part.Type.MODIFIER) } }

        // De arquivo: o mesmo STL do objeto fica exatamente sobre ele (mesma posição no arquivo).
        assertEquals(1, engine.addPartFromFile(0, Part.Type.MODIFIER, testAsset("cube20.stl")))
        val loaded = engine.parts(0)[1]
        assertEquals("cube20.stl", loaded.name)
        for (axis in 0..2) {
            assertEquals(0f, loaded.placement.offset[axis], 0.001f)
            assertEquals(20f, loaded.placement.size[axis], 0.01f)
        }
        engine.removePart(0, 1)
        // Sem o modificador de 100%; não igual ao base, porque o projeto reaberto não fica na mesma posição.
        assertTrue(volume("removed") < filled)
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
        // Dois filamentos nos objetos: torre de purga, que o usuário pode mover.
        val tower = engine.wipeTower()!!
        assertTrue(tower.width > 0 && tower.depth > 0)
        assertEquals(engine.placements().maxOf { it.size[2] }, tower.height, 0.01f)
        engine.setWipeTowerPosition(40f, 50f)
        assertEquals(40f, engine.wipeTower()!!.x, 0.001f)
        assertEquals(50f, engine.wipeTower()!!.y, 0.001f)
        val stats = engine.slice(File(work, "two.gcode")) { _, _ -> }
        assertEquals(listOf(0, 1), stats.filaments.map { it.index })
        // A torre sai onde foi posta, e cada segmento tem o filamento e a cor dele.
        val toolpaths = engine.toolpaths()
        val prime = SlicerEngine.roleNames.indexOf("Prime tower")
        val towerX = (0 until toolpaths.segmentCount).filter { toolpaths.roles[it].toInt() == prime }.map { toolpaths.extrusions[it * 9] }
        assertTrue("torre em x ${towerX.minOrNull()}", towerX.isNotEmpty() && towerX.min() > 25f && towerX.min() < 60f)
        assertEquals(setOf(0, 1), toolpaths.filaments.map { it.toInt() }.toSet())
        assertEquals("#000000", toolpaths.filamentColors[1].uppercase())

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

    // A K2 (bico único com CFS) purga pela matriz a cada troca, fora do G-code; como no Creality
    // Print, essa purga entra no filamento usado. A K3 tem um bico por filamento e não purga.
    @Test
    fun crealityK2CountsTheFirmwarePurgeOfEachChange() = runBlocking {
        suspend fun twoColors(printer: String, name: String): Pair<SliceStats, File> {
            engine.loadVendor("CrealityPrint/Creality")
            engine.select(PresetKind.PRINTER, printer)
            engine.loadModel(testAsset("cube20.stl"))
            engine.addModel(testAsset("voron_cube_v7.stl"))
            if (engine.filamentColors().size < 2) engine.addFilament()
            engine.setObjectFilament(1, 1)
            val gcode = File(work, "$name.gcode")
            return engine.slice(gcode) { _, _ -> } to gcode
        }
        // Volume extrudado no G-code, do rodapé "; filament used [cm3] = a, b".
        fun extruded(gcode: File) = gcode.useLines { lines ->
            lines.first { it.startsWith("; filament used [cm3] =") }.substringAfter("=").split(",").sumOf { it.trim().toDouble() } * 1000
        }

        val (k2, k2Gcode) = twoColors("Creality K2 0.4 nozzle", "k2")
        // Multiplicador do Creality Print para a impressora (default_flush_multiplier), não o 0,3 do Orca.
        assertEquals(1.3, engine.flushMultipliers()[0], 1e-9)
        val flush = engine.flushVolumes()
        val tools = k2Gcode.useLines { lines -> lines.filter { Regex("T\\d+").matches(it) }.map { it.drop(1).toInt() }.toList() }
        // O firmware purga a matriz vezes o multiplicador (flush_para no metadata.py da K2).
        val purge = tools.zipWithNext().filter { (a, b) -> a != b }.sumOf { (a, b) -> flush[a * 2 + b] } * engine.flushMultipliers()[0]
        assertTrue("trocas: $tools", purge > 0)
        assertEquals("extruded ${extruded(k2Gcode)} purge $purge tools ${tools.size} flush ${flush.toList()}", extruded(k2Gcode) + purge, k2.filamentMm3, 10.0)

        val (k3, k3Gcode) = twoColors("Creality K3", "k3")
        assertEquals(extruded(k3Gcode), k3.filamentMm3, 10.0)
    }

    @Test
    fun crealityGcodeHasTheColorOfEachUsedFilament() = runBlocking {
        engine.loadVendor("CrealityPrint/Creality")
        engine.select(PresetKind.PRINTER, "Creality K3")
        engine.loadModel(testAsset("cube20.stl"))
        engine.setFilamentColor(0, "#000000")
        engine.setFilamentColor(1, "#26EEEE")
        val gcode = File(work, "k3.gcode")
        engine.slice(gcode) { _, _ -> }
        // O firmware da K3 escolhe o primeiro bico por esta linha (como o Creality Print grava).
        val line = gcode.useLines { lines -> lines.first { it.startsWith("; default_filament_colour =") } }
        assertEquals("; default_filament_colour = #000000;;;", line)
        // E só lê esse campo de arquivos que ele reconhece como da Creality, não do OrcaSlicer.
        val header = gcode.useLines { it.take(4).toList() }
        assertTrue(header.toString(), header[1].startsWith("; generated by PocketSlicer on "))
        assertEquals("; printer_model: Creality K3", header[2])
    }

    @Test
    fun printerDefaultBedTypeByName() = runBlocking {
        // O Bambu Studio grava default_bed_type pelo nome; o Orca 2.4.2 só lia o número.
        engine.loadVendor("BambuStudio/BBL")
        engine.select(PresetKind.PRINTER, "Bambu Lab A1 0.4 nozzle")
        engine.loadModel(testAsset("cube20.stl"))
        val gcode = File(work, "a1.gcode")
        engine.slice(gcode) { _, _ -> }
        assertEquals("; curr_bed_type = Textured PEI Plate", gcode.useLines { lines -> lines.first { it.startsWith("; curr_bed_type =") } })
    }

    @Test
    fun bedTypeFollowsPrinterModel() = runBlocking {
        // O modelo Qidi Q2 declara not_support_bed_type "Engineering Plate;Supertack Plate".
        engine.loadVendor("QIDIStudio/Q Series")
        engine.select(PresetKind.PRINTER, "Q2 0.4 nozzle")
        val types = engine.bedTypes()
        assertEquals(listOf("Cool Plate", "High Temp Plate", "Textured PEI Plate", "Textured Cool Plate"), types.values)
        assertEquals(types.values.size, types.labels.size)
        assertTrue(types.current in types.values)
        assertThrows(SlicerException::class.java) { runBlocking { engine.setBedType("Engineering Plate") } }

        engine.setBedType("Cool Plate")
        assertEquals("Cool Plate", engine.bedTypes().current)
        engine.loadModel(testAsset("cube20.stl"))
        val gcode = File(work, "q2-cool.gcode")
        engine.slice(gcode) { _, _ -> }
        assertTrue(gcode.useLines { lines -> lines.any { it == "; curr_bed_type = Cool Plate" } })

        // Trocar de impressora volta para o tipo padrão dela.
        engine.select(PresetKind.PRINTER, "Q2 0.4 nozzle")
        assertTrue(engine.bedTypes().current in engine.bedTypes().values)
    }

    @Test
    fun qidiStudioQ2Slices() = runBlocking {
        // O G-code inicial do Q2 no QIDI Studio usa [chamber_temperatures], que no Orca é chamber_temperature.
        engine.loadVendor("QIDIStudio/Q Series")
        engine.select(PresetKind.PRINTER, "Q2 0.4 nozzle")
        engine.loadModel(testAsset("cube20.stl"))
        val gcode = File(work, "q2.gcode")
        engine.slice(gcode) { _, _ -> }
        assertTrue(gcode.useLines { lines -> lines.any { it.startsWith("PRINT_START ") && " CHAMBER=" in it } })
        // Torre da Bambu, como no QIDI Studio: a da Prusa puxa o filamento antes do corte.
        assertTrue(gcode.useLines { lines -> lines.any { it == "; wipe_tower_type = type1" } })
        // Como o QIDI Studio (is_support_3mf): a Q2 recebe o .gcode.3mf, com a miniatura.
        assertTrue(engine.gcode3mf())
    }

    @Test
    fun qidiStudioQ2ChangesFilamentLikeQidiStudio() = runBlocking {
        engine.loadVendor("QIDIStudio/Q Series")
        engine.select(PresetKind.PRINTER, "Q2 0.4 nozzle")
        engine.loadModel(testAsset("cube20.stl"))
        engine.addModel(testAsset("cube20.stl"))
        engine.addFilament()
        engine.setObjectFilament(1, 1)
        val gcode = File(work, "q2-two-colors.gcode")
        engine.slice(gcode) { _, _ -> }
        val lines = gcode.readLines()
        // O filamento inicial já está carregado depois do G-code inicial: a primeira troca é
        // para o outro filamento, já com a peça começada (o Orca cortava e descarregava o
        // filamento inicial antes da primeira camada).
        val firstLayer = lines.indexOfFirst { it.startsWith(";LAYER_CHANGE") }
        val changes = lines.withIndex().filter { it.value.startsWith("TOOL_CHANGE_START ") }
        assertTrue(changes.isNotEmpty())
        assertTrue(changes.first().index > firstLayer)
        assertTrue(changes.none { it.value == "TOOL_CHANGE_START F=0 T=0" || it.value == "TOOL_CHANGE_START F=1 T=1" })
        assertEquals(changes.size, lines.count { it.startsWith("CUT_FILAMENT ") })
        // Antes do corte só as retrações do G-code de troca do QIDI Studio, sem o ramming da
        // torre da Prusa.
        assertTrue(lines.none { it.startsWith("; Ramming") || it.startsWith("; Retract(unload)") })
    }

    @Test
    fun crealityPrintK2Slices() = runBlocking {
        // O G-code inicial e o de troca da K2 no Creality Print testam multicolor_method,
        // flush_into_skeleton e flush_length_5, opções que o Orca não tem.
        engine.loadVendor("CrealityPrint/Creality")
        engine.select(PresetKind.PRINTER, "Creality K2 0.4 nozzle")
        engine.loadModel(testAsset("cube20.stl"))
        val gcode = File(work, "k2.gcode")
        engine.slice(gcode) { _, _ -> }
        assertTrue(gcode.useLines { lines -> lines.any { it.startsWith("START_PRINT ") } })
        assertTrue(gcode.useLines { lines -> lines.any { it == "; wipe_tower_type = type1" } })
    }

    // Projeto salvo pelo Creality Print com o G-code da impressora alterado: o do projeto vale,
    // e ele testa multicolor_method como o dos perfis.
    @Test
    fun opensCrealityPrintProjectWithItsOwnGcodeVariables() = runBlocking {
        engine.loadVendor("CrealityPrint/Creality")
        engine.select(PresetKind.PRINTER, "Creality K2 0.4 nozzle")
        engine.loadModel(testAsset("cube20.stl"))
        val saved = File(work, "saved.3mf")
        engine.saveProject(saved, Thumbnail(8, 8, ByteArray(8 * 8 * 4)))

        val project = File(work, "creality-print.3mf")
        java.util.zip.ZipFile(saved).use { zip ->
            java.util.zip.ZipOutputStream(project.outputStream()).use { out ->
                for (entry in zip.entries()) {
                    out.putNextEntry(java.util.zip.ZipEntry(entry.name))
                    var bytes = zip.getInputStream(entry).use { it.readBytes() }
                    if (entry.name == "Metadata/project_settings.config") {
                        val config = org.json.JSONObject(bytes.decodeToString())
                        config.put("machine_start_gcode", "{if multicolor_method}M117 multicolor\n{endif}M117 project start")
                        // Processo, o filamento e a impressora.
                        config.put("different_settings_to_system", org.json.JSONArray(listOf("", "", "machine_start_gcode")))
                        bytes = config.toString().encodeToByteArray()
                    }
                    out.write(bytes)
                    out.closeEntry()
                }
            }
        }
        assertEquals("CrealityPrint/Creality", engine.openProject(project))
        val gcode = File(work, "project.gcode")
        engine.slice(gcode) { _, _ -> }
        val lines = gcode.readLines()
        assertTrue("M117 project start" in lines)
        assertTrue("M117 multicolor" !in lines)
    }

    @Test
    fun otherVendorKeepsProjectFilaments() = runBlocking {
        engine.loadVendor("BBL")
        engine.select(PresetKind.PRINTER, "Bambu Lab A1 0.4 nozzle")
        engine.addFilament()
        engine.setFilamentColor(1, "#000000")
        // Como trocar de impressora no Orca: os filamentos do projeto continuam, com as cores.
        engine.loadVendor("QIDIStudio/Q Series")
        engine.select(PresetKind.PRINTER, "Q2 0.4 nozzle")
        assertEquals(2, engine.filamentPresets().size)
        assertEquals("#000000", engine.filamentColors()[1])
        assertEquals(2 * 2, engine.flushVolumes().size)
    }

    @Test
    fun printerWithMoreExtrudersGetsAColorPerFilament() = runBlocking {
        engine.loadVendor("Prusa")
        engine.select(PresetKind.PRINTER, "Prusa XL 0.4 nozzle")
        assertEquals(1, engine.filamentColors().size)
        // XL 5T: um filamento por cabeça; as cores acompanham, e cada uma pode mudar.
        engine.select(PresetKind.PRINTER, "Prusa XL 5T 0.4 nozzle")
        assertEquals(5, engine.filamentPresets().size)
        assertEquals(5, engine.filamentColors().size)
        engine.setFilamentColor(4, "#000000")
        assertEquals("#000000", engine.filamentColors()[4])
        // Uma matriz 5 x 5 por bico.
        assertEquals(5 * 25, engine.flushVolumes().size)
    }

    @Test
    fun layerChangesPauseAndSwitchFilament() = runBlocking {
        engine.loadVendor("BBL")
        engine.select(PresetKind.PRINTER, "Bambu Lab A1 0.4 nozzle")
        engine.loadModel(testAsset("cube20.stl"))
        // Um filamento só: só pausa e G-code.
        assertTrue(!engine.filamentChangeAvailable())
        engine.addFilament()
        assertTrue(engine.filamentChangeAvailable())

        engine.setLayerChanges(
            listOf(
                LayerChange(10f, LayerChange.Action.FILAMENT_CHANGE, filament = 1),
                LayerChange(5f, LayerChange.Action.PAUSE),
                LayerChange(15f, LayerChange.Action.CUSTOM, gcode = "M117 teste"),
            ),
        )
        assertEquals(listOf(5f, 10f, 15f), engine.layerChanges().map { it.z })
        val gcode = File(work, "changes.gcode")
        engine.slice(gcode) { _, _ -> }
        val text = gcode.readText()
        assertTrue("pausa da A1", "M400 U1" in text)
        assertTrue("M117 teste" in text)
        // Abaixo de 10 mm, filamento 1; acima, o 2.
        val toolpaths = engine.toolpaths()
        val wall = SlicerEngine.roleNames.indexOf("Outer wall")
        val walls = (0 until toolpaths.segmentCount).filter { toolpaths.roles[it].toInt() == wall }
        assertEquals(setOf(0), walls.filter { toolpaths.extrusions[it * 9 + 2] < 9.9f }.map { toolpaths.filaments[it].toInt() }.toSet())
        assertEquals(setOf(1), walls.filter { toolpaths.extrusions[it * 9 + 2] > 10.1f }.map { toolpaths.filaments[it].toInt() }.toSet())

        // O projeto guarda as ações; excluir o filamento leva a troca para ele junto.
        val project = File(work, "changes.3mf")
        engine.saveProject(project, Thumbnail(8, 8, ByteArray(8 * 8 * 4)))
        engine.openProject(project)
        assertEquals(3, engine.layerChanges().size)
        engine.removeFilament(1)
        assertEquals(listOf(LayerChange.Action.PAUSE, LayerChange.Action.CUSTOM), engine.layerChanges().map { it.action })
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

    @Test
    fun paintingSupportsMarksFacetsIsSavedAndBlocksSupport() = runBlocking {
        engine.loadVendor("BBL")
        engine.select(PresetKind.PRINTER, "Bambu Lab A1 0.4 nozzle")
        engine.loadModel(testAsset("cube20.stl"))
        // Cubo de 20 mm no centro da mesa (128, 128), de z = 0 a 20; o raio desce no centro dele.
        val down = floatArrayOf(0f, 0f, -1f)
        val above = floatArrayOf(128f, 128f, 100f)
        assertTrue(engine.paint(0, PaintKind.SUPPORT, above, down, Brush(radius = 2.0), strokeStart = true))
        val circle = engine.painted(0, PaintKind.SUPPORT)
        assertTrue(circle.states.isNotEmpty() && circle.states.all { it.toInt() == PaintState.ENFORCE.ordinal })
        // No topo (coordenadas do objeto: centro na origem), dentro do círculo.
        for (v in 0 until circle.positions.size / 3) {
            assertEquals(10f, circle.positions[v * 3 + 2], 0.001f)
            assertTrue(hypotOf(circle.positions[v * 3], circle.positions[v * 3 + 1]) <= 2.01f)
        }
        assertTrue(engine.painted(0, PaintKind.SEAM).states.isEmpty())
        // Fora do objeto não pinta.
        assertTrue(!engine.paint(0, PaintKind.SUPPORT, floatArrayOf(10f, 10f, 100f), down, Brush(), strokeStart = true))

        // Arrastar liga os pontos: uma faixa de 10 mm de comprimento.
        engine.paint(0, PaintKind.SUPPORT, floatArrayOf(123f, 128f, 100f), down, Brush(state = PaintState.BLOCK), strokeStart = true)
        engine.paint(0, PaintKind.SUPPORT, floatArrayOf(133f, 128f, 100f), down, Brush(state = PaintState.BLOCK), strokeStart = false)
        val stroke = engine.painted(0, PaintKind.SUPPORT)
        val blocked = (0 until stroke.states.size).filter { stroke.states[it].toInt() == PaintState.BLOCK.ordinal }
        val xs = blocked.flatMap { t -> (0..2).map { stroke.positions[stroke.indices[t * 3 + it] * 3] } }
        assertTrue("${xs.min()}..${xs.max()}", xs.min() < -5.5f && xs.max() > 5.5f)

        // O preenchimento pega a face de cima inteira (os dois triângulos do STL).
        engine.paint(0, PaintKind.SEAM, above, down, Brush(Brush.Tool.SMART_FILL), strokeStart = true)
        val face = engine.painted(0, PaintKind.SEAM)
        assertEquals(2, face.states.size)

        val project = File(work, "painted.3mf")
        engine.saveProject(project, Thumbnail(8, 8, ByteArray(8 * 8 * 4)))
        engine.loadModel(testAsset("cube20.stl"))
        assertTrue(engine.painted(0, PaintKind.SUPPORT).states.isEmpty())
        engine.openProject(project)
        assertEquals(stroke.states.size, engine.painted(0, PaintKind.SUPPORT).states.size)
        assertEquals(2, engine.painted(0, PaintKind.SEAM).states.size)
        engine.clearPaint(0, PaintKind.SUPPORT)
        assertTrue(engine.painted(0, PaintKind.SUPPORT).states.isEmpty())
        assertEquals(2, engine.painted(0, PaintKind.SEAM).states.size)

        // Bloquear tudo (preenchimento até 90°) tira o suporte automático.
        engine.loadModel(testAsset("calicat.stl"))
        engine.setOption(PresetKind.PROCESS, "enable_support", "1")
        assertEquals(engine.values(PresetKind.PROCESS).values["support_threshold_angle"]!!.toDouble(), engine.overhangHighlightAngle(0), 0.0)
        suspend fun volume(name: String) = engine.slice(File(work, "$name.gcode")) { _, _ -> }.filamentMm3
        val supported = volume("supported")
        engine.setOption(PresetKind.PROCESS, "enable_support", "0")
        val none = volume("none")
        assertEquals(0.0, engine.overhangHighlightAngle(0), 0.0)
        engine.setOption(PresetKind.PROCESS, "enable_support", "1")
        assertTrue(engine.paint(0, PaintKind.SUPPORT, above, down, Brush(Brush.Tool.SMART_FILL, PaintState.BLOCK, smartFillAngle = 90.0), strokeStart = true))
        val blockedVolume = volume("blocked")
        assertTrue("$none < $supported", none < supported)
        assertEquals(none, blockedVolume, none * 0.01)
    }

    @Test
    fun gapFillAndSectionViewWorkLikeOrcaGizmo() = runBlocking {
        engine.loadVendor("BBL")
        engine.select(PresetKind.PRINTER, "Bambu Lab A1 0.4 nozzle")
        engine.loadModel(testAsset("cube20.stl"))
        val down = floatArrayOf(0f, 0f, -1f)
        val above = floatArrayOf(128f, 128f, 100f)
        // Topo forçado inteiro com um ponto bloqueado pequeno no meio.
        engine.paint(0, PaintKind.SUPPORT, above, down, Brush(Brush.Tool.SMART_FILL), strokeStart = true)
        engine.paint(0, PaintKind.SUPPORT, above, down, Brush(state = PaintState.BLOCK, radius = 0.4), strokeStart = true)
        assertTrue(engine.painted(0, PaintKind.SUPPORT).states.any { it.toInt() == PaintState.BLOCK.ordinal })
        // Com área menor que a do ponto, nada; com 2 mm², o ponto vira forçado.
        assertTrue(engine.gaps(0, PaintKind.SUPPORT, 0.01).states.isEmpty())
        val gaps = engine.gaps(0, PaintKind.SUPPORT, 2.0)
        assertTrue(gaps.states.isNotEmpty() && gaps.states.all { it.toInt() == PaintState.ENFORCE.ordinal })
        assertEquals(1, engine.fillGaps(0, PaintKind.SUPPORT, 2.0))
        assertTrue(engine.painted(0, PaintKind.SUPPORT).states.all { it.toInt() == PaintState.ENFORCE.ordinal })
        assertEquals(0, engine.fillGaps(0, PaintKind.SUPPORT, 2.0))

        // Corte que esconde o topo (z > 15 na mesa): o raio passa por ele e pinta a base, por dentro.
        val clip = ClipPlane(floatArrayOf(0f, 0f, 1f), 15f)
        engine.clearPaint(0, PaintKind.SEAM)
        assertTrue(engine.paint(0, PaintKind.SEAM, above, down, Brush(radius = 2.0), strokeStart = true, clip = clip))
        val seam = engine.painted(0, PaintKind.SEAM)
        assertTrue(seam.states.isNotEmpty())
        for (v in 0 until seam.positions.size / 3) assertEquals(-10f, seam.positions[v * 3 + 2], 0.001f)
        // Tudo escondido: não atinge.
        assertTrue(!engine.paint(0, PaintKind.SEAM, above, down, Brush(), strokeStart = true, clip = ClipPlane(floatArrayOf(0f, 0f, 1f), -1f)))
    }

    @Test
    fun paintedFuzzySkinOnlyRoughensThePaintedWall() = runBlocking {
        engine.loadVendor("BBL")
        engine.select(PresetKind.PRINTER, "Bambu Lab A1 0.4 nozzle")
        engine.loadModel(testAsset("cube20.stl"))
        // "Só pintado": sem pintura, nenhuma parede difusa.
        engine.setOption(PresetKind.PROCESS, "fuzzy_skin", "none")
        suspend fun moves(name: String) = File(work, "$name.gcode").also { engine.slice(it) { _, _ -> } }.useLines { lines -> lines.count { it.startsWith("G1 X") } }
        val plain = moves("fuzzy_plain")
        // Raio horizontal na face x = +10 do cubo (x = 138 na mesa); o preenchimento pega a face inteira.
        val side = floatArrayOf(200f, 128f, 10f)
        val left = floatArrayOf(-1f, 0f, 0f)
        assertTrue(runCatching { engine.paint(0, PaintKind.FUZZY_SKIN, side, left, Brush(state = PaintState.BLOCK), strokeStart = true) }.isFailure)
        assertTrue(engine.paint(0, PaintKind.FUZZY_SKIN, side, left, Brush(Brush.Tool.SMART_FILL), strokeStart = true))
        val face = engine.painted(0, PaintKind.FUZZY_SKIN)
        assertEquals(2, face.states.size)
        assertTrue(engine.painted(0, PaintKind.SUPPORT).states.isEmpty())
        val painted = moves("fuzzy_painted")
        assertTrue("$plain < $painted", painted > plain + 100)

        val project = File(work, "fuzzy.3mf")
        engine.saveProject(project, Thumbnail(8, 8, ByteArray(8 * 8 * 4)))
        engine.loadModel(testAsset("cube20.stl"))
        engine.openProject(project)
        assertEquals(2, engine.painted(0, PaintKind.FUZZY_SKIN).states.size)
    }

    @Test
    fun colorPaintingSplitsTheObjectByFilament() = runBlocking {
        engine.loadVendor("BBL")
        engine.select(PresetKind.PRINTER, "Bambu Lab A1 0.4 nozzle")
        engine.loadModel(testAsset("cube20.stl"))
        val down = floatArrayOf(0f, 0f, -1f)
        val above = floatArrayOf(128f, 128f, 100f)
        // Sem pintura: a malha inteira (12 triângulos) no filamento da peça.
        val plain = engine.painted(0, PaintKind.COLOR)
        assertEquals(12, plain.states.size)
        assertTrue(plain.states.all { it.toInt() == 1 })
        // Só há um filamento; as ferramentas das outras pinturas não valem aqui, nem o contrário.
        assertTrue(runCatching { engine.paint(0, PaintKind.COLOR, above, down, Brush(color = 2), strokeStart = true) }.isFailure)
        engine.addFilament()
        assertTrue(runCatching { engine.paint(0, PaintKind.COLOR, above, down, Brush(Brush.Tool.SMART_FILL, color = 2), strokeStart = true) }.isFailure)
        assertTrue(runCatching { engine.paint(0, PaintKind.SUPPORT, above, down, Brush(Brush.Tool.TRIANGLE), strokeStart = true) }.isFailure)

        // Balde: a região de mesma cor ligada ao toque, sem passar de 30° entre faces (a face de cima).
        assertTrue(engine.paint(0, PaintKind.COLOR, above, down, Brush(Brush.Tool.BUCKET_FILL, color = 2), strokeStart = true))
        assertEquals(2, engine.painted(0, PaintKind.COLOR).states.count { it.toInt() == 2 })
        // Sem detecção de bordas, o cubo inteiro; triângulo: só o tocado.
        engine.clearPaint(0, PaintKind.COLOR)
        assertTrue(engine.paint(0, PaintKind.COLOR, above, down, Brush(Brush.Tool.BUCKET_FILL, color = 2, smartFillAngle = -1.0), strokeStart = true))
        assertTrue(engine.painted(0, PaintKind.COLOR).states.all { it.toInt() == 2 })
        assertTrue(engine.paint(0, PaintKind.COLOR, above, down, Brush(Brush.Tool.TRIANGLE, PaintState.NONE), strokeStart = true))
        assertEquals(1, engine.painted(0, PaintKind.COLOR).states.count { it.toInt() == 1 })
        engine.clearPaint(0, PaintKind.COLOR)

        // Faixa de altura de 2 mm a partir do meio (z = 10 na mesa), tocando a face x = +10: as
        // quatro paredes entre 10 e 12 mm, nenhuma parte da base nem do topo.
        assertTrue(engine.paint(0, PaintKind.COLOR, floatArrayOf(200f, 128f, 10f), floatArrayOf(-1f, 0f, 0f), Brush(Brush.Tool.HEIGHT_RANGE, color = 2, height = 2.0), strokeStart = true))
        val band = engine.painted(0, PaintKind.COLOR)
        val banded = (0 until band.states.size).filter { band.states[it].toInt() == 2 }
        val zs = banded.flatMap { t -> (0..2).map { band.positions[band.indices[t * 3 + it] * 3 + 2] } }
        assertTrue("${zs.min()}..${zs.max()}", zs.min() >= -0.01f && zs.max() <= 2.01f)
        val walls = banded.map { t ->
            val v = (0..2).map { band.positions[band.indices[t * 3 + it] * 3] to band.positions[band.indices[t * 3 + it] * 3 + 1] }
            when {
                v.all { it.first > 9.99f } -> "+x"
                v.all { it.first < -9.99f } -> "-x"
                v.all { it.second > 9.99f } -> "+y"
                v.all { it.second < -9.99f } -> "-y"
                else -> "other"
            }
        }
        assertEquals(setOf("+x", "-x", "+y", "-y"), walls.toSet())

        // O fatiamento troca de filamento na faixa, e o 3MF guarda a pintura.
        val gcode = File(work, "color.gcode")
        engine.slice(gcode) { _, _ -> }
        assertTrue(gcode.useLines { lines -> lines.any { it.trim() == "T1" || it.startsWith("M620 S1") } })
        assertTrue(engine.objects()[0].filaments.any { it.toInt() == 1 })
        val project = File(work, "color.3mf")
        engine.saveProject(project, Thumbnail(8, 8, ByteArray(8 * 8 * 4)))
        engine.loadModel(testAsset("cube20.stl"))
        engine.openProject(project)
        assertEquals(banded.size, engine.painted(0, PaintKind.COLOR).states.count { it.toInt() == 2 })
        // Excluir o filamento 2 apaga a pintura dele.
        engine.removeFilament(1)
        assertTrue(engine.painted(0, PaintKind.COLOR).states.all { it.toInt() == 1 })
    }

    @Test
    fun calibrationTestsSliceLikeOrcaMenu() = runBlocking {
        engine.loadVendor("BBL")
        engine.select(PresetKind.PRINTER, "Bambu Lab A1 0.4 nozzle")
        suspend fun gcode(name: String): List<String> = File(work, "$name.gcode").also {
            try {
                engine.slice(it) { _, _ -> }
            } catch (e: SlicerException) {
                throw AssertionError("$name: ${e.message}", e)
            }
        }.readLines()
        suspend fun height() = engine.placements().maxOf { it.size[2].toDouble() }

        // Temperatura de 230 a 190 °C: 9 blocos de 10 mm, um M104 por bloco descendo de 5 em 5.
        engine.calibrate(Calibration(Calibration.Test.TEMPERATURE, start = 230.0, end = 190.0))
        assertEquals(90.0, height(), 0.5)
        val temps = gcode("temp").mapNotNull { Regex("^M104 S(\\d+)").find(it)?.groupValues?.get(1)?.toInt() }.distinct()
        assertTrue("$temps", temps.containsAll((190..230 step 5).toList()) && temps.none { it > 230 })
        assertEquals("230", engine.values(PresetKind.FILAMENT).values["nozzle_temperature"])
        assertTrue(runCatching { engine.calibrate(Calibration(Calibration.Test.TEMPERATURE, start = 230.0, end = 228.0)) }.isFailure)

        // PA torre de 0 a 0,1 em 0,002: 51 mm, um valor de PA por milímetro.
        engine.calibrate(Calibration(Calibration.Test.PRESSURE_ADVANCE_TOWER, start = 0.0, end = 0.1, step = 0.002))
        assertEquals(51.0, height(), 0.5)
        val pa = gcode("pa").filter { it.startsWith("M900 K") }.distinct()
        assertTrue("${pa.size}", pa.size > 40)
        // PA linha: as linhas vêm do gerador, com os números.
        engine.calibrate(Calibration(Calibration.Test.PRESSURE_ADVANCE_LINE, start = 0.0, end = 0.1, step = 0.002, printNumbers = true))
        assertTrue(gcode("pa_line").count { it.startsWith("M900 K") } > 40)
        // PA padrão: uma alça por combinação de velocidade e aceleração, cada uma com os 17 padrões
        // (0 a 0,08 em 0,005) desenhados pelo gerador em cada camada dele.
        engine.calibrate(Calibration(Calibration.Test.PRESSURE_ADVANCE_PATTERN, start = 0.0, end = 0.08, step = 0.005))
        assertEquals(1, engine.objects().size)
        val pattern = gcode("pa_pattern")
        assertTrue(pattern.count { it.startsWith("; start pressure advance pattern for layer") } > 1)
        assertTrue(pattern.filter { it.startsWith("M900 K") }.distinct().size >= 17)
        engine.calibrate(
            Calibration(Calibration.Test.PRESSURE_ADVANCE_PATTERN, start = 0.0, end = 0.08, step = 0.005, speeds = listOf(100.0, 200.0), accelerations = listOf(3000.0, 6000.0))
        )
        assertEquals(4, engine.objects().size)
        val layers = gcode("pa_pattern_batch").count { it.startsWith("; start pressure advance pattern for layer") }
        assertEquals(0, layers % 4)
        assertTrue(engine.layerChanges().isEmpty())
        assertTrue(runCatching {
            engine.calibrate(Calibration(Calibration.Test.PRESSURE_ADVANCE_PATTERN, start = 0.0, end = 0.08, step = 0.005, speeds = listOf(5000.0), accelerations = listOf(3000.0)))
        }.isFailure)

        // Retração de 0 a 2 mm: a altura segue a fórmula do Orca.
        engine.calibrate(Calibration(Calibration.Test.RETRACTION, start = 0.0, end = 2.0, step = 0.1))
        assertEquals(1.0 + 0.4 + 20, height(), 0.5)
        assertTrue(gcode("retraction").any { it.startsWith("; Calib_Retraction_tower:") })

        // Velocidade volumétrica e VFA: modo vaso, a velocidade da parede sobe.
        engine.calibrate(Calibration(Calibration.Test.MAX_VOLUMETRIC_SPEED, start = 5.0, end = 20.0, step = 0.5))
        assertEquals("1", engine.values(PresetKind.PROCESS).values["spiral_mode"])
        gcode("volumetric")
        engine.calibrate(Calibration(Calibration.Test.VFA, start = 40.0, end = 200.0, step = 10.0))
        assertEquals(85.0, height(), 0.5)
        gcode("vfa")

        // Fluxo: os pedaços do 3MF, cada um com o próprio fluxo.
        engine.calibrate(Calibration(Calibration.Test.FLOW_RATE, flowLinear = false, flowPass = 1))
        assertTrue(engine.objects().size > 5)
        engine.calibrate(Calibration(Calibration.Test.FLOW_RATE, flowLinear = true, flowPass = 2, flowMonotonic = true))
        gcode("flow")

        // Input shaping: a A1 (Marlin antigo) recusa, como no Orca; numa Klipper, M593/SET_INPUT_SHAPER por camada.
        assertTrue(runCatching {
            engine.calibrate(Calibration(Calibration.Test.INPUT_SHAPING_FREQUENCY, start = 0.15, freqStartX = 15.0, freqEndX = 110.0, freqStartY = 15.0, freqEndY = 110.0))
            gcode("shaping_a1")
        }.isFailure)
        engine.loadVendor("Voron")
        engine.select(PresetKind.PRINTER, "Voron 2.4 350 0.4 nozzle")
        engine.calibrate(Calibration(Calibration.Test.INPUT_SHAPING_FREQUENCY, start = 0.15, shaperType = "ZV", freqStartX = 15.0, freqEndX = 110.0, freqStartY = 15.0, freqEndY = 110.0))
        assertTrue(gcode("shaping").count { it.startsWith("SET_INPUT_SHAPER") } > 50)
        engine.calibrate(Calibration(Calibration.Test.CORNERING, start = 1.0, end = 15.0, model = 2))
        assertTrue(gcode("cornering").count { it.startsWith("SET_VELOCITY_LIMIT") && "SQUARE_CORNER_VELOCITY" in it } > 50)
        assertTrue(runCatching { engine.calibrate(Calibration(Calibration.Test.CORNERING, start = 1.0, end = 150.0)) }.isFailure)

        // Abrir outro modelo sai do teste: sem M104 por bloco.
        engine.loadModel(testAsset("cube20.stl"))
        assertTrue(gcode("plain").count { it.startsWith("M900 K") } < 3)
    }

    @Test
    fun vendorProfilesDropValuesOutOfOrcaRange() = runBlocking {
        // O Bambu Studio usa -1 (automático) onde o Orca só aceita 0 a 2; o Orca recusaria o 3MF.
        engine.loadVendor("BambuStudio/BBL")
        engine.select(PresetKind.PRINTER, "Bambu Lab A1 0.4 nozzle")
        assertEquals("0", engine.values(PresetKind.PROCESS).values["tree_support_wall_count"])
    }

    private fun hypotOf(x: Float, y: Float) = kotlin.math.hypot(x, y)

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
