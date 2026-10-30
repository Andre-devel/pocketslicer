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
#include <optional>
#include <stdexcept>
#include <string>
#include <vector>

namespace slicer {

enum class PresetKind { Printer = 0, Filament = 1, Process = 2 };

// Gasto de um filamento do projeto (índice a partir de 0, como ObjectMesh::filaments).
struct FilamentUse {
    size_t index = 0;
    double m = 0.0;    // comprimento
    double g = 0.0;    // peso (filament_density)
    double cost = 0.0; // filament_cost (por kg) × peso
};

struct SliceStats {
    double print_time_s = 0.0;   // estimativa do modo normal
    double filament_mm3 = 0.0;   // volume total extrudado
    double filament_m = 0.0;     // soma do comprimento dos filamentos
    double filament_g = 0.0;     // soma do peso
    double cost = 0.0;           // soma do custo
    std::vector<double> role_time_s;    // tempo das extrusões por ExtrusionRole (índice)
    std::vector<FilamentUse> filaments; // os usados, em ordem de índice
};

// Caminhos do último fatiamento (GCodeProcessorResult), em buffers planos para a GPU.
// Segmentos na ordem do G-code; camadas numeradas a partir de 0.
struct Toolpaths {
    std::vector<float>    extrusions;      // 9 por segmento: x0 y0 z0 x1 y1 z1 largura altura velocidade(mm/s)
    std::vector<uint8_t>  roles;           // ExtrusionRole por segmento
    std::vector<uint32_t> layers;          // camada por segmento
    std::vector<uint8_t>  filaments;       // filamento (a partir de 0) por segmento
    std::vector<std::string> filament_colors; // "#RRGGBB" de cada filamento, do G-code
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
    size_t                filament = 0; // o do objeto (o das partes sem filamento próprio nem pintura)
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

// Torre de purga da mesa, estimada como a GUI do Orca a desenha antes de fatiar
// (PartPlate::estimate_wipe_tower_size). (x, y): canto da frente à esquerda, em mm na mesa;
// rotation em graus em torno dele.
struct WipeTower {
    double x = 0.0, y = 0.0;
    double width = 0.0, depth = 0.0, height = 0.0;
    double rotation = 0.0;
};

// Ação antes de uma camada, como na barra de camadas do Orca. FilamentChange troca para
// filament (a partir de 0) daí em diante e só vale com todos os objetos num filamento só
// (filament_change_available()); Custom insere gcode.
struct LayerChange {
    enum class Action { Pause = 0, FilamentChange = 1, Custom = 2 };
    double      z = 0.0; // topo da camada, mm
    Action      action = Action::Pause;
    size_t      filament = 0;
    std::string gcode;
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
    // {"values": {key: valor serializado}, "dirty": [keys alteradas em relação ao preset salvo],
    //  "saved": {key alterada: valor no preset salvo}}
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
    // e devolve o nome dele. Um 3MF sem configuração abre como load_model(). As placas do
    // projeto viram as placas da mesa; fica na mesa a primeira que tiver algum objeto.
    std::string open_project(const std::string &path);

    // Placas: sempre há ao menos uma, e os objetos da escolhida são os da mesa (objects(),
    // arrange(), slice()...). load_model() volta a uma placa só; add_model() acrescenta à atual.
    // Quantos objetos tem cada placa.
    std::vector<size_t> plates() const;
    // A placa (a partir de 0) cujos objetos estão na mesa.
    size_t current_plate() const;
    // Põe os objetos da placa na mesa; os da anterior ficam guardados nela.
    void select_plate(size_t plate);
    // Placa nova, vazia, no fim; devolve o índice dela.
    size_t add_plate();
    // Exclui a placa e os objetos dela, ou os passa para move_to (rearranjando-a). Se era a
    // da mesa, a mesa passa a ser move_to ou a vizinha. Lança se for a única.
    void delete_plate(size_t plate, std::optional<size_t> move_to);
    // Passa o objeto da mesa para outra placa, rearranjando-a.
    void move_to_plate(size_t object, size_t plate);
    // Grava as placas com os objetos, a configuração completa e a miniatura (da placa da
    // mesa) num 3MF que o Orca abre.
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
    // Preset de filamento de cada filamento do projeto, na mesma ordem de filament_colors().
    std::vector<std::string> filament_presets() const;
    // Material de cada filamento do projeto (filament_type: "PLA", "PETG"...), na mesma ordem.
    std::vector<std::string> filament_types() const;

    // --- Filamentos do projeto (como a barra lateral do Orca) ---
    // Quantos filamentos o projeto pode ter: no mínimo um por extrusor da impressora; com
    // um extrusor só para vários materiais (AMS, MMU), até 64, senão um por extrusor.
    std::array<size_t, 2> filament_limits() const;
    // Filamento novo, no fim: o preset do último e a próxima cor da paleta do Orca.
    void add_filament();
    // Exclui o filamento; os objetos e partes dele passam para o primeiro, e os dos
    // seguintes descem um índice (também nas outras placas e na pintura).
    void remove_filament(size_t filament);
    // Preset (compatível) do filamento; o do primeiro é o selected(PresetKind::Filament).
    void set_filament_preset(size_t filament, const std::string &name);
    // Cor "#RRGGBB".
    void set_filament_color(size_t filament, const std::string &color);
    // Filamento do objeto da mesa (a partir de 0).
    void set_object_filament(size_t object, size_t filament);

    // Volumes de purga (mm³) na troca do filamento i para o j: matriz n x n por linha
    // (flush_volumes_matrix), uma por extrusor da impressora, em sequência.
    std::vector<double> flush_volumes() const;
    void set_flush_volumes(const std::vector<double> &matrix);
    // Multiplicador dos volumes, por extrusor (flush_multiplier).
    std::vector<double> flush_multipliers() const;
    void set_flush_multiplier(size_t extruder, double multiplier);

    // Tipo de mesa do projeto (curr_bed_type), em JSON: {"current": valor, "values": [...],
    // "labels": [...]} com os tipos que a impressora aceita; "values" vazio quando ela tem um
    // tipo só (o Orca só mostra a escolha nas Bambu e com support_multi_bed_types).
    std::string bed_types_json() const;
    // [value]: um dos "values" de bed_types_json(), ex. "Textured PEI Plate".
    void set_bed_type(const std::string &value);
    // A torre de purga da mesa, se o fatiamento vai ter uma: torre ligada (enable_prime_tower)
    // e mais de um filamento nos objetos (ou timelapse suave).
    std::optional<WipeTower> wipe_tower() const;
    // Posição (canto da frente à esquerda) da torre na placa da mesa.
    void set_wipe_tower_position(double x, double y);

    // Pausas, trocas de filamento e G-code personalizado por altura na placa da mesa, por z.
    std::vector<LayerChange> layer_changes() const;
    void set_layer_changes(const std::vector<LayerChange> &changes);
    // Se a troca de filamento por altura vale: projeto com mais de um filamento e todos os
    // objetos da mesa num só (o modo "MultiAsSingle" do Orca).
    bool filament_change_available() const;

    // Recalcula a matriz pelas cores, como o "Auto-calcular" do Orca. As mudanças de
    // filamento (acrescentar, cor, preset de suporte) já recalculam as linhas dele.
    void calc_flush_volumes();
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
