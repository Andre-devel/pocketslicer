// Fachada C++ sobre libslic3r (OrcaSlicer) usada pelo bridge JNI e pelo slice_cli.
//
// Reproduz o fluxo da GUI do Orca: os perfis de um fabricante são carregados por
// PresetBundle (herança "inherits", compatibilidade impressora/filamento/processo),
// a configuração final vem de PresetBundle::full_config() e o fatiamento é
// Print::process() + Print::export_gcode().
//
// Não é thread-safe: todas as chamadas devem vir da mesma thread, exceto cancel().
// Erros são lançados como std::exception (slicer::Canceled quando cancelado).

#pragma once

#include <array>
#include <cstdint>
#include <functional>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace slicer {

enum class PresetKind { Printer = 0, Filament = 1, Process = 2 };

struct SliceStats {
    double print_time_s = 0.0;   // estimativa do modo normal
    double filament_mm3 = 0.0;   // volume total extrudado
};

// Área de impressão da impressora selecionada, em mm.
struct Bed {
    std::vector<std::array<double, 2>> shape; // contorno (printable_area), anti-horário
    double max_height = 0.0;                  // printable_height
};

// Malha de um objeto em coordenadas locais (volumes já transformados) mais a
// transformação da instância para a mesa: matriz 4x4 em ordem de coluna (OpenGL).
struct ObjectMesh {
    std::vector<float>    positions; // x, y, z por vértice
    std::vector<uint32_t> indices;   // 3 por triângulo
    std::array<float, 16> transform{};
};

struct Canceled : std::runtime_error {
    Canceled() : std::runtime_error("slicing canceled") {}
};

using ProgressFn = std::function<void(int percent, const std::string &text)>;

class Engine {
public:
    // resources_dir: cópia de resources/ do OrcaSlicer (profiles/, info/, flush/).
    // data_dir: diretório gravável; recebe system/ (symlinks para o fabricante carregado)
    // e user/. Ao apagá-lo, não siga symlinks (File.deleteRecursively() segue) ou os
    // perfis em resources_dir vão junto.
    Engine(const std::string &resources_dir, const std::string &data_dir);
    ~Engine();

    // Fabricantes disponíveis em resources/profiles (sem a biblioteca de filamentos).
    std::vector<std::string> vendors() const;

    // Carrega os perfis do fabricante (mais OrcaFilamentLibrary) e seleciona sua
    // primeira impressora com o processo e o filamento padrão dela.
    void load_vendor(const std::string &vendor);

    // Impressoras do fabricante; filamentos e processos compatíveis com a seleção atual.
    std::vector<std::string> presets(PresetKind kind) const;
    std::string selected(PresetKind kind) const;
    // Trocar a impressora reaplica o processo e o filamento padrão dela quando compatíveis.
    void select(PresetKind kind, const std::string &name);

    Bed bed() const;

    // STL, OBJ ou 3MF (só a geometria). Substitui o modelo anterior e o centraliza
    // na mesa da impressora selecionada; trocar a impressora o recentraliza.
    void load_model(const std::string &path);
    std::vector<ObjectMesh> objects() const;

    // Fatia o modelo na posição atual com os perfis selecionados e grava o G-code em gcode_path.
    SliceStats slice(const std::string &gcode_path, const ProgressFn &progress);
    // Pode ser chamado de outra thread durante slice().
    void cancel();

private:
    struct Impl;
    std::unique_ptr<Impl> m;
};

} // namespace slicer
