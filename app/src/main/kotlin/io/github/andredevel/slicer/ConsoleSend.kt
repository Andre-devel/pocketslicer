package io.github.andredevel.slicer

/**
 * O G-code livre do console de uma impressora ([SlicerViewModel.sendConsoleGcode]): [sending]
 * enquanto a impressora não respondeu; [failed] e [error] guardam o que falhou por último, até
 * ser dispensado. Fica no ViewModel para o comando longo (G28, M109) seguir com a aba fechada.
 */
data class ConsoleSend(val sending: Boolean = false, val failed: String? = null, val error: String? = null)
