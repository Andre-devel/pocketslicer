// Traduções dos textos do libslic3r (opções, progresso, erros) pelos catálogos gettext
// (.po) do OrcaSlicer, como a GUI do Orca faz com wxWidgets.
//
// O catálogo é global ao processo: o libslic3r traduz pelo callback
// Slic3r::I18N::translate_fn, inclusive nas threads do fatiamento. Carregue-o só
// quando nenhum fatiamento estiver em andamento.

#pragma once

#include <string>
#include <vector>

namespace slicer::i18n {

// Substitui o catálogo pelas entradas dos arquivos .po, na ordem (a última vence em
// msgids repetidos). Sem arquivos, os textos ficam como no código (inglês).
// Lança se algum arquivo não puder ser lido.
void load(const std::vector<std::string> &po_files);

// Tradução do msgid, ou o próprio texto se o catálogo não o tiver.
std::string translate(const std::string &text);

} // namespace slicer::i18n
