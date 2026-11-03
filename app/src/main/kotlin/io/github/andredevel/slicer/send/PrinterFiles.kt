package io.github.andredevel.slicer.send

import android.util.Base64

/** Arquivo de G-code guardado na impressora; [modified] em ms desde 1970. */
data class PrinterFile(val path: String, val modified: Long, val size: Long) {
    val name get() = path.substringAfterLast('/')
}

/**
 * O que o app lê do próprio arquivo ([GcodeInfo.parse]): a miniatura do cabeçalho e, do rodapé
 * do Orca, o tempo estimado, o gasto e os filamentos usados. Não depende dos metadados do
 * Moonraker, que a K3 não preenche (o dela não reconhece o Orca).
 */
class GcodeInfo(val thumbnail: ByteArray?, val seconds: Long?, val grams: Double?, val filaments: List<GcodeFilament>) {
    companion object {
        /** Quanto ler do começo (miniaturas) e do fim (rodapé e configuração) do arquivo. */
        const val HEAD_BYTES = 64 * 1024
        const val TAIL_BYTES = 128 * 1024

        private val THUMBNAIL = Regex("""^; thumbnail(_JPG)? begin (\d+)x(\d+) \d+""")
        private val TIME_PART = Regex("""(\d+)\s*([dhms])""")

        /** Miniatura maior até [MAX_THUMBNAIL] de largura (a menor, se todas passam), PNG ou JPG. */
        private const val MAX_THUMBNAIL = 400

        fun parse(head: String, tail: String): GcodeInfo {
            val footer = footer(tail)
            fun list(key: String) = footer[key]?.split(',', ';')?.map { it.trim() }.orEmpty()
            val grams = list("filament used [g]").map { it.toDoubleOrNull() ?: 0.0 }
            val colors = list("filament_colour")
            val types = list("filament_type")
            val filaments = grams.withIndex().filter { it.value > 0 }.map { (i, g) ->
                GcodeFilament(i, colors.getOrNull(i)?.takeIf { COLOR.matches(it) }?.uppercase(), types.getOrNull(i)?.takeIf { it.isNotEmpty() }, g)
            }
            return GcodeInfo(
                thumbnail(head),
                footer["estimated printing time (normal mode)"]?.let(::seconds),
                footer["total filament used [g]"]?.toDoubleOrNull() ?: grams.sum().takeIf { grams.isNotEmpty() },
                filaments,
            )
        }

        private val COLOR = Regex("#[0-9A-Fa-f]{6}")

        /** `; chave = valor` do rodapé e da configuração que o Orca escreve no fim. */
        private fun footer(tail: String): Map<String, String> {
            val values = HashMap<String, String>()
            for (line in tail.lineSequence()) {
                if (!line.startsWith("; ")) continue
                val equals = line.indexOf(" = ")
                if (equals < 0) continue
                values.putIfAbsent(line.substring(2, equals).trim(), line.substring(equals + 3).trim())
            }
            return values
        }

        /** "1d 2h 27m 2s" em segundos. */
        private fun seconds(text: String): Long? {
            val parts = TIME_PART.findAll(text).toList()
            if (parts.isEmpty()) return null
            return parts.sumOf { m ->
                val n = m.groupValues[1].toLong()
                when (m.groupValues[2]) {
                    "d" -> n * 86_400
                    "h" -> n * 3_600
                    "m" -> n * 60
                    else -> n
                }
            }
        }

        private fun thumbnail(head: String): ByteArray? {
            class Block(val width: Int, val data: String)
            val blocks = ArrayList<Block>()
            val lines = head.lineSequence().iterator()
            while (lines.hasNext()) {
                val match = THUMBNAIL.find(lines.next()) ?: continue
                val data = StringBuilder()
                var closed = false
                while (lines.hasNext()) {
                    val line = lines.next()
                    if (line.startsWith("; thumbnail") && line.contains(" end")) {
                        closed = true
                        break
                    }
                    data.append(line.removePrefix(";").trim())
                }
                // Cortado no fim do pedaço lido: não dá para decodificar.
                if (closed) blocks += Block(match.groupValues[2].toInt(), data.toString())
            }
            val chosen = blocks.filter { it.width <= MAX_THUMBNAIL }.maxByOrNull { it.width } ?: blocks.minByOrNull { it.width } ?: return null
            return try {
                Base64.decode(chosen.data, Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}

/** Filamento [index] (do projeto, 0 = T0/T1A) usado no arquivo, com a cor `#RRGGBB` e o tipo do perfil. */
data class GcodeFilament(val index: Int, val color: String?, val type: String?, val grams: Double)

/** Impressão do histórico do Moonraker; horas em ms desde 1970, tempos em segundos. */
data class PrintJob(
    val id: String, val file: String, val state: State, val start: Long, val end: Long?,
    val printSeconds: Long, val filamentMm: Double, val exists: Boolean,
) {
    enum class State { COMPLETED, CANCELLED, ERROR, INTERRUPTED, IN_PROGRESS }
}

/** As últimas impressões e os totais de todas. */
data class PrintHistory(val jobs: List<PrintJob>, val totalJobs: Int, val printSeconds: Long, val filamentMm: Double, val longestSeconds: Long)
