package io.github.andredevel.slicer

import io.github.andredevel.slicer.send.Maintenance

/**
 * A calibração ou manutenção [task] que a impressora roda ou rodou por último
 * ([SlicerViewModel.runMaintenance]): começou em [startedAt] e terminou em [endedAt]
 * (milissegundos do relógio); [error] com o motivo quando [State.FAILED].
 */
data class MaintenanceRun(
    val task: Maintenance,
    val startedAt: Long,
    val state: State = State.RUNNING,
    val endedAt: Long? = null,
    val error: String? = null,
) {
    enum class State { RUNNING, DONE, FAILED }
}
