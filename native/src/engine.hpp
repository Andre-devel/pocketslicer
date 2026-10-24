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

// Malha de um objeto em coordenadas locais, com os volumes já transformados.
struct ObjectMesh {
    std::vector<float>    positions; // x, y, z por vértice
    std::vector<uint32_t> indices;   // 3 por triângulo
};

using Vec3 = std::array<double, 3>;

// Posição de um objeto na mesa (da sua instância). transform é a matriz 4x4 em
// ordem de coluna (OpenGL); os demais campos são a decomposição dela.
struct Placement {
    std::array<double, 16> transform{};
    Vec3 offset{};       // mm
    Vec3 rotation{};     // graus, ângulos de Euler XYZ como no Orca
    Vec3 scale{};        // fator (1 = 100%)
    bool outside = false; // fora (total ou parcialmente) do volume de impressão
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

    // STL, OBJ ou 3MF (só a geometria). Substitui os objetos anteriores e os centraliza
    // na mesa da impressora selecionada; trocar a impressora os recentraliza.
    void load_model(const std::string &path);
    // Como load_model(), mas acrescenta os objetos do arquivo. Se a mesa já tinha
    // objetos, todos são rearranjados (como o "Clonar" da GUI do Orca).
    void add_model(const std::string &path);
    // Cópia do objeto, ao fim da lista; rearranja todos como em add_model().
    void duplicate(size_t object);
    void remove(size_t object);
    // Arranjo automático de todos os objetos na mesa (libnest2d).
    void arrange();
    std::vector<ObjectMesh> objects() const;
    std::vector<Placement> placements() const;

    // As alterações de posição sempre apoiam o objeto na mesa (z mínimo = 0).
    void set_transform(size_t object, const std::array<double, 16> &transform);
    void set_placement(size_t object, const Vec3 &offset, const Vec3 &rotation, const Vec3 &scale);
    // Gira o objeto para que o triângulo dado (índice na malha de objects()) fique
    // virado para baixo, apoiado na mesa.
    void lay_on_face(size_t object, size_t triangle);

    // Fatia o modelo na posição atual com os perfis selecionados (falha se algum
    // objeto estiver fora do volume de impressão) e grava o G-code em gcode_path.
    SliceStats slice(const std::string &gcode_path, const ProgressFn &progress);
    // Pode ser chamado de outra thread durante slice().
    void cancel();

private:
    struct Impl;
    std::unique_ptr<Impl> m;
};

} // namespace slicer
