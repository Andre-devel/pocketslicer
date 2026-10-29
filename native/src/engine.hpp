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
    double filament_m = 0.0;     // comprimento de filamento
    double filament_g = 0.0;     // peso (densidade do filamento)
    double cost = 0.0;           // filament_cost (por kg) × peso
};

// Caminhos do último fatiamento (GCodeProcessorResult), em buffers planos para a GPU.
// Segmentos na ordem do G-code; camadas numeradas a partir de 0.
struct Toolpaths {
    std::vector<float>    extrusions;      // 9 por segmento: x0 y0 z0 x1 y1 z1 largura altura velocidade(mm/s)
    std::vector<uint8_t>  roles;           // ExtrusionRole por segmento
    std::vector<uint32_t> layers;          // camada por segmento
    std::vector<float>    travels;         // 6 por movimento sem extrusão: x0 y0 z0 x1 y1 z1
    std::vector<uint32_t> travel_layers;
    std::vector<float>    layer_z;         // altura do topo de cada camada
};

// Área de impressão da impressora selecionada, em mm.
struct Bed {
    std::vector<std::array<double, 2>> shape; // contorno (printable_area), anti-horário
    double max_height = 0.0;                  // printable_height
};

// Malha de um objeto em coordenadas locais, com os volumes já transformados. As partes
// pintadas em várias cores vêm divididas nos triângulos da pintura.
struct ObjectMesh {
    std::string           name;      // ModelObject::name (em geral o nome do arquivo)
    std::vector<float>    positions; // x, y, z por vértice
    std::vector<uint32_t> indices;   // 3 por triângulo
    std::vector<uint8_t>  filaments; // filamento (a partir de 0) por triângulo, em ordem crescente
};

// Miniatura RGBA com as linhas de baixo para cima (a ordem do glReadPixels).
struct Thumbnail {
    unsigned width = 0;
    unsigned height = 0;
    std::vector<uint8_t> rgba;
};

using Vec3 = std::array<double, 3>;

// Posição de um objeto na mesa (da sua instância). transform é a matriz 4x4 em
// ordem de coluna (OpenGL); os demais campos são a decomposição dela.
struct Placement {
    std::array<double, 16> transform{};
    Vec3 offset{};       // mm
    Vec3 rotation{};     // graus, ângulos de Euler XYZ como no Orca
    Vec3 scale{};        // fator (1 = 100%)
    Vec3 size{};         // mm, caixa da instância já transformada
    bool outside = false; // fora (total ou parcialmente) do volume de impressão
};

struct Canceled : std::runtime_error {
    Canceled() : std::runtime_error("slicing canceled") {}
};

using ProgressFn = std::function<void(int percent, const std::string &text)>;

class Engine {
public:
    // resources_dir: cópia de resources/ do OrcaSlicer (profiles/, info/, flush/), mais
    // vendors/ (perfis dos fatiadores dos fabricantes, ver vendors()).
    // data_dir: diretório gravável; recebe system/ (symlinks para o fabricante carregado)
    // e user/. Ao apagá-lo, não siga symlinks (File.deleteRecursively() segue) ou os
    // perfis em resources_dir vão junto.
    Engine(const std::string &resources_dir, const std::string &data_dir);
    ~Engine();

    // Idioma dos textos do libslic3r e desta fachada (opções, progresso, erros): os .po de
    // resources_dir/i18n/<language> (ex.: "pt_BR", "en"), do Orca e da fachada. Vale para o
    // processo todo (i18n.hpp); sem esse diretório, os textos ficam como no código.
    void set_language(const std::string &language);

    // Fabricantes disponíveis: primeiro os copiados dos fatiadores dos fabricantes, em
    // resources/vendors/<app>/, como "<app>/<fabricante>" (ex.: "BambuStudio/BBL"); depois os
    // do Orca, em resources/profiles (sem a biblioteca de filamentos).
    std::vector<std::string> vendors() const;

    // Carrega os perfis do fabricante (mais OrcaFilamentLibrary) e seleciona sua
    // primeira impressora com o processo e o filamento padrão dela.
    void load_vendor(const std::string &vendor);

    // Impressoras do fabricante; filamentos e processos compatíveis com a seleção atual.
    std::vector<std::string> presets(PresetKind kind) const;
    std::string selected(PresetKind kind) const;
    // Trocar a impressora reaplica o processo e o filamento padrão dela quando compatíveis.
    // Descarta alterações não salvas do preset selecionado.
    void select(PresetKind kind, const std::string &name);

    // --- Parâmetros (o preset "editado" de cada tipo, base do fatiamento) ---

    // Definições das opções editáveis do tipo de preset, de PrintConfigDef, como JSON:
    // [{"key","label","category","tooltip","sidetext","type","vector","mode",
    //   "min","max","enum_values","enum_labels","multiline"}], type em
    // bool|int|float|percent|float_or_percent|string|enum|point|points;
    // mode 0 = simples (painel básico), 1 = avançado.
    std::string options_json(PresetKind kind) const;
    // {"values": {key: valor serializado}, "dirty": [keys alteradas em relação ao preset salvo]}
    std::string values_json(PresetKind kind) const;
    // Valor no formato serializado do Orca (ex.: "0.2", "15%", "1", "grid"). Lança se inválido.
    void set_option(PresetKind kind, const std::string &key, const std::string &value);
    void discard_changes(PresetKind kind);

    // --- Presets de usuário (em <data_dir>/user/default, formato do Orca) ---

    // Nomes dos presets de usuário (não de sistema) entre os de presets().
    std::vector<std::string> user_presets(PresetKind kind) const;
    // Salva o preset editado com outro nome (ou sobrescreve um de usuário) e o seleciona.
    void save_preset(PresetKind kind, const std::string &name);
    // Só presets de usuário; seleciona outro compatível.
    void delete_preset(PresetKind kind, const std::string &name);
    // Importa um .json de preset do Orca; devolve os nomes importados.
    std::vector<std::string> import_preset(const std::string &path);
    // Exporta o preset editado (configuração completa) como .json do Orca.
    void export_preset(PresetKind kind, const std::string &path) const;

    Bed bed() const;

    // STL, OBJ ou 3MF (só a geometria). Substitui os objetos anteriores e os centraliza
    // na mesa da impressora selecionada; trocar a impressora os recentraliza.
    void load_model(const std::string &path);
    // Como load_model(), mas acrescenta os objetos do arquivo. Se a mesa já tinha
    // objetos, todos são rearranjados (como o "Clonar" da GUI do Orca).
    void add_model(const std::string &path);
    // Projeto 3MF do Orca/Bambu Studio: objetos nas posições salvas e perfis com as
    // alterações, como a GUI do Orca abre. Carrega o fabricante da impressora do projeto
    // e devolve o nome dele. Um 3MF sem configuração abre como load_model(); de um projeto
    // com várias placas, só os objetos da primeira que tiver algum.
    std::string open_project(const std::string &path);
    // Quantos objetos tem cada placa do projeto aberto por open_project(); vazio se ele tem
    // só uma ou se, depois dele, vieram load_model() ou add_model().
    std::vector<size_t> project_plates() const;
    // A placa (a partir de 0) cujos objetos estão na mesa, quando project_plates() não é vazio.
    size_t project_plate() const;
    // Troca os objetos da mesa pelos da placa (a partir de 0) do projeto, nas posições do
    // arquivo; as alterações feitas nos da placa anterior se perdem. Lança se ela estiver vazia.
    void select_plate(size_t plate);
    // Grava os objetos, a configuração completa e a miniatura num 3MF que o Orca abre.
    void save_project(const std::string &path, const Thumbnail &thumbnail);
    // Se a impressora selecionada recebe o G-code dentro de um .gcode.3mf (Bambu, ou a
    // opção "use_3mf" do perfil) em vez do .gcode puro.
    bool gcode_3mf() const;
    // .gcode.3mf com o G-code do último slice() (gravado em gcode_path) e a miniatura da
    // mesa (512x512), como "Exportar arquivo fatiado da placa" do Orca.
    void export_gcode_3mf(const std::string &gcode_path, const std::string &path, const Thumbnail &thumbnail);
    // G-code pronto (para preview); substitui o resultado do último slice(). O tempo e o
    // filamento vêm dos comentários e da configuração no G-code, como no Orca.
    SliceStats load_gcode(const std::string &path);

    // Cópia do objeto, ao fim da lista; rearranja todos como em add_model().
    void duplicate(size_t object);
    void remove(size_t object);
    // Arranjo automático de todos os objetos na mesa (libnest2d).
    void arrange();
    std::vector<ObjectMesh> objects() const;
    // Cor de cada filamento do projeto ("#RRGGBB", filament_colour), pelo índice de
    // ObjectMesh::filaments.
    std::vector<std::string> filament_colors() const;
    std::vector<Placement> placements() const;

    // As alterações de posição sempre apoiam o objeto na mesa (z mínimo = 0).
    void set_transform(size_t object, const std::array<double, 16> &transform);
    void set_placement(size_t object, const Vec3 &offset, const Vec3 &rotation, const Vec3 &scale);
    // Gira o objeto para que o triângulo dado (índice na malha de objects()) fique
    // virado para baixo, apoiado na mesa.
    void lay_on_face(size_t object, size_t triangle);

    // Tamanhos (largura, altura) das miniaturas que o G-code da impressora selecionada
    // leva (opção "thumbnails"). Vazio nas impressoras Bambu: o G-code delas não tem miniatura.
    std::vector<std::array<unsigned, 2>> gcode_thumbnail_sizes() const;

    // Fatia o modelo na posição atual com os perfis selecionados (falha se algum
    // objeto estiver fora do volume de impressão) e grava o G-code em gcode_path.
    // thumbnails: imagens nos tamanhos de gcode_thumbnail_sizes(); as que faltarem ficam fora.
    SliceStats slice(const std::string &gcode_path, const ProgressFn &progress,
                     const std::vector<Thumbnail> &thumbnails = {});
    // Pode ser chamado de outra thread durante slice().
    void cancel();

    // Do último slice() ou load_gcode() bem-sucedido (vazio antes deles).
    Toolpaths toolpaths() const;
    // Nome (em inglês, como no Orca) de cada ExtrusionRole, pelo índice.
    static std::vector<std::string> role_names();

private:
    struct Impl;
    std::unique_ptr<Impl> m;
};

} // namespace slicer
