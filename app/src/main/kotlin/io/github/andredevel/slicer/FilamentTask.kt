package io.github.andredevel.slicer

import io.github.andredevel.slicer.send.Slot

/**
 * Carga ([load]) ou descarga de [slot] na fila de filamento de uma impressora
 * ([SlicerViewModel.queueFilament]); [error] com o motivo quando [State.FAILED].
 */
data class FilamentTask(val id: Long, val slot: Slot, val load: Boolean, val state: State = State.PENDING, val error: String? = null) {
    enum class State { PENDING, RUNNING, DONE, FAILED }
}
