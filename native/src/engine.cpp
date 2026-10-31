#include "engine.hpp"

#include <map>
#include <algorithm>
#include <atomic>
#include <iterator>
#include <map>
#include <queue>
#include <regex>
#include <set>
#include <tuple>

#include <boost/algorithm/string/predicate.hpp>
#include <boost/filesystem.hpp>
#include <boost/nowide/fstream.hpp>
#include <boost/log/core.hpp>
#include <boost/log/expressions.hpp>
#include <boost/log/trivial.hpp>

#include "libslic3r/libslic3r.h"
#include "libslic3r/AABBMesh.hpp"
#include "libslic3r/AppConfig.hpp"
#include "libslic3r/Arrange.hpp"
#include "libslic3r/BuildVolume.hpp"
#include "libslic3r/CustomGCode.hpp"
#include "libslic3r/CutUtils.hpp"
#include "libslic3r/calib.hpp"
#include "libslic3r/ExtrusionEntity.hpp"
#include "libslic3r/Flow.hpp"
#include "libslic3r/FlushVolCalc.hpp"
#include "libslic3r/Format/bbs_3mf.hpp"
#include "libslic3r/GCode/GCodeProcessor.hpp"
#include "libslic3r/GCode/WipeTower.hpp"
#include "libslic3r/GCode/ThumbnailData.hpp"
#include "libslic3r/GCode/Thumbnails.hpp"
#include "libslic3r/I18N.hpp"
#include "libslic3r/LocalesUtils.hpp"
#include "libslic3r/format.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/PresetBundle.hpp"
#include "libslic3r/Print.hpp"
#include "libslic3r/PrintConfig.hpp"
#include "libslic3r/TriangleSelector.hpp"
#include "libslic3r/Utils.hpp"

#include "nlohmann/json.hpp"

#include "i18n.hpp"

namespace fs = boost::filesystem;
using namespace Slic3r;

namespace slicer {

namespace {

// Paleta de Plater::get_next_color_for_filament() do Orca.
const char *const FILAMENT_PALETTE[] = {"#00C1AE", "#F4E2C1", "#ED1C24", "#00FF7F", "#F26722", "#FFEB31", "#7841CE", "#115877",
                                        "#ED1E79", "#2EBDEF", "#345B2F", "#800080", "#FA8173", "#800000", "#F7B763", "#A4C41E"};

void copy_for_orca(const fs::path &src, const fs::path &dst);
} // namespace

struct Engine::Impl {
    fs::path                      profiles_dir;
    fs::path                      vendors_dir; // perfis dos fatiadores dos fabricantes: <app>/<fabricante>.json
    AppConfig                     app_config;
    std::unique_ptr<PresetBundle> bundle;
    std::string                   vendor; // de bundle
    // O processo foi escolhido (num projeto aberto ou pelo usuário), não só o padrão da impressora.
    bool                          process_chosen = false;
    // Altura de camada que o processo escolhido tinha, enquanto as trocas de impressora o
    // substituem pelo mais próximo: passar por um bico 0.2 não faz o 0.4 seguinte perder os 0.2.
    std::optional<double>         wanted_layer_height;
    Model                         model;
    Print                         print;
    GCodeProcessorResult          result; // do último slice()
    std::map<size_t, double>      firmware_flush; // do último slice(), ver firmware_flush()
    // Objetos de cada placa, em coordenadas da mesa. A placa da mesa (plate) fica em model,
    // e a entrada dela aqui, vazia, até outra ser escolhida. Sempre há ao menos uma.
    std::vector<Model> plates = std::vector<Model>(1);
    size_t             plate = 0;

    // Pintura em andamento (paint()), como os TriangleSelectorGUI do gizmo: seletor e raycaster de
    // cada peça do objeto, válidos enquanto a pintura guardada na peça tiver o timestamp deles.
    // TriangleSelector com o "Preencher lacunas" de TriangleSelectorPatch (GLGizmoPainterBase.cpp,
    // que é da GUI): trechos de triângulos vizinhos no mesmo estado.
    class Selector : public TriangleSelector {
    public:
        using TriangleSelector::TriangleSelector;
        struct Patch {
            std::vector<int>              facets;
            EnforcerBlockerType           state = EnforcerBlockerType::NONE;
            std::set<EnforcerBlockerType> neighbor_states;
            double                        area = 0.0; // até max_area
        };
        // TriangleSelectorPatch::update_triangles_per_patch().
        std::vector<Patch> patches(double max_area) const;
        // Os trechos menores que area (TrianglePatch::is_fragment()) com o estado que vão receber.
        std::vector<std::pair<const Patch *, EnforcerBlockerType>> fragments(const std::vector<Patch> &all, double area) const
        {
            std::vector<std::pair<const Patch *, EnforcerBlockerType>> out;
            for (const Patch &patch : all)
                if (patch.area < area && !patch.neighbor_states.empty())
                    out.emplace_back(&patch, *patch.neighbor_states.begin());
            return out;
        }
        // TriangleSelectorPatch::update_selector_triangles().
        size_t fill_gaps(double area)
        {
            const std::vector<Patch> all = patches(area);
            const auto               gaps = fragments(all, area);
            for (const auto &[patch, state] : gaps)
                for (int facet : patch->facets)
                    m_triangles[facet].set_state(state);
            return gaps.size();
        }
        // Triângulos (na malha da peça) de facets.
        void append_facets(const std::vector<int> &facets, indexed_triangle_set &out) const
        {
            for (int facet : facets) {
                const int first = int(out.vertices.size());
                for (int i = 0; i < 3; ++i)
                    out.vertices.push_back(m_vertices[m_triangles[facet].verts_idxs[i]].v);
                out.indices.emplace_back(first, first + 1, first + 2);
            }
        }
    };
    struct PaintVolume {
        ObjectID                            volume;
        ObjectWithTimestamp::Timestamp      timestamp = 0;
        std::shared_ptr<const TriangleMesh> mesh; // o seletor guarda uma referência a ela
        std::unique_ptr<Selector>           selector;
        std::unique_ptr<AABBMesh>           raycaster;
    };
    ObjectID                 paint_object;
    PaintKind                paint_kind = PaintKind::Support;
    std::vector<PaintVolume> paint_volumes;
    // Ponto do toque anterior do arraste: peça (em paint_volumes), triângulo e ponto na malha dela.
    struct PaintHit {
        size_t volume = 0;
        int    facet  = -1;
        Vec3f  point  = Vec3f::Zero();
    };
    std::optional<PaintHit> paint_last;

    // Parâmetros do teste de calibração na mesa (calibrate()), que o gerador de G-code lê a cada
    // camada; Calib_None fora de um teste.
    Calib_Params calib;
    // Padrão do teste de PA padrão (Model::calib_pa_pattern na GUI), que gera o G-code das alças
    // de cada placa em slice().
    std::unique_ptr<CalibPressureAdvancePattern> pa_pattern;
    void calib_pa_pattern(bool junction_deviation);
    PaintVolume &paint_volume(const ModelObject &object, PaintKind kind, const ModelVolume &volume);

    // Índice de um fabricante de vendors(): "<app>/<fabricante>" está em vendors_dir, e o
    // nome só, em profiles_dir. Os perfis ficam no diretório de mesmo nome, sem o .json.
    fs::path vendor_index(const std::string &vendor) const
    {
        if (vendor.empty() || vendor.find("..") != std::string::npos)
            throw std::invalid_argument("unknown vendor: " + vendor);
        const fs::path index = (vendor.find('/') == std::string::npos ? profiles_dir : vendors_dir) / (vendor + ".json");
        if (!fs::exists(index))
            throw std::invalid_argument("unknown vendor: " + vendor);
        return index;
    }

    PresetCollection &collection(PresetKind kind)
    {
        if (!bundle)
            throw std::runtime_error("no vendor loaded");
        switch (kind) {
        case PresetKind::Printer: return bundle->printers;
        case PresetKind::Filament: return bundle->filaments;
        case PresetKind::Process: return bundle->prints;
        }
        throw std::invalid_argument("invalid preset kind");
    }

    void select_filament(const std::string &name)
    {
        bundle->filaments.select_preset_by_name(name, true);
        bundle->set_filament_preset(0, bundle->filaments.get_selected_preset_name());
    }

    // Tipo de mesa padrão da impressora: o default_bed_type do preset ou, sem ele, o do modelo
    // da impressora (machine_model_list do índice do fabricante), como o Bambu Studio lê. O Orca
    // ignora o do modelo, onde o Bambu Studio e o QIDI Studio o declaram.
    BedType default_bed_type()
    {
        Preset &printer = bundle->printers.get_selected_preset();
        if (printer.config.opt_string("default_bed_type").empty()) {
            const fs::path index = vendor_index(vendor);
            boost::nowide::ifstream in(index.string());
            const nlohmann::json vendor_json = nlohmann::json::parse(in, nullptr, false);
            const std::string &model = printer.config.opt_string("printer_model");
            if (vendor_json.is_object() && vendor_json.contains("machine_model_list"))
                for (const nlohmann::json &entry : vendor_json["machine_model_list"]) {
                    if (entry.value("name", "") != model)
                        continue;
                    boost::nowide::ifstream model_in((index.parent_path() / index.stem() / entry.value("sub_path", "")).string());
                    const nlohmann::json model_json = nlohmann::json::parse(model_in, nullptr, false);
                    BedType bed_type;
                    if (model_json.is_object() && model_json.contains("default_bed_type") && model_json["default_bed_type"].is_string() &&
                        ConfigOptionEnum<BedType>::from_string(model_json["default_bed_type"].get<std::string>(), bed_type) &&
                        bed_type > btDefault && bed_type < btCount)
                        return bed_type;
                }
        }
        return printer.get_default_bed_type(bundle.get());
    }

    // Como PresetBundle::load_selections() faz para uma impressora recém-instalada.
    void select_printer_defaults()
    {
        const DynamicPrintConfig &printer = bundle->printers.get_selected_preset().config;
        auto compatible = [](const Preset *p) { return p != nullptr && p->is_compatible; };

        const std::string &print_name = printer.opt_string("default_print_profile");
        if (compatible(bundle->prints.find_preset(print_name)))
            bundle->prints.select_preset_by_name(print_name, true);

        const auto *filaments = printer.option<ConfigOptionStrings>("default_filament_profile");
        if (filaments != nullptr && !filaments->values.empty() &&
            compatible(bundle->filaments.find_preset(filaments->values.front())))
            select_filament(filaments->values.front());
    }

    // Escolhas do projeto que a troca de impressora mantém, como no Bambu Studio: a altura de
    // camada (vai para o processo da impressora nova de altura mais próxima, de preferência o de
    // mesmo nome), as alterações não salvas do processo e o tipo de cada filamento. O padrão de
    // uma impressora, sem alterações, passa ao padrão da outra.
    struct Choices
    {
        bool                                             keep_process = false;
        std::string                                      process;
        double                                           layer_height = 0.;
        DynamicPrintConfig                               process_edits;
        std::vector<std::pair<std::string, std::string>> filaments; // nome e tipo
    };

    // "0.20mm Standard @BBL A1" -> "0.20mm Standard"; "Bambu PLA Basic @BBL A1" -> "Bambu PLA Basic".
    static std::string base_name(const std::string &name) { return name.substr(0, name.find(" @")); }

    static std::string filament_type(const Preset &preset)
    {
        const auto *type = preset.config.option<ConfigOptionStrings>("filament_type");
        return type != nullptr && !type->values.empty() ? type->values.front() : std::string();
    }

    Choices choices() const
    {
        Choices                 out;
        const PresetCollection &prints = bundle->prints;
        out.process      = prints.get_selected_preset_name();
        out.layer_height = wanted_layer_height.value_or(prints.get_edited_preset().config.opt_float("layer_height"));
        out.process_edits.apply_only(prints.get_edited_preset().config, prints.current_dirty_options());
        for (const char *key : {"compatible_printers", "compatible_printers_condition", "inherits"})
            out.process_edits.erase(key);
        out.keep_process = process_chosen || !out.process_edits.empty() ||
                           out.process != bundle->printers.get_selected_preset().config.opt_string("default_print_profile");
        for (const std::string &name : bundle->filament_presets)
            if (const Preset *preset = bundle->filaments.find_preset(name))
                out.filaments.emplace_back(name, filament_type(*preset));
        return out;
    }

    // Depois de select_printer_defaults(): troca os padrões da impressora pelo que [choices] pede.
    void restore(const Choices &choices)
    {
        PresetCollection &prints   = bundle->prints;
        const std::string selected = prints.get_selected_preset_name();
        const Preset     *process  = nullptr;
        std::tuple<long, bool, bool> best{};
        for (const Preset &preset : prints) {
            if (!choices.keep_process)
                break;
            if (preset.is_default || !preset.is_visible || !preset.is_compatible)
                continue;
            // Menor diferença de altura; no empate, o de mesmo nome e depois o padrão da impressora.
            const std::tuple<long, bool, bool> rank{std::labs(std::lround(preset.config.opt_float("layer_height") * 1000.) - std::lround(choices.layer_height * 1000.)),
                                                    base_name(preset.name) != base_name(choices.process), preset.name != selected};
            if (process == nullptr || rank < best)
                process = &preset, best = rank;
        }
        if (process != nullptr && process->name != selected)
            prints.select_preset_by_name(process->name, true);
        if (choices.keep_process)
            wanted_layer_height = choices.layer_height;
        DynamicPrintConfig &config = prints.get_edited_preset().config;
        for (const std::string &key : choices.process_edits.keys())
            if (config.has(key))
                config.set_key_value(key, choices.process_edits.option(key)->clone());

        // Cada filamento: o de mesmo nome para a impressora nova, o padrão dela, um genérico ou
        // outro qualquer, sempre do mesmo tipo (PLA, PETG...); sem nenhum, fica o padrão.
        const size_t count = std::min(choices.filaments.size(), bundle->filament_presets.size());
        for (size_t i = 0; i < count; ++i) {
            const auto &[name, type] = choices.filaments[i];
            const Preset *pick       = nullptr;
            int           best_rank  = 0;
            for (const Preset &preset : bundle->filaments) {
                if (preset.is_default || !preset.is_compatible || filament_type(preset) != type)
                    continue;
                const int rank = base_name(preset.name) == base_name(name)         ? 0
                                 : preset.name == bundle->filament_presets[i]     ? 1
                                 : boost::starts_with(preset.name, "Generic")     ? 2
                                                                                  : 3;
                if (pick == nullptr || rank < best_rank)
                    pick = &preset, best_rank = rank;
            }
            if (pick != nullptr)
                bundle->set_filament_preset(i, pick->name);
        }
        if (!bundle->filament_presets.empty())
            select_filament(bundle->filament_presets.front());
    }

    ModelObject &object(size_t index);
    ModelConfig &settings(const SettingsTarget &target);
    void add(Model &&objects, bool keep_layout = true);
    // 3MF dos objetos da mesa como uma placa. Com gcode_path, um .gcode.3mf como
    // Plater::export_gcode_3mf(): sem as malhas, com o G-code e os dados do último slice().
    void store_3mf(const std::string &path, const Thumbnail &thumbnail, const std::string &gcode_path = {});

    // Volta a uma placa só, vazia, na mesa.
    void reset_plates()
    {
        model.clear_objects();
        model.clear_materials();
        model.plates_custom_gcodes.clear();
        plates.assign(1, Model());
        plate = 0;
        calib = Calib_Params();
        pa_pattern.reset();
    }

    Model &plate_model(size_t index)
    {
        if (index >= plates.size())
            throw std::out_of_range("invalid plate");
        return index == plate ? model : plates[index];
    }

    // Põe os objetos da placa na mesa, guardando os da atual.
    void show_plate(size_t index)
    {
        if (index >= plates.size())
            throw std::out_of_range("invalid plate");
        if (index == plate)
            return;
        plates[plate] = std::move(model);
        model = std::move(plates[index]);
        plates[index] = Model();
        plate = index;
    }

    // Origem da placa na grade de PartPlateList::compute_shape_position() do Orca: a
    // primeira na origem, colunas para +x e linhas para -y, separadas por 1/5 da mesa.
    Vec2d plate_origin(size_t index, size_t count) const
    {
        const Vec2d stride  = build_volume().bounding_volume2d().size() * (1. + 1. / 5.); // como em open_project()
        const int   columns = plate_columns(count);
        return Vec2d(double(index % columns) * stride.x(), -double(index / columns) * stride.y());
    }

    static int plate_columns(size_t count) { return std::max(1, int(std::ceil(std::sqrt(double(count)) - 1e-6))); }

    // Acrescenta uma cópia do objeto (só a 1ª instância) a target.
    static ModelObject *copy_object(Model &target, const ModelObject &source)
    {
        ModelObject *added = target.add_object(source);
        while (added->instances.size() > 1)
            added->delete_instance(added->instances.size() - 1);
        return added;
    }

    const DynamicPrintConfig &printer() const { return bundle->printers.get_edited_preset().config; }

    std::vector<Vec2d> printable_area() const
    {
        const auto *area = printer().opt<ConfigOptionPoints>("printable_area");
        return area != nullptr ? area->values : std::vector<Vec2d>{};
    }

    BuildVolume build_volume() const
    {
        const auto *height = printer().opt<ConfigOptionFloat>("printable_height");
        return BuildVolume(printable_area(), height != nullptr ? height->value : 0.0, {}, {});
    }

    // Arranjo automático de todos os objetos (libnest2d). Sem objetos fixos: com eles,
    // o FirstFit do Orca, ao não achar lugar na 1ª tentativa, os descarta e sobrepõe.
    void arrange_all() { arrange(model); }

    void arrange(Model &model)
    {
        arrangement::ArrangePolygons items;
        for (ModelObject *object : model.objects) {
            arrangement::ArrangePolygon ap;
            object->instances.front()->get_arrange_polygon(&ap);
            // Placa 0: no libnest2d do Orca, UNARRANGED (-1) coincide com
            // BIN_ID_UNFIT e o item seria descartado.
            ap.bed_idx = 0;
            // Distância entre objetos via polígono inflado (update_selected_items_inflation na GUI).
            ap.inflation = scaled(ARRANGE_SPACING_MM / 2);
            items.push_back(std::move(ap));
        }
        Points bed;
        for (const Vec2d &p : printable_area())
            bed.push_back(Point::new_scale(p.x(), p.y()));
        arrangement::ArrangeParams params;
        params.progressind = {}; // o padrão escreve no stdout
        arrangement::arrange(items, bed, params);
        for (size_t i = 0; i < items.size(); ++i) {
            ModelObject *object = model.objects[i];
            // Sem espaço na mesa (bed_idx != 0): fica onde está e aparece como fora dela.
            if (items[i].bed_idx == 0)
                object->instances.front()->apply_arrange_result(items[i].translation.cast<double>(), items[i].rotation);
            object->ensure_on_bed();
        }
    }

    static constexpr double ARRANGE_SPACING_MM = 5.0;

    // Todas as placas, a da mesa inclusive.
    std::vector<Model *> all_plates()
    {
        std::vector<Model *> out{&model};
        for (size_t i = 0; i < plates.size(); ++i)
            if (i != plate)
                out.push_back(&plates[i]);
        return out;
    }

    size_t filament_count() const { return bundle->filament_presets.size(); }

    void check_filament(size_t filament) const
    {
        if (filament >= filament_count())
            throw std::out_of_range("no filament " + std::to_string(filament));
    }

    // Filamento de suporte (filament_is_support), como is_support_filament() da GUI.
    bool is_support_filament(size_t filament) const
    {
        const Preset *preset = bundle->filaments.find_preset(bundle->filament_presets[filament]);
        const auto   *opt    = preset != nullptr ? preset->config.option<ConfigOptionBools>("filament_is_support") : nullptr;
        return opt != nullptr && opt->get_at(0);
    }

    void calc_flush_volumes(std::optional<size_t> filament);
    void sync_filament_colors();

    // Filamentos (a partir de 1) dos objetos da mesa, pintura inclusive.
    std::set<int> object_filaments() const
    {
        std::set<int> out;
        for (const ModelObject *object : model.objects)
            for (const ModelVolume *volume : object->volumes)
                if (volume->is_model_part()) {
                    out.insert(volume->extruder_id());
                    for (int painted : volume->get_extruders())
                        out.insert(painted);
                }
        return out;
    }

    // Modo das ações por altura, como ToolOrdering::assign_custom_gcodes() o deduz do Print;
    // trocas de filamento gravadas noutro modo são ignoradas no fatiamento.
    CustomGCode::Mode layer_change_mode() const
    {
        if (filament_count() == 1)
            return CustomGCode::SingleExtruder;
        return object_filaments().size() == 1 ? CustomGCode::MultiAsSingle : CustomGCode::MultiExtruder;
    }

    // Sem PartPlate: os objetos ficam apoiados na mesa e centralizados nela.
    void place_on_bed()
    {
        if (!bundle || model.objects.empty())
            return;
        for (ModelObject *object : model.objects)
            object->ensure_on_bed();
        const std::vector<Vec2d> area = printable_area();
        if (!area.empty())
            model.center_instances_around_point(BoundingBoxf(area).center());
    }
};

Engine::Engine(const std::string &resources_dir, const std::string &data_dir) : m(new Impl)
{
    boost::log::core::get()->set_filter(boost::log::trivial::severity >= boost::log::trivial::warning);
    // Absolutos: load_vendor() cria symlinks para os perfis.
    set_resources_dir(fs::absolute(resources_dir).string());
    set_data_dir(fs::absolute(data_dir).string());
    // Arquivos intermediários do 3MF (Model::get_backup_path); sem isto ficariam em "/".
    const fs::path temp_dir = fs::absolute(data_dir) / "tmp";
    fs::create_directories(temp_dir);
    set_temporary_dir(temp_dir.string());
    m->profiles_dir = fs::path(Slic3r::resources_dir()) / "profiles";
    m->vendors_dir  = fs::path(Slic3r::resources_dir()) / "vendors";
    PresetBundle().setup_directories();
}

Engine::~Engine() = default;

void Engine::set_language(const std::string &language)
{
    std::vector<std::string> files;
    const fs::path dir = fs::path(Slic3r::resources_dir()) / "i18n" / language;
    if (!language.empty() && fs::is_directory(dir))
        for (const fs::directory_entry &entry : fs::directory_iterator(dir))
            if (entry.path().extension() == ".po")
                files.push_back(entry.path().string());
    // Ordem ASCII: OrcaSlicer_<idioma>.po antes de slicer.po, que pode corrigir o do Orca.
    std::sort(files.begin(), files.end());
    i18n::load(files);
}

std::vector<std::string> Engine::vendors() const
{
    const auto indexes = [](const fs::path &dir, const std::string &prefix) {
        std::vector<std::string> out;
        if (!fs::is_directory(dir))
            return out;
        for (const fs::directory_entry &entry : fs::directory_iterator(dir)) {
            const fs::path &path = entry.path();
            if (path.extension() == ".json" && fs::is_directory(path.parent_path() / path.stem()) &&
                path.stem() != PresetBundle::ORCA_FILAMENT_LIBRARY)
                out.push_back(prefix + path.stem().string());
        }
        std::sort(out.begin(), out.end());
        return out;
    };
    // Primeiro os dos fatiadores dos fabricantes, depois os do Orca.
    std::vector<std::string> out;
    if (fs::is_directory(m->vendors_dir))
        for (const fs::directory_entry &app : fs::directory_iterator(m->vendors_dir))
            if (fs::is_directory(app.path()))
                for (std::string &vendor : indexes(app.path(), app.path().filename().string() + "/"))
                    out.push_back(std::move(vendor));
    std::sort(out.begin(), out.end());
    for (std::string &vendor : indexes(m->profiles_dir, ""))
        out.push_back(std::move(vendor));
    return out;
}

void Engine::load_vendor(const std::string &vendor)
{
    const fs::path index = m->vendor_index(vendor);
    // Cores dos filamentos do projeto: o bundle novo começa com os do perfil da impressora.
    std::vector<std::string> colors;
    std::optional<Impl::Choices> choices;
    if (m->bundle) {
        colors  = m->bundle->project_config.option<ConfigOptionStrings>("filament_colour")->values;
        choices = m->choices();
    }

    // PresetBundle carrega todo fabricante presente em <data_dir>/system. Como na
    // GUI do Orca, só entram os "instalados": aqui, o escolhido e a biblioteca de
    // filamentos genéricos (da qual os filamentos dos fabricantes herdam).
    // Carregar os 130+ fabricantes de uma vez custaria centenas de MB de RAM.
    const fs::path system_dir = fs::path(data_dir()) / PRESET_SYSTEM_DIR;
    fs::remove_all(system_dir);
    fs::create_directories(system_dir);
    // O PresetBundle identifica o fabricante pelo nome do arquivo em system/ ("BBL" liga o
    // tratamento das Bambu), então os de vendors_dir entram sem o "<app>/".
    for (const fs::path &json : {m->profiles_dir / (std::string(PresetBundle::ORCA_FILAMENT_LIBRARY) + ".json"), index}) {
        fs::create_symlink(json, system_dir / json.filename());
        if (json.parent_path() == m->profiles_dir)
            fs::create_directory_symlink(json.parent_path() / json.stem(), system_dir / json.stem());
        else
            copy_for_orca(json.parent_path() / json.stem(), system_dir / json.stem());
    }

    auto bundle = std::make_unique<PresetBundle>();
    bundle->load_presets(m->app_config, ForwardCompatibilitySubstitutionRule::EnableSystemSilent);
    // Impressoras só ficam visíveis se o modelo/variante estiver "instalado" no
    // AppConfig (o assistente da GUI faz isso). Aqui, todos os do fabricante.
    for (const auto &[id, profile] : bundle->vendors)
        for (const VendorProfile::PrinterModel &model : profile.models)
            for (const VendorProfile::PrinterVariant &variant : model.variants)
                m->app_config.set_variant(id, model.id, variant.name, true);
    bundle->load_selections(m->app_config);
    // Filamentos "instalados" na GUI são só os padrões de cada impressora; aqui todos
    // os compatíveis são selecionáveis (select_preset_by_name ignora os invisíveis).
    for (Preset &filament : bundle->filaments)
        filament.is_visible = true;
    m->bundle = std::move(bundle);
    m->vendor = vendor;

    const std::vector<std::string> printers = presets(PresetKind::Printer);
    if (printers.empty())
        throw std::runtime_error("vendor has no printers: " + vendor);
    select(PresetKind::Printer, printers.front());

    // Como a troca de impressora na GUI do Orca, o projeto continua com os mesmos filamentos
    // (quantos a impressora aceitar), com as cores deles e o filamento padrão da impressora.
    if (!colors.empty()) {
        const std::array<size_t, 2> limits = filament_limits();
        const size_t                count  = std::clamp(colors.size(), limits[0], limits[1]);
        m->bundle->set_num_filaments(unsigned(count), FILAMENT_PALETTE[0]);
        for (size_t i = colors.size(); i < count; ++i)
            colors.push_back(FILAMENT_PALETTE[i % std::size(FILAMENT_PALETTE)]);
        colors.resize(count);
        DynamicPrintConfig &project = m->bundle->project_config;
        project.option<ConfigOptionStrings>("filament_colour")->values = colors;
        if (auto *multi = project.option<ConfigOptionStrings>("filament_multi_colour"))
            multi->values = colors;
        m->calc_flush_volumes(std::nullopt);
    }
    if (choices)
        m->restore(*choices);
}

std::vector<std::string> Engine::presets(PresetKind kind) const
{
    std::vector<std::string> out;
    for (const Preset &preset : m->collection(kind))
        if (!preset.is_default && preset.is_visible && preset.is_compatible)
            out.push_back(preset.name);
    return out;
}

std::string Engine::selected(PresetKind kind) const
{
    return m->collection(kind).get_selected_preset_name();
}

namespace {

// Opção de um perfil de impressora do fabricante que o Orca não conhece e descarta ao carregar,
// lida dos JSON em machine_dir seguindo "inherits".
std::optional<std::string> vendor_printer_option(const fs::path &machine_dir, std::string name, const std::string &key)
{
    std::map<std::string, nlohmann::json> by_name;
    if (fs::is_directory(machine_dir))
        for (const fs::directory_entry &file : fs::directory_iterator(machine_dir)) {
            boost::nowide::ifstream in(file.path().string());
            nlohmann::json profile = nlohmann::json::parse(in, nullptr, false);
            if (profile.is_object() && profile.contains("name") && profile["name"].is_string()) {
                const std::string profile_name = profile["name"].get<std::string>();
                by_name[profile_name]          = std::move(profile);
            }
        }
    while (!name.empty()) {
        const auto it = by_name.find(name);
        if (it == by_name.end())
            break;
        if (it->second.contains(key) && it->second[key].is_string())
            return it->second[key].get<std::string>();
        name = it->second.value("inherits", "");
    }
    return std::nullopt;
}

} // namespace

void Engine::select(PresetKind kind, const std::string &name)
{
    PresetCollection &collection = m->collection(kind);
    const Preset *preset = collection.find_preset(name);
    if (preset == nullptr || preset->is_default || !preset->is_compatible)
        throw std::invalid_argument("preset not available: " + name);

    switch (kind) {
    case PresetKind::Printer: {
        const Impl::Choices choices = m->choices();
        collection.select_preset_by_name(name, true);
        m->bundle->update_multi_material_filament_presets();
        m->sync_filament_colors();
        // Troca processo/filamento incompatíveis pelos primeiros compatíveis...
        m->bundle->update_compatible(PresetSelectCompatibleType::Always);
        // ...e prefere os padrões declarados pela impressora.
        m->select_printer_defaults();
        m->restore(choices);
        // Tipo de mesa padrão da impressora, como o Plater da GUI faz (a validação
        // recusa filamentos sem temperatura para a mesa escolhida).
        m->bundle->project_config.set_key_value("curr_bed_type", new ConfigOptionEnum<BedType>(m->default_bed_type()));
        // O firmware do CFS purga a matriz vezes o multiplicador que lê do G-code, e o Creality
        // Print, ao trocar de impressora, põe no projeto o default_flush_multiplier dela (1,3
        // quando o perfil não diz; avisa se o projeto ficar abaixo). O padrão do Orca é 0,3.
        if (m->vendor.rfind("CrealityPrint/", 0) == 0) {
            const Preset     &printer = collection.get_selected_preset();
            const fs::path    machine = fs::path(data_dir()) / PRESET_SYSTEM_DIR / m->vendor_index(m->vendor).stem() / "machine";
            const std::string value   = vendor_printer_option(machine, printer.is_system ? printer.name : printer.inherits(), "default_flush_multiplier")
                                          .value_or("1.3");
            auto &multipliers = m->bundle->project_config.option<ConfigOptionFloats>("flush_multiplier")->values;
            std::fill(multipliers.begin(), multipliers.end(), string_to_double_decimal_point(value));
        }
        m->place_on_bed();
        break;
    }
    case PresetKind::Filament:
        m->select_filament(name);
        break;
    case PresetKind::Process:
        collection.select_preset_by_name(name, true);
        m->process_chosen = true;
        m->wanted_layer_height.reset();
        // Filamentos podem depender do processo (compatible_prints).
        m->bundle->update_compatible(PresetSelectCompatibleType::Never, PresetSelectCompatibleType::Always);
        break;
    }
}

namespace {

const std::vector<std::string> &option_keys(PresetKind kind)
{
    switch (kind) {
    case PresetKind::Printer: return Preset::printer_options();
    case PresetKind::Filament: return Preset::filament_options();
    case PresetKind::Process: return Preset::print_options();
    }
    throw std::invalid_argument("invalid preset kind");
}

struct OptionPlace
{
    const char *key, *page, *group, *line;
};

#include "option_layout.inc"

const std::vector<OptionPlace> &option_layout(PresetKind kind)
{
    static const std::vector<OptionPlace> printer(std::begin(printer_layout), std::end(printer_layout));
    static const std::vector<OptionPlace> filament(std::begin(filament_layout), std::end(filament_layout));
    static const std::vector<OptionPlace> process(std::begin(process_layout), std::end(process_layout));
    switch (kind) {
    case PresetKind::Printer: return printer;
    case PresetKind::Filament: return filament;
    case PresetKind::Process: return process;
    }
    throw std::invalid_argument("invalid preset kind");
}

Preset::Type preset_type(PresetKind kind)
{
    switch (kind) {
    case PresetKind::Printer: return Preset::TYPE_PRINTER;
    case PresetKind::Filament: return Preset::TYPE_FILAMENT;
    case PresetKind::Process: return Preset::TYPE_PRINT;
    }
    throw std::invalid_argument("invalid preset kind");
}

// Opção que a GUI do Orca mostra para FFF: com rótulo, não de desenvolvimento nem só leitura.
// As de vínculo entre presets (herança, compatibilidade) não são parâmetros de impressão.
const ConfigOptionDef *editable_def(const std::string &key)
{
    static const std::set<std::string> internal = {
        "inherits", "compatible_printers", "compatible_printers_condition", "compatible_prints",
        "compatible_prints_condition", "print_settings_id", "filament_settings_id", "printer_settings_id",
        "printer_model", "printer_variant", "default_print_profile", "default_filament_profile",
        "print_compatible_printers", "filament_vendor", "filament_ids", "printer_agent", "printable_area"};
    const ConfigOptionDef *def = print_config_def.get(key);
    if (def == nullptr || def->label.empty() || def->readonly || def->mode == comDevelop ||
        def->printer_technology == ptSLA || def->type == coNone || internal.count(key))
        return nullptr;
    return def;
}

const char *type_name(ConfigOptionType type)
{
    switch (ConfigOptionType(int(type) & ~int(coVectorType))) {
    case coBool: return "bool";
    case coInt: return "int";
    case coFloat: return "float";
    case coPercent: return "percent";
    case coFloatOrPercent: return "float_or_percent";
    case coString: return "string";
    case coEnum: return "enum";
    case coPoint: return "point";
    case coPoint3: return "point";
    default: return "other";
    }
}

// O Snapmaker Orca aceita filamento sem filament_id (nem no pai) e usa o setting_id no lugar;
// o PresetBundle do Orca descartaria o fabricante inteiro ("can not find filament_id").
void set_filament_id(const fs::path &path)
{
    boost::nowide::ifstream in(path.string());
    nlohmann::json profile = nlohmann::json::parse(in, nullptr, false);
    in.close();
    if (!profile.is_object() || profile.value("type", "") != "filament" || profile.contains("filament_id") ||
        !profile.contains("setting_id") || !profile["setting_id"].is_string() || profile["setting_id"] == "")
        return;
    profile["filament_id"] = profile["setting_id"];
    boost::nowide::ofstream out(path.string());
    out << profile.dump(4);
}

// Variáveis do G-code personalizado que o Orca não conhece; sem a troca, o fatiamento falha
// ("Not a variable name"). O QIDI Studio chama de chamber_temperatures a opção
// chamber_temperature do Orca: o Orca renomeia a opção ao ler (PrintConfigDef::handle_legacy),
// mas não a variável ("CHAMBER=[chamber_temperatures]" no Q2). E chama de is_support_3mf o
// use_3mf do Orca: com ele, a Q2 recebe o .gcode.3mf, de onde o Moonraker tira a miniatura.
// O Creality Print tem opções próprias que o G-code da K2 testa: multicolor_method (purga
// calculada no G-code de troca em vez da torre; o padrão false é o que o Orca faz),
// flush_into_skeleton (purga dentro da peça) e flush_length_5 (o Orca divide a purga em
// flush_length_1..4).
// O (?!") poupa as chaves do JSON dos perfis ("multicolor_method": "0").
std::string renamed_legacy_variables(std::string text)
{
    static const std::vector<std::pair<std::regex, std::string>> replacements = {
        {std::regex("\\bchamber_temperatures\\b"), "chamber_temperature"},
        {std::regex("\\bis_support_3mf\\b"), "use_3mf"},
        {std::regex("\\bmulticolor_method\\b(?!\")"), "false"},
        {std::regex("\\bflush_into_skeleton\\b(?!\")"), "false"},
        {std::regex("\\bflush_length_5\\b(?!\")"), "0"},
    };
    for (const auto &[variable, value] : replacements)
        if (std::regex_search(text, variable))
            text = std::regex_replace(text, variable, value);
    return text;
}

void rename_legacy_variables(const fs::path &path)
{
    boost::nowide::ifstream in(path.string());
    const std::string text((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    in.close();
    const std::string renamed = renamed_legacy_variables(text);
    if (renamed == text)
        return;
    boost::nowide::ofstream out(path.string());
    out << renamed;
}

// O Bambu Studio e os fatiadores derivados dele (QIDI Studio, Creality Print) têm só a torre
// de limpeza da Bambu, que o Orca chama de type1 e usa sozinho apenas nas impressoras da
// Bambu (Print::wipe_tower_type). Sem a opção no perfil, o Orca usaria a type2 (da Prusa), que
// antes de cada troca puxa o filamento ~90 mm e o esfria indo e voltando, como numa MMU sem
// cortador; na Q2 isso faz o corte falhar ("QDE_004_008 Extruder unloading failure"). Vai na
// raiz das impressoras (sem inherits), para valer em todas.
void set_bambu_wipe_tower(const fs::path &path)
{
    boost::nowide::ifstream in(path.string());
    nlohmann::json profile = nlohmann::json::parse(in, nullptr, false);
    in.close();
    if (!profile.is_object() || profile.value("type", "") != "machine" || profile.contains("inherits") ||
        profile.contains("wipe_tower_type"))
        return;
    profile["wipe_tower_type"] = "type1";
    boost::nowide::ofstream out(path.string());
    out << profile.dump(4);
}

// Opções numéricas fora da faixa do Orca, que o Bambu Studio aceita (tree_support_wall_count -1,
// "automático" nele, onde o Orca usa 0): o fatiamento passa, mas o Orca recusa o 3MF salvo com
// elas ("not in range", validate() de PrintConfig.cpp, o mesmo teste aqui). Saem do perfil, que
// fica com o valor do pai ou o padrão do Orca.
void drop_out_of_range(const fs::path &path)
{
    DynamicPrintConfig                 config;
    std::map<std::string, std::string> key_values;
    std::string                        reason;
    config.load_from_json(path.string(), ForwardCompatibilitySubstitutionRule::EnableSilent, key_values, reason);
    std::vector<std::string> keys;
    for (const std::string &key : config.keys()) {
        const ConfigOption    *opt = config.option(key);
        const ConfigOptionDef *def = print_config_def.get(key);
        if (opt == nullptr || def == nullptr)
            continue;
        bool out_of_range = false;
        switch (opt->type()) {
        case coFloat:
        case coPercent:
        case coFloatOrPercent: out_of_range = !def->is_value_valid(static_cast<const ConfigOptionFloat *>(opt)->value); break;
        case coInt: out_of_range = !def->is_value_valid(static_cast<const ConfigOptionInt *>(opt)->value); break;
        case coFloats:
        case coPercents: {
            const auto *values = static_cast<const ConfigOptionVector<double> *>(opt);
            for (size_t i = 0; i < values->values.size(); ++i)
                out_of_range |= !values->is_nil(i) && !def->is_value_valid(values->values[i]);
            break;
        }
        case coInts: {
            const auto *values = static_cast<const ConfigOptionVector<int> *>(opt);
            for (size_t i = 0; i < values->values.size(); ++i)
                out_of_range |= !values->is_nil(i) && !def->is_value_valid(values->values[i]);
            break;
        }
        default:;
        }
        if (out_of_range)
            keys.push_back(key);
    }
    if (keys.empty())
        return;
    boost::nowide::ifstream in(path.string());
    nlohmann::json profile = nlohmann::json::parse(in, nullptr, false);
    in.close();
    if (!profile.is_object())
        return;
    for (const std::string &key : keys) {
        profile.erase(key);
        BOOST_LOG_TRIVIAL(warning) << "vendor profile " << path << ": dropped out of range " << key;
    }
    boost::nowide::ofstream out(path.string());
    out << profile.dump(4);
}

// Copia os perfis de um fatiador do fabricante (Creality Print...) para dst, ajustando as
// opções cujo valor o Orca não aceita: com uma só, PresetBundle descartaria o fabricante
// inteiro ("Invalid value provided for parameter X"). O Creality Print grava "" onde o Orca
// usa "nil" (sem valor) nas listas por extrusora; se trocar não basta, a opção sai e o perfil
// fica com o valor do pai.
void copy_for_orca(const fs::path &src, const fs::path &dst)
{
    static const std::regex invalid_option("Invalid value provided for parameter ([A-Za-z0-9_]+)");
    const std::string app = src.parent_path().filename().string();
    const bool bambu_wipe_tower = app == "BambuStudio" || app == "QIDIStudio" || app == "CrealityPrint";
    for (fs::recursive_directory_iterator it(src), end; it != end; ++it) {
        const fs::path target = dst / fs::relative(it->path(), src);
        if (fs::is_directory(it->path())) {
            fs::create_directories(target);
            continue;
        }
        fs::copy_file(it->path(), target);
        if (target.extension() != ".json")
            continue;
        set_filament_id(target);
        rename_legacy_variables(target);
        if (bambu_wipe_tower)
            set_bambu_wipe_tower(target);
        for (int dropped = 0; dropped < 100; ++dropped) {
            DynamicPrintConfig                 config;
            std::map<std::string, std::string> key_values;
            std::string                        reason;
            config.load_from_json(target.string(), ForwardCompatibilitySubstitutionRule::EnableSilent, key_values, reason);
            std::smatch match;
            if (reason.empty() || !std::regex_search(reason, match, invalid_option))
                break;
            boost::nowide::ifstream in(target.string());
            nlohmann::json profile = nlohmann::json::parse(in, nullptr, false);
            in.close();
            const std::string key = match[1].str();
            if (!profile.is_object() || !profile.contains(key))
                break;
            nlohmann::json &value = profile[key];
            bool nil = false;
            if (value.is_array())
                for (nlohmann::json &item : value)
                    if (item == "") {
                        item = "nil";
                        nil = true;
                    }
            if (!nil)
                profile.erase(key);
            BOOST_LOG_TRIVIAL(warning) << "vendor profile " << it->path() << ": " << (nil ? "nil in " : "dropped ") << key;
            boost::nowide::ofstream out(target.string());
            out << profile.dump(4);
        }
        drop_out_of_range(target);
    }
}

// Purga que o firmware faz sozinho a cada troca, fora do G-code, em mm³ por filamento (o que
// entra). Na K2 com CFS (bico único) o G-code de troca só tem o T, e a impressora purga pela
// matriz de purga; o Creality Print soma essa purga ao filamento usado
// (GCodeProcessor::calculateVolume, com multicolor_method 0). Na K3 cada filamento tem o seu
// bico (single_extruder_multi_material 0) e não há purga. Vazio quando o G-code já conta a
// purga (FLUSH_START da Bambu) ou ela vai para a torre.
std::map<size_t, double> firmware_flush(const GCodeProcessorResult &result, const PrintConfig &config)
{
    std::map<size_t, double> flush;
    if (!config.single_extruder_multi_material || config.purge_in_prime_tower || !result.print_statistics.flush_per_filament.empty())
        return flush;
    const size_t              count      = config.filament_colour.size();
    const std::vector<double> matrix     = get_flush_volumes_matrix(config.flush_volumes_matrix.values, 0, config.nozzle_diameter.size());
    const double              multiplier = config.flush_multiplier.get_at(0);
    int                       current    = -1;
    for (const GCodeProcessorResult::MoveVertex &move : result.moves)
        if (move.type == EMoveType::Tool_change) {
            const int next = move.extruder_id;
            if (current >= 0 && next != current && size_t(current) * count + next < matrix.size())
                flush[next] += matrix[size_t(current) * count + next] * multiplier;
            current = next;
        }
    return flush;
}

SliceStats stats_of(const GCodeProcessorResult &result, const std::map<size_t, double> &firmware_flush = {})
{
    SliceStats stats;
    stats.print_time_s = result.print_statistics.modes.front().time;
    // Por filamento, do volume (com a purga das trocas), como o "filament used" no fim do
    // G-code: diâmetro, densidade e preço de cada um. Os totais são a soma.
    auto at = [](const std::vector<float> &values, size_t i, double fallback) {
        return i < values.size() ? double(values[i]) : fallback;
    };
    std::map<size_t, double> volumes = result.print_statistics.total_volumes_per_extruder;
    for (const auto &[index, mm3] : firmware_flush)
        volumes[index] += mm3;
    for (const auto &[index, mm3] : volumes) {
        stats.filament_mm3 += mm3;
        FilamentUse use;
        use.index = index;
        const double radius = at(result.filament_diameters, index, 1.75) / 2.0;
        use.m = mm3 / (M_PI * radius * radius) / 1000.0;
        use.g = mm3 * at(result.filament_densities, index, 1.0) / 1000.0;
        use.cost = at(result.filament_costs, index, 0.0) * use.g / 1000.0;
        stats.filament_m += use.m;
        stats.filament_g += use.g;
        stats.cost += use.cost;
        stats.filaments.push_back(use);
    }
    // Tempo de cada tipo de extrusão (modo normal), como a coluna de tempo da legenda do Orca.
    stats.role_time_s.assign(size_t(erCount), 0.0);
    for (const GCodeProcessorResult::MoveVertex &move : result.moves)
        if (move.type == EMoveType::Extrude && size_t(move.extrusion_role) < stats.role_time_s.size())
            stats.role_time_s[size_t(move.extrusion_role)] += move.time[0];
    return stats;
}

// O primeiro fabricante (na ordem de vendors, com o índice de cada um) que declara uma das
// impressoras.
std::string vendor_of(const std::vector<std::pair<std::string, fs::path>> &vendors, const std::set<std::string> &printers)
{
    for (const auto &[vendor, path] : vendors) {
        boost::nowide::ifstream in(path.string());
        const nlohmann::json index = nlohmann::json::parse(in, nullptr, false);
        if (!index.is_object() || !index.contains("machine_list"))
            continue;
        for (const nlohmann::json &machine : index["machine_list"])
            if (printers.count(machine.value("name", "")) > 0)
                return vendor;
    }
    return {};
}

Model read_model(const std::string &path)
{
    DynamicPrintConfig        config;
    ConfigSubstitutionContext substitutions(ForwardCompatibilitySubstitutionRule::EnableSilent);
    Model model = Model::read_from_file(path, &config, &substitutions,
                                        LoadStrategy::LoadModel | LoadStrategy::AddDefaultInstances);
    if (model.objects.empty())
        throw std::runtime_error(format(_u8L("No objects found in %1%."), fs::path(path).filename().string()));
    // Como a GUI do Orca: a origem de cada objeto passa a ser o centro da sua malha,
    // então a posição exibida é a do centro e girar/escalar usa esse pivô.
    for (ModelObject *object : model.objects)
        object->center_around_origin();
    return model;
}

std::vector<std::string> translated(const std::vector<std::string> &texts)
{
    std::vector<std::string> out;
    for (const std::string &text : texts)
        out.push_back(i18n::translate(text));
    return out;
}

} // namespace

Bed Engine::bed() const
{
    if (!m->bundle)
        throw std::runtime_error("no vendor loaded");
    const DynamicPrintConfig &config = m->bundle->printers.get_edited_preset().config;
    Bed bed;
    if (const auto *area = config.opt<ConfigOptionPoints>("printable_area"))
        for (const Vec2d &p : area->values)
            bed.shape.push_back({p.x(), p.y()});
    if (const auto *height = config.opt<ConfigOptionFloat>("printable_height"))
        bed.max_height = height->value;
    return bed;
}

// Malha das partes do objeto em coordenadas do objeto, como raw_indexed_triangle_set(), mas
// com as pintadas em várias cores (mmu_segmentation) divididas nos triângulos da pintura.
// filaments recebe o filamento (a partir de 0) de cada triângulo: o da cor pintada ou o da peça;
// com ele, os triângulos vêm agrupados por filamento, em ordem crescente.
static indexed_triangle_set object_mesh(const ModelObject &object, std::vector<uint8_t> *filaments)
{
    indexed_triangle_set out;
    auto append_its = [&](const indexed_triangle_set &its, const Transform3d &matrix, int filament) {
        const size_t first = out.vertices.size();
        for (const stl_vertex &v : its.vertices)
            out.vertices.emplace_back((matrix * v.cast<double>()).cast<float>());
        for (const stl_triangle_vertex_indices &t : its.indices)
            out.indices.emplace_back(t + stl_triangle_vertex_indices::Constant(int(first)));
        if (filaments != nullptr)
            filaments->insert(filaments->end(), its.indices.size(), uint8_t(std::clamp(filament, 0, 255)));
    };
    for (const ModelVolume *volume : object.volumes) {
        if (!volume->is_model_part())
            continue;
        const int part_filament = volume->extruder_id() - 1;
        if (volume->mmu_segmentation_facets.empty()) {
            append_its(volume->mesh().its, volume->get_matrix(), part_filament);
            continue;
        }
        // Índice = estado do TriangleSelector: 0 sem pintura, n o filamento n.
        std::vector<indexed_triangle_set> per_state;
        volume->mmu_segmentation_facets.get_facets(*volume, per_state);
        for (size_t state = 0; state < per_state.size(); ++state)
            append_its(per_state[state], volume->get_matrix(), state == 0 ? part_filament : int(state) - 1);
    }
    if (filaments == nullptr)
        return out;
    // Triângulos agrupados por filamento (estável), para o app desenhar cada grupo de uma vez.
    std::array<size_t, 257> start{};
    for (uint8_t filament : *filaments)
        ++start[filament + 1];
    for (size_t i = 1; i < start.size(); ++i)
        start[i] += start[i - 1];
    std::vector<stl_triangle_vertex_indices> indices(out.indices.size());
    std::vector<uint8_t>                     sorted(filaments->size());
    for (size_t i = 0; i < out.indices.size(); ++i) {
        const size_t to = start[(*filaments)[i]]++;
        indices[to]     = out.indices[i];
        sorted[to]      = (*filaments)[i];
    }
    out.indices = std::move(indices);
    *filaments  = std::move(sorted);
    return out;
}

void Engine::load_model(const std::string &path)
{
    // Lê antes de limpar: se o arquivo for inválido, a mesa continua como estava.
    Model model = read_model(path);
    m->reset_plates();
    m->add(std::move(model));
}

void Engine::add_model(const std::string &path)
{
    m->add(read_model(path));
}

std::string Engine::open_project(const std::string &path)
{
    DynamicPrintConfig        config;
    ConfigSubstitutionContext substitutions(ForwardCompatibilitySubstitutionRule::EnableSilent);
    En3mfType                 type = En3mfType::From_Other;
    PlateDataPtrs             plates;
    // read_from_archive() aloca os presets embutidos; quem chama os libera.
    struct OwnedPresets : std::vector<Preset *> {
        ~OwnedPresets() { for (Preset *preset : *this) delete preset; }
    } project_presets;
    Semver version;
    Model  model;
    try {
        model = Model::read_from_archive(path, &config, &substitutions, type,
                                         LoadStrategy::LoadModel | LoadStrategy::LoadConfig | LoadStrategy::AddDefaultInstances,
                                         &plates, &project_presets, &version);
    } catch (...) {
        release_PlateData_list(plates);
        throw;
    }
    const size_t plate_count = std::max<size_t>(plates.size(), 1);
    release_PlateData_list(plates);

    if (!config.has("printer_settings_id")) {
        load_model(path);
        if (m->vendor.empty())
            throw std::runtime_error("no vendor loaded");
        return m->vendor;
    }

    // A impressora do projeto ou o preset de sistema de que ela herda (último de inherits_group).
    std::set<std::string> printers{config.opt_string("printer_settings_id")};
    if (const auto *inherits = config.option<ConfigOptionStrings>("inherits_group"); inherits != nullptr && !inherits->values.empty())
        printers.insert(inherits->values.back());
    // Com o mesmo nome no Orca e no fatiador do fabricante, vale o do fabricante.
    std::vector<std::pair<std::string, fs::path>> indexes;
    for (const std::string &v : vendors())
        indexes.emplace_back(v, m->vendor_index(v));
    std::string vendor = vendor_of(indexes, printers);
    if (vendor.empty())
        vendor = m->vendor;
    if (vendor.empty())
        throw std::runtime_error(format(_u8L("The project printer %1% is not among the installed profiles."),
                                        config.opt_string("printer_settings_id")));
    if (vendor != m->vendor)
        load_vendor(vendor);
    // O Bambu Studio 2.x grava algumas opções de filamento como texto (filament_notes = ""),
    // que no Orca são listas: "" vira lista vazia, e PresetBundle::load_config_file_config()
    // falha ao espalhá-la pelos filamentos ("Assigning from an empty vector"). Sem a opção,
    // cada filamento fica com o valor padrão, como numa opção que o arquivo não traz.
    for (const std::string &key : m->bundle->filaments.default_preset().config.keys())
        if (const ConfigOption *opt = config.option(key);
            opt != nullptr && !opt->is_scalar() && static_cast<const ConfigOptionVectorBase *>(opt)->size() == 0)
            config.erase(key);
    // O G-code personalizado de um projeto do Creality Print ou do QIDI Studio usa as mesmas
    // variáveis que rename_legacy_variables() troca nos perfis desses fatiadores.
    for (const std::string &key : config.keys())
        if (ConfigOption *opt = config.optptr(key); opt->type() == coString) {
            std::string &value = static_cast<ConfigOptionString *>(opt)->value;
            value              = renamed_legacy_variables(value);
        } else if (opt->type() == coStrings)
            for (std::string &value : static_cast<ConfigOptionStrings *>(opt)->values)
                value = renamed_legacy_variables(value);
    // Como Plater::load_files(): presets embutidos, depois a configuração do projeto.
    if (!project_presets.empty())
        m->bundle->load_project_embedded_presets(project_presets, ForwardCompatibilitySubstitutionRule::EnableSilent);
    m->bundle->load_config_model(path, std::move(config), version);
    m->process_chosen = true;
    m->wanted_layer_height.reset();
    for (Preset &filament : m->bundle->filaments)
        filament.is_visible = true;

    // O arquivo diz a placa de cada objeto pelo id do 3MF, que o carregamento não devolve;
    // mas as placas ficam numa grade (Impl::plate_origin()). Cada instância vai para a placa
    // mais próxima do seu centro, como objeto próprio, na posição relativa a ela.
    const BoundingBoxf bed     = m->build_volume().bounding_volume2d();
    const Vec2d        stride  = bed.size() * (1. + 1. / 5.);
    const int          columns = Impl::plate_columns(plate_count);
    m->reset_plates();
    m->plates.resize(plate_count);
    for (size_t i = 0; i < model.objects.size(); ++i)
        for (size_t j = 0; j < model.objects[i]->instances.size(); ++j) {
            const Vec2d offset = to_2d(model.objects[i]->instance_bounding_box(j).center()) - bed.center();
            const int   column = int(std::lround(offset.x() / stride.x()));
            const int   row    = int(std::lround(-offset.y() / stride.y()));
            const long  index  = plate_count == 1 ? 0 : long(row) * columns + column;
            if (plate_count > 1 && !(column >= 0 && column < columns && row >= 0 && index < long(plate_count)))
                continue;
            ModelObject   *added  = Impl::copy_object(m->plates[index], *model.objects[i]);
            ModelInstance *placed = added->instances.front();
            placed->set_transformation(model.objects[i]->instances[j]->get_transformation());
            placed->set_offset(placed->get_offset() - to_3d(m->plate_origin(size_t(index), plate_count), 0.));
        }
    // Ações por altura de cada placa; cada Model da mesa é uma placa só (curr_plate_index 0).
    for (const auto &[index, info] : model.plates_custom_gcodes)
        if (index >= 0 && size_t(index) < m->plates.size())
            m->plates[index].plates_custom_gcodes[0] = info;
    // A primeira placa com objetos.
    const auto first = std::find_if(m->plates.begin(), m->plates.end(), [](const Model &p) { return !p.objects.empty(); });
    if (first == m->plates.end())
        throw std::runtime_error(format(_u8L("No objects found in %1%."), fs::path(path).filename().string()));
    m->model = std::move(*first);
    *first = Model();
    m->plate = size_t(first - m->plates.begin());
    return m->vendor;
}

std::vector<size_t> Engine::plates() const
{
    std::vector<size_t> objects;
    for (size_t i = 0; i < m->plates.size(); ++i)
        objects.push_back(i == m->plate ? m->model.objects.size() : m->plates[i].objects.size());
    return objects;
}

size_t Engine::current_plate() const
{
    return m->plate;
}

void Engine::select_plate(size_t plate)
{
    m->show_plate(plate);
}

size_t Engine::add_plate()
{
    m->plates.emplace_back();
    return m->plates.size() - 1;
}

void Engine::delete_plate(size_t plate, std::optional<size_t> move_to)
{
    if (plate >= m->plates.size() || (move_to && (*move_to >= m->plates.size() || *move_to == plate)))
        throw std::out_of_range("invalid plate");
    if (m->plates.size() == 1)
        throw std::runtime_error("the last plate cannot be deleted");
    // Sai da mesa: vai para a placa que recebe os objetos, ou para a vizinha.
    if (plate == m->plate)
        m->show_plate(move_to.value_or(plate > 0 ? plate - 1 : 1));
    if (move_to) {
        Model &target = m->plate_model(*move_to);
        for (const ModelObject *object : m->plates[plate].objects)
            Impl::copy_object(target, *object);
        m->arrange(target);
    }
    m->plates.erase(m->plates.begin() + plate);
    if (m->plate > plate)
        --m->plate;
}

void Engine::move_to_plate(size_t object, size_t plate)
{
    const ModelObject &source = m->object(object);
    if (plate == m->plate)
        return;
    Model &target = m->plate_model(plate);
    Impl::copy_object(target, source);
    m->model.delete_object(object);
    m->arrange(target);
}

void Engine::save_project(const std::string &path, const Thumbnail &thumbnail)
{
    m->store_3mf(path, thumbnail);
}

bool Engine::gcode_3mf() const
{
    if (!m->bundle)
        throw std::runtime_error("no vendor loaded");
    // Bambu só imprime .gcode.3mf; nas demais, a opção "use_3mf" do perfil (ex.: Qidi Q2).
    const auto *use_3mf = m->printer().option<ConfigOptionBool>("use_3mf");
    return m->bundle->is_bbl_vendor() || (use_3mf != nullptr && use_3mf->value);
}

void Engine::export_gcode_3mf(const std::string &gcode_path, const std::string &path, const Thumbnail &thumbnail)
{
    if (m->result.moves.empty() || !fs::exists(gcode_path))
        throw std::runtime_error("nothing sliced");
    m->store_3mf(path, thumbnail, gcode_path);
}

SliceStats Engine::load_gcode(const std::string &path)
{
    GCodeProcessor processor;
    processor.init_filament_maps_and_nozzle_type_when_import_only_gcode();
    GCodeProcessor::s_IsBBLPrinter = m->bundle && m->bundle->is_bbl_vendor();
    processor.process_file(path);
    m->result = processor.extract_result();
    m->firmware_flush.clear();
    return stats_of(m->result);
}

void Engine::duplicate(size_t object)
{
    Model copy;
    copy.add_object(m->object(object));
    m->add(std::move(copy), false);
}

void Engine::remove(size_t object)
{
    m->object(object);
    m->model.delete_object(object);
}

void Engine::arrange()
{
    if (!m->bundle)
        throw std::runtime_error("no vendor loaded");
    m->arrange_all();
}

std::vector<ObjectMesh> Engine::objects() const
{
    std::vector<ObjectMesh> out;
    for (const ModelObject *object : m->model.objects) {
        ObjectMesh                 mesh;
        mesh.name = object->name;
        const ConfigOption *extruder = object->config.option("extruder");
        mesh.filament = extruder != nullptr && extruder->getInt() > 0 ? size_t(extruder->getInt() - 1) : 0;
        const indexed_triangle_set its = object_mesh(*object, &mesh.filaments);
        mesh.positions.reserve(its.vertices.size() * 3);
        for (const stl_vertex &v : its.vertices)
            mesh.positions.insert(mesh.positions.end(), {v.x(), v.y(), v.z()});
        mesh.indices.reserve(its.indices.size() * 3);
        for (const stl_triangle_vertex_indices &t : its.indices)
            mesh.indices.insert(mesh.indices.end(), {uint32_t(t[0]), uint32_t(t[1]), uint32_t(t[2])});
        out.push_back(std::move(mesh));
    }
    return out;
}

std::vector<std::string> Engine::filament_colors() const
{
    if (!m->bundle)
        return {};
    const auto *colors = m->bundle->project_config.option<ConfigOptionStrings>("filament_colour");
    return colors != nullptr ? colors->values : std::vector<std::string>{};
}

std::vector<std::string> Engine::filament_presets() const
{
    return m->bundle ? m->bundle->filament_presets : std::vector<std::string>{};
}

std::vector<std::string> Engine::filament_types() const
{
    if (!m->bundle)
        return {};
    const DynamicPrintConfig config = m->bundle->full_config();
    return config.option<ConfigOptionStrings>("filament_type")->values;
}

namespace {

// get_min_flush_volumes() da GUI do Orca (Plater.cpp): o volume do bico menos o que a
// retração longa no corte do filamento (Bambu) já tira dele, por filamento.
std::vector<int> min_flush_volumes(const DynamicPrintConfig &config, size_t nozzle)
{
    const auto *nozzle_volume = config.option<ConfigOptionFloatsNullable>("nozzle_volume");
    const int   volume        = nozzle_volume != nullptr ? int(nozzle_volume->get_at(nozzle)) : 0;
    const auto *enabled_opt   = config.option<ConfigOptionInt>("enable_long_retraction_when_cut");
    const int   enabled       = enabled_opt != nullptr ? enabled_opt->value : 0;
    const auto *activated_opt = config.option<ConfigOptionBools>("long_retractions_when_cut");
    const bool  activated     = activated_opt != nullptr && activated_opt->get_at(nozzle);

    const size_t count = config.option<ConfigOptionFloats>("filament_diameter")->values.size();
    std::vector<double> filament_distance(count, 18.0), printer_distance(count, 18.0);
    std::vector<unsigned char> filament_active(count, 0);
    if (const auto *opt = config.option<ConfigOptionFloats>("filament_retraction_distances_when_cut"))
        filament_distance = opt->values;
    if (const auto *opt = config.option<ConfigOptionFloats>("retraction_distances_when_cut"))
        printer_distance = opt->values;
    if (const auto *opt = config.option<ConfigOptionBools>("filament_long_retractions_when_cut"))
        filament_active = opt->values;

    std::vector<int> out;
    for (size_t i = 0; i < count; ++i) {
        double retract = enabled && activated ? printer_distance[std::min(nozzle, printer_distance.size() - 1)] : 0;
        const unsigned char active = i < filament_active.size() ? filament_active[i] : 0;
        if (active == 0)
            retract = 0;
        else if (active == 1 && enabled == LongRectrationLevel::EnableFilament)
            retract = i < filament_distance.size() && !std::isnan(filament_distance[i]) ? filament_distance[i]
                                                                                      : printer_distance[std::min(nozzle, printer_distance.size() - 1)];
        out.push_back(volume - int(PI * 1.75 * 1.75 / 4 * int(retract)));
    }
    return out;
}

// "#RRGGBB" ou "#RRGGBBAA" em RGBA; inválida = branco, como o wxColour da GUI faria preto.
std::array<unsigned char, 4> parse_color(const std::string &color)
{
    std::array<unsigned char, 4> rgba{255, 255, 255, 255};
    if ((color.size() == 7 || color.size() == 9) && color[0] == '#')
        for (size_t i = 0; i + 1 < color.size(); i += 2)
            rgba[i / 2] = (unsigned char) std::stoul(color.substr(i + 1, 2), nullptr, 16);
    return rgba;
}

bool valid_color(const std::string &color)
{
    static const std::regex hex("#[0-9A-Fa-f]{6}");
    return std::regex_match(color, hex);
}

} // namespace

// Sidebar::auto_calc_flushing_volumes_internal() do Orca, para as linhas e colunas do
// filamento (ou de todos), em cada extrusor.
// update_multi_material_filament_presets() muda o número de filamentos sem mexer nas cores do
// projeto (a GUI do Orca as ajusta à parte); os novos recebem cores da paleta.
void Engine::Impl::sync_filament_colors()
{
    DynamicPrintConfig &project = bundle->project_config;
    auto               &colors  = project.option<ConfigOptionStrings>("filament_colour")->values;
    const size_t        count   = filament_count();
    const size_t        old     = colors.size();
    if (old == count)
        return;
    colors.resize(count);
    for (size_t i = old; i < count; ++i)
        colors[i] = FILAMENT_PALETTE[i % std::size(FILAMENT_PALETTE)];
    if (auto *multi = project.option<ConfigOptionStrings>("filament_multi_colour")) {
        multi->values.resize(count);
        for (size_t i = old; i < count; ++i)
            multi->values[i] = colors[i];
    }
    if (auto *type = project.option<ConfigOptionStrings>("filament_colour_type"))
        type->values.resize(count, "1");
    if (auto *map = project.option<ConfigOptionInts>("filament_map"))
        map->values.resize(count, 1);
    for (size_t i = old; i < count; ++i)
        calc_flush_volumes(i);
}

void Engine::Impl::calc_flush_volumes(std::optional<size_t> filament)
{
    const DynamicPrintConfig full     = bundle->full_config();
    auto                    &matrix   = bundle->project_config.option<ConfigOptionFloats>("flush_volumes_matrix")->values;
    const size_t             nozzles  = size_t(bundle->get_printer_extruder_count());
    const size_t             count    = filament_count();
    const auto              *colors   = bundle->project_config.option<ConfigOptionStrings>("filament_colour");
    const auto              *datasets = full.option<ConfigOptionIntsNullable>("nozzle_flush_dataset");
    if (matrix.size() != count * count * nozzles)
        return; // update_multi_material_filament_presets() ainda não ajustou o tamanho
    std::vector<std::array<unsigned char, 4>> rgba;
    for (size_t i = 0; i < count; ++i)
        rgba.push_back(parse_color(colors->get_at(i)));

    for (size_t nozzle = 0; nozzle < nozzles; ++nozzle) {
        const std::vector<int> min_volumes = min_flush_volumes(full, nozzle);
        const int              dataset     = datasets != nullptr ? datasets->get_at(nozzle) : 0;
        double                *nozzle_matrix = matrix.data() + count * count * nozzle;
        auto volume = [&](size_t from, size_t to) {
            if (is_support_filament(to))
                return g_flush_volume_to_support;
            FlushVolCalculator calculator(min_volumes[from], g_max_flush_volume, dataset);
            int out = calculator.calc_flush_vol(rgba[from][3], rgba[from][0], rgba[from][1], rgba[from][2],
                                                rgba[to][3], rgba[to][0], rgba[to][1], rgba[to][2]);
            return is_support_filament(from) ? std::max(out, g_min_flush_volume_from_support) : out;
        };
        for (size_t changed = 0; changed < count; ++changed) {
            if (filament && *filament != changed)
                continue;
            for (size_t other = 0; other < count; ++other)
                if (other != changed) {
                    nozzle_matrix[count * other + changed] = volume(other, changed);
                    nozzle_matrix[count * changed + other] = volume(changed, other);
                }
        }
    }
}

std::array<size_t, 2> Engine::filament_limits() const
{
    if (!m->bundle)
        throw std::runtime_error("no vendor loaded");
    const DynamicPrintConfig &printer   = m->printer();
    const size_t              extruders = printer.option<ConfigOptionFloats>("nozzle_diameter")->values.size();
    const bool                shared    = printer.opt_bool("single_extruder_multi_material");
    return {extruders, shared ? MAXIMUM_EXTRUDER_NUMBER : extruders};
}

void Engine::add_filament()
{
    const size_t count = filament_presets().size();
    if (count >= filament_limits()[1])
        throw std::runtime_error(_u8L("The printer does not support more filaments."));
    m->bundle->set_num_filaments(unsigned(count + 1), FILAMENT_PALETTE[count % std::size(FILAMENT_PALETTE)]);
    m->calc_flush_volumes(count);
}

// Como Sidebar::delete_filament() e Plater::on_filaments_delete() da GUI.
void Engine::remove_filament(size_t filament)
{
    if (!m->bundle)
        throw std::runtime_error("no vendor loaded");
    m->check_filament(filament);
    const size_t count = m->filament_count();
    if (count <= filament_limits()[0])
        throw std::runtime_error(_u8L("Each extruder of the printer needs a filament."));
    m->bundle->update_num_filaments(unsigned(filament));
    // A seleção do tipo Filamento é o preset do primeiro.
    m->select_filament(m->bundle->filament_presets.front());

    auto shift = [filament](ModelConfig &config, const char *key, bool erase_deleted) {
        if (!config.has(key))
            return;
        const int value = config.opt_int(key); // 1 = primeiro filamento; 0 = o do objeto
        if (value == int(filament) + 1) {
            if (erase_deleted)
                config.erase(key);
            else
                config.set_key_value(key, new ConfigOptionInt(1));
        } else if (value > int(filament) + 1) {
            config.set_key_value(key, new ConfigOptionInt(value - 1));
        }
    };
    for (Model *plate : m->all_plates()) {
        // Trocas por altura: as para ele saem, as dos seguintes descem um.
        for (auto &[index, info] : plate->plates_custom_gcodes) {
            auto &items = info.gcodes;
            items.erase(std::remove_if(items.begin(), items.end(), [filament](const CustomGCode::Item &item) {
                return item.type == CustomGCode::ToolChange && item.extruder == int(filament) + 1;
            }), items.end());
            for (CustomGCode::Item &item : items)
                if (item.type == CustomGCode::ToolChange && item.extruder > int(filament) + 1)
                    --item.extruder;
        }
        for (ModelObject *object : plate->objects) {
            shift(object->config, "extruder", false);
            for (const char *key : {"support_filament", "support_interface_filament"})
                shift(object->config, key, true);
            for (ModelVolume *volume : object->volumes) {
                // Pintura: estados acima do excluído descem um; os dele voltam a "sem pintura".
                volume->update_extruder_count_when_delete_filament(count - 1, filament + 1, 0);
                shift(volume->config, "extruder", false);
                for (const char *key : {"support_filament", "support_interface_filament"})
                    shift(volume->config, key, true);
            }
        }
    }
    DynamicPrintConfig &print = m->bundle->prints.get_edited_preset().config;
    for (const char *key : {"support_filament", "support_interface_filament", "wipe_tower_filament"})
        if (auto *opt = print.option<ConfigOptionInt>(key); opt != nullptr) {
            if (opt->value == int(filament) + 1)
                opt->value = 0;
            else if (opt->value > int(filament) + 1)
                --opt->value;
        }
}

void Engine::set_filament_preset(size_t filament, const std::string &name)
{
    if (!m->bundle)
        throw std::runtime_error("no vendor loaded");
    m->check_filament(filament);
    const Preset *preset = m->bundle->filaments.find_preset(name);
    if (preset == nullptr || preset->is_default || !preset->is_compatible)
        throw std::invalid_argument("preset not available: " + name);
    const bool was_support = m->is_support_filament(filament);
    if (filament == 0)
        m->select_filament(name);
    else
        m->bundle->set_filament_preset(filament, name);
    if (m->is_support_filament(filament) != was_support)
        m->calc_flush_volumes(filament);
}

void Engine::set_filament_color(size_t filament, const std::string &color)
{
    if (!m->bundle)
        throw std::runtime_error("no vendor loaded");
    m->check_filament(filament);
    if (!valid_color(color))
        throw std::invalid_argument("invalid color: " + color);
    DynamicPrintConfig &project = m->bundle->project_config;
    project.option<ConfigOptionStrings>("filament_colour")->values[filament] = color;
    if (auto *multi = project.option<ConfigOptionStrings>("filament_multi_colour"); multi != nullptr && filament < multi->size())
        multi->values[filament] = color;
    m->calc_flush_volumes(filament);
}

void Engine::set_object_filament(size_t object, size_t filament)
{
    if (!m->bundle)
        throw std::runtime_error("no vendor loaded");
    m->check_filament(filament);
    m->object(object).config.set_key_value("extruder", new ConfigOptionInt(int(filament) + 1));
}

namespace {

std::vector<std::string> settings_keys_of(SettingsTarget::Kind kind)
{
    std::vector<std::string> keys = PrintRegionConfig().keys();
    if (kind == SettingsTarget::Kind::Object)
        for (const std::string &key : PrintObjectConfig().keys())
            keys.push_back(key);
    else if (kind == SettingsTarget::Kind::Range)
        keys.push_back("layer_height");
    const std::vector<std::string> &print = Preset::print_options();
    std::vector<std::string> out;
    for (const std::string &key : keys)
        if (key != "extruder" && editable_def(key) != nullptr &&
            std::find(print.begin(), print.end(), key) != print.end() &&
            std::find(out.begin(), out.end(), key) == out.end())
            out.push_back(key);
    return out;
}

const std::vector<std::string> &settings_keys_cached(SettingsTarget::Kind kind)
{
    static const std::array<std::vector<std::string>, 3> keys = {
        settings_keys_of(SettingsTarget::Kind::Object), settings_keys_of(SettingsTarget::Kind::Volume),
        settings_keys_of(SettingsTarget::Kind::Range)};
    return keys[size_t(kind)];
}

t_layer_config_ranges::iterator range_at(ModelObject &object, size_t index)
{
    if (index >= object.layer_config_ranges.size())
        throw std::out_of_range("no height range " + std::to_string(index));
    return std::next(object.layer_config_ranges.begin(), long(index));
}

void check_range(const t_layer_config_ranges &ranges, const HeightRange &range, const t_layer_height_range *ignore)
{
    if (!(range.min >= 0.0 && range.min < range.max))
        throw std::invalid_argument(_u8L("The range must start below where it ends."));
    for (const auto &[other, config] : ranges)
        if ((ignore == nullptr || other != *ignore) && range.min < other.second && other.first < range.max)
            throw std::invalid_argument(format(_u8L("The range overlaps %1%-%2% mm."), other.first, other.second));
}

size_t range_index(const t_layer_config_ranges &ranges, const t_layer_height_range &key)
{
    return size_t(std::distance(ranges.begin(), ranges.find(key)));
}

} // namespace

ModelConfig &Engine::Impl::settings(const SettingsTarget &target)
{
    ModelObject &o = object(target.object);
    switch (target.kind) {
    case SettingsTarget::Kind::Object: return o.config;
    case SettingsTarget::Kind::Volume:
        if (target.index >= o.volumes.size())
            throw std::out_of_range("no volume " + std::to_string(target.index));
        return o.volumes[target.index]->config;
    case SettingsTarget::Kind::Range: return range_at(o, target.index)->second;
    }
    throw std::invalid_argument("invalid settings target");
}

std::vector<std::string> Engine::settings_keys(SettingsTarget::Kind kind) const
{
    return settings_keys_cached(kind);
}

std::string Engine::settings_json(const SettingsTarget &target) const
{
    const ModelConfig &config = m->settings(target);
    const std::vector<std::string> &keys = settings_keys_cached(target.kind);
    nlohmann::json out = nlohmann::json::object();
    for (const std::string &key : config.keys())
        if (std::find(keys.begin(), keys.end(), key) != keys.end())
            out[key] = config.opt_serialize(key);
    return out.dump();
}

void Engine::set_setting(const SettingsTarget &target, const std::string &key, const std::string &value)
{
    const std::vector<std::string> &keys = settings_keys_cached(target.kind);
    if (std::find(keys.begin(), keys.end(), key) == keys.end())
        throw std::invalid_argument("not an option of this target: " + key);
    ModelConfig &config = m->settings(target);
    // Sobre uma cópia, como set_option(): um valor inválido não muda nada.
    DynamicPrintConfig copy = config.get();
    try {
        copy.set_deserialize_strict(key, value);
    } catch (const BadOptionValueException &) {
        throw std::invalid_argument(format(_u8L("Invalid value provided for parameter %1%: %2%"), i18n::translate(editable_def(key)->label), value));
    }
    config.set_key_value(key, copy.option(key)->clone());
}

void Engine::erase_setting(const SettingsTarget &target, const std::string &key)
{
    m->settings(target).erase(key);
}

std::vector<HeightRange> Engine::height_ranges(size_t object) const
{
    std::vector<HeightRange> out;
    for (const auto &[range, config] : m->object(object).layer_config_ranges)
        out.push_back({range.first, range.second});
    return out;
}

size_t Engine::add_height_range(size_t object, const HeightRange &range)
{
    if (!m->bundle)
        throw std::runtime_error("no vendor loaded");
    ModelObject &o = m->object(object);
    check_range(o.layer_config_ranges, range, nullptr);
    // Como ObjectList::get_default_layer_config(): a altura de camada do objeto ou do processo.
    const DynamicPrintConfig &print = m->bundle->prints.get_edited_preset().config;
    const double layer_height = o.config.has("layer_height") ? o.config.opt_float("layer_height") : print.opt_float("layer_height");
    const t_layer_height_range key{range.min, range.max};
    ModelConfig &config = o.layer_config_ranges[key];
    config.set_key_value("layer_height", new ConfigOptionFloat(layer_height));
    config.set_key_value("extruder", new ConfigOptionInt(0));
    return range_index(o.layer_config_ranges, key);
}

size_t Engine::set_height_range(size_t object, size_t index, const HeightRange &range)
{
    ModelObject &o = m->object(object);
    auto it = range_at(o, index);
    check_range(o.layer_config_ranges, range, &it->first);
    ModelConfig config = std::move(it->second);
    o.layer_config_ranges.erase(it);
    const t_layer_height_range key{range.min, range.max};
    o.layer_config_ranges[key] = std::move(config);
    return range_index(o.layer_config_ranges, key);
}

void Engine::remove_height_range(size_t object, size_t index)
{
    ModelObject &o = m->object(object);
    o.layer_config_ranges.erase(range_at(o, index));
}

namespace {

// Placement da transformação, sem size e outside.
Placement decompose(const Geometry::Transformation &t)
{
    Placement p;
    std::copy(t.get_matrix().data(), t.get_matrix().data() + 16, p.transform.begin());
    const Vec3d offset = t.get_offset(), rotation = t.get_rotation(), scale = t.get_scaling_factor();
    for (int i = 0; i < 3; ++i) {
        p.offset[i] = offset[i];
        p.rotation[i] = Geometry::rad2deg(rotation[i]);
        p.scale[i] = scale[i];
    }
    return p;
}

Geometry::Transformation compose(const Vec3 &offset, const Vec3 &rotation, const Vec3 &scale)
{
    for (double s : scale)
        if (!(s > 0.0))
            throw std::invalid_argument("scale must be positive");
    Geometry::Transformation t;
    t.set_offset(Vec3d(offset[0], offset[1], offset[2]));
    t.set_rotation(Vec3d(Geometry::deg2rad(rotation[0]), Geometry::deg2rad(rotation[1]), Geometry::deg2rad(rotation[2])));
    t.set_scaling_factor(Vec3d(scale[0], scale[1], scale[2]));
    return t;
}

} // namespace

namespace {

ModelVolume &volume_at(ModelObject &object, size_t part)
{
    if (part >= object.volumes.size())
        throw std::out_of_range("no part " + std::to_string(part));
    return *object.volumes[part];
}

size_t model_parts(const ModelObject &object)
{
    return size_t(std::count_if(object.volumes.begin(), object.volumes.end(), [](const ModelVolume *v) { return v->is_model_part(); }));
}

// Fim de load_generic_subobject()/load_modifier(): filamento, ordem do Orca e o índice novo.
size_t part_added(ModelObject &object, ModelVolume &volume)
{
    // Como a GUI: o filamento da peça é o do objeto (0 = o do objeto) só nas peças do objeto.
    int extruder = 0;
    if (volume.is_model_part() && object.config.has("extruder"))
        extruder = object.config.opt_int("extruder");
    volume.config.set_key_value("extruder", new ConfigOptionInt(extruder));
    object.sort_volumes(true);
    object.invalidate_bounding_box();
    if (volume.is_model_part())
        object.ensure_on_bed();
    return size_t(std::distance(object.volumes.begin(), std::find(object.volumes.begin(), object.volumes.end(), &volume)));
}

} // namespace

std::vector<Part> Engine::parts(size_t object) const
{
    std::vector<Part> out;
    for (const ModelVolume *volume : m->object(object).volumes) {
        Part part;
        part.name      = volume->name;
        part.type      = Part::Type(int(volume->type()));
        part.placement = decompose(volume->get_transformation());
        const Vec3d size = volume->mesh().transformed_bounding_box(volume->get_matrix()).size();
        std::copy(size.data(), size.data() + 3, part.placement.size.begin());
        if (!volume->is_model_part()) {
            const indexed_triangle_set &its = volume->mesh().its;
            for (const stl_vertex &v : its.vertices)
                part.positions.insert(part.positions.end(), {v.x(), v.y(), v.z()});
            for (const stl_triangle_vertex_indices &t : its.indices)
                part.indices.insert(part.indices.end(), {uint32_t(t[0]), uint32_t(t[1]), uint32_t(t[2])});
        }
        out.push_back(std::move(part));
    }
    return out;
}

size_t Engine::add_part(size_t object, Part::Type type, Part::Shape shape)
{
    if (!m->bundle)
        throw std::runtime_error("no vendor loaded");
    ModelObject &o = m->object(object);
    // create_mesh() e load_generic_subobject() de GUI_ObjectList.cpp.
    const double side = 0.1 * m->build_volume().bounding_volume2d().size().maxCoeff();
    static const char *const names[] = {"Cube", "Cylinder", "Sphere", "Cone", "Disc", "Torus"};
    TriangleMesh mesh;
    switch (shape) {
    case Part::Shape::Cube: mesh = TriangleMesh(its_make_cube(side, side, side)); break;
    case Part::Shape::Cylinder: mesh = TriangleMesh(its_make_cylinder(0.5 * side, side)); break;
    case Part::Shape::Sphere: mesh = TriangleMesh(its_make_sphere(0.5 * side, PI / 90)); break;
    case Part::Shape::Cone: mesh = TriangleMesh(its_make_cone(0.5 * side, side)); break;
    case Part::Shape::Disc: mesh = TriangleMesh(its_make_cylinder(0.5 * side, 0.2f)); break;
    case Part::Shape::Torus: mesh = TriangleMesh(its_make_torus(0.5 * side, 0.125 * side, PI / 60)); break;
    default: throw std::invalid_argument("invalid shape");
    }
    if (int(type) < int(Part::Type::Model) || int(type) > int(Part::Type::SupportEnforcer))
        throw std::invalid_argument("invalid part type");

    const ModelInstance &instance    = *o.instances.front();
    const BoundingBoxf3  instance_bb = o.instance_bounding_box(0);
    ModelVolume         *volume      = o.add_volume(std::move(mesh), ModelVolumeType(int(type)));
    // Alinhada à mesa: desfaz a rotação e a escala da instância. (A GUI depois passa a
    // transformação da instância para as peças; aqui a instância fica como está.)
    const Transform3d to_object = instance.get_transformation().get_matrix_no_offset().inverse();
    volume->set_transformation(Geometry::Transformation(to_object));
    const BoundingBoxf3 mesh_bb = volume->mesh().bounding_box();
    const Vec3d offset = Vec3d(instance_bb.max.x(), instance_bb.min.y(), instance_bb.min.z()) + 0.5 * mesh_bb.size() - instance.get_offset();
    volume->set_offset(to_object * offset);
    volume->name = format("%1%-%2%", _u8L("Generic"), _u8L(names[int(shape)]));
    volume->source.is_from_builtin_objects = true;
    return part_added(o, *volume);
}

size_t Engine::add_part_from_file(size_t object, Part::Type type, const std::string &path)
{
    if (int(type) < int(Part::Type::Model) || int(type) > int(Part::Type::SupportEnforcer))
        throw std::invalid_argument("invalid part type");
    ModelObject &o = m->object(object);
    // ObjectList::load_modifier(), sem a galeria.
    Model model = Model::read_from_file(path, nullptr, nullptr, LoadStrategy::LoadModel);
    if (model.objects.empty())
        throw std::runtime_error(format(_u8L("No objects found in %1%."), fs::path(path).filename().string()));
    for (ModelObject *other : model.objects)
        if (o.origin_translation != Vec3d::Zero()) {
            other->center_around_origin();
            const Vec3d delta = o.origin_translation - other->origin_translation;
            for (ModelVolume *volume : other->volumes)
                volume->translate(delta);
        }
    model.add_default_instances();
    ModelVolume *volume = o.add_volume(model.mesh(), ModelVolumeType(int(type)));
    volume->name = fs::path(path).filename().string();
    volume->source.input_file = path;
    if (model.objects.size() == 1 && model.objects.front()->volumes.size() == 1)
        volume->source.mesh_offset = model.objects.front()->volumes.front()->source.mesh_offset;
    volume->set_offset(volume->source.mesh_offset - o.volumes.front()->source.mesh_offset);
    return part_added(o, *volume);
}

size_t Engine::set_part_type(size_t object, size_t part, Part::Type type)
{
    if (int(type) < int(Part::Type::Model) || int(type) > int(Part::Type::SupportEnforcer))
        throw std::invalid_argument("invalid part type");
    ModelObject &o = m->object(object);
    ModelVolume *volume = &volume_at(o, part);
    if (volume->is_model_part() && type != Part::Type::Model && model_parts(o) == 1)
        throw std::invalid_argument(_u8L("The type of the last solid object part is not to be changed."));
    volume->set_type(ModelVolumeType(int(type)));
    o.sort_volumes(true);
    o.invalidate_bounding_box();
    o.ensure_on_bed();
    return size_t(std::distance(o.volumes.begin(), std::find(o.volumes.begin(), o.volumes.end(), volume)));
}

namespace {

void part_moved(ModelObject &object, const ModelVolume &volume)
{
    object.invalidate_bounding_box();
    if (volume.is_model_part())
        object.ensure_on_bed();
}

} // namespace

void Engine::set_part_transform(size_t object, size_t part, const std::array<double, 16> &transform)
{
    ModelObject &o = m->object(object);
    ModelVolume &volume = volume_at(o, part);
    Transform3d matrix;
    std::copy(transform.begin(), transform.end(), matrix.data());
    volume.set_transformation(Geometry::Transformation(matrix));
    part_moved(o, volume);
}

void Engine::set_part_placement(size_t object, size_t part, const Vec3 &offset, const Vec3 &rotation, const Vec3 &scale)
{
    ModelObject &o = m->object(object);
    ModelVolume &volume = volume_at(o, part);
    volume.set_transformation(compose(offset, rotation, scale));
    part_moved(o, volume);
}

void Engine::remove_part(size_t object, size_t part)
{
    ModelObject &o = m->object(object);
    const ModelVolume &volume = volume_at(o, part);
    // Como ObjectList::del_subobject_from_object().
    if (volume.is_model_part() && model_parts(o) == 1)
        throw std::invalid_argument(_u8L("Deleting the last solid part is not allowed."));
    const bool model_part = volume.is_model_part();
    // Com uma peça só, a matriz dela passa para a instância (ModelObject::delete_volume()).
    o.delete_volume(part);
    if (model_part)
        o.ensure_on_bed();
}

// A pintura do tipo na peça.
static FacetsAnnotation &paint_facets(ModelVolume &volume, PaintKind kind)
{
    switch (kind) {
    case PaintKind::Seam: return volume.seam_facets;
    case PaintKind::FuzzySkin: return volume.fuzzy_skin_facets;
    case PaintKind::Color: return volume.mmu_segmentation_facets;
    default: return volume.supported_facets;
    }
}

static const FacetsAnnotation &paint_facets(const ModelVolume &volume, PaintKind kind)
{
    return paint_facets(const_cast<ModelVolume &>(volume), kind);
}

std::vector<Engine::Impl::Selector::Patch> Engine::Impl::Selector::patches(double max_area) const
{
    auto [neighbors, neighbors_propagated] = precompute_all_neighbors();
    std::vector<bool> visited(m_triangles.size(), false);
    auto touching = [&](int facet) {
        const Vec3i32   &n = neighbors[facet];
        const auto      &v = m_triangles[facet].verts_idxs;
        std::vector<int> out;
        append_touching_subtriangles(n(0), v[1], v[0], out);
        append_touching_subtriangles(n(1), v[2], v[1], out);
        append_touching_subtriangles(n(2), v[0], v[2], out);
        for (int neighbor : neighbors_propagated[facet])
            if (neighbor != -1 && !m_triangles[neighbor].is_split())
                out.push_back(neighbor);
        return out;
    };
    std::vector<Patch> out;
    for (size_t start = 0; start < m_triangles.size(); ++start) {
        if (visited[start] || !m_triangles[start].valid() || m_triangles[start].is_split())
            continue;
        Patch patch;
        patch.state = m_triangles[start].get_state();
        std::queue<int> queue;
        queue.push(int(start));
        while (!queue.empty()) {
            const int facet = queue.front();
            queue.pop();
            if (!visited[facet]) {
                patch.facets.push_back(facet);
                for (int other : touching(facet)) {
                    if (other < 0)
                        continue;
                    if (m_triangles[other].get_state() != patch.state) {
                        patch.neighbor_states.insert(m_triangles[other].get_state());
                        continue;
                    }
                    if (!visited[other])
                        queue.push(other);
                }
            }
            visited[facet] = true;
        }
        // calc_fragment_area(): para de somar ao passar do limite.
        for (int facet : patch.facets) {
            const auto  &v  = m_triangles[facet].verts_idxs;
            const Vec3f &a  = m_vertices[v[0]].v;
            patch.area += 0.5 * double((a - m_vertices[v[1]].v).cross(a - m_vertices[v[2]].v).norm());
            if (patch.area >= max_area)
                break;
        }
        out.push_back(std::move(patch));
    }
    return out;
}

Engine::Impl::PaintVolume &Engine::Impl::paint_volume(const ModelObject &object, PaintKind kind, const ModelVolume &volume)
{
    if (object.id() != paint_object || kind != paint_kind) {
        paint_volumes.clear();
        paint_object = object.id();
        paint_kind   = kind;
    }
    const FacetsAnnotation &facets = paint_facets(volume, kind);
    auto it = std::find_if(paint_volumes.begin(), paint_volumes.end(), [&](const PaintVolume &v) { return v.volume == volume.id(); });
    if (it != paint_volumes.end() && it->timestamp == facets.timestamp() && it->mesh == volume.mesh_ptr())
        return *it;
    if (it == paint_volumes.end())
        it = paint_volumes.emplace(paint_volumes.end());
    // Como GLGizmoFdmSupports::update_from_model_object().
    it->volume    = volume.id();
    it->timestamp = facets.timestamp();
    it->mesh      = volume.mesh_ptr();
    it->selector  = std::make_unique<Selector>(*it->mesh);
    it->selector->deserialize(facets.get_data(), false);
    it->raycaster = std::make_unique<AABBMesh>(*it->mesh);
    return *it;
}

// O plano na malha de uma peça (GLGizmoPainterBase::get_clipping_plane_in_volume_coordinates()).
static TriangleSelector::ClippingPlane clipping_plane(const std::optional<ClipPlane> &clip, const Transform3d &trafo)
{
    if (!clip)
        return {};
    const Vec3d normal(clip->normal[0], clip->normal[1], clip->normal[2]);
    const Vec3d local = trafo.linear().transpose() * normal;
    const Vec3d point = trafo.inverse() * (normal * clip->offset);
    return TriangleSelector::ClippingPlane({float(local.x()), float(local.y()), float(local.z()), float(point.dot(local))});
}

bool Engine::paint(size_t object, PaintKind kind, const Vec3 &origin, const Vec3 &direction, const Brush &brush, bool stroke_start,
                   const std::optional<ClipPlane> &clip)
{
    if ((kind == PaintKind::FuzzySkin || kind == PaintKind::Color) && brush.state == PaintState::Block)
        throw std::invalid_argument("this paint has no block state");
    const bool color_tool = brush.tool == Brush::Tool::Triangle || brush.tool == Brush::Tool::BucketFill || brush.tool == Brush::Tool::HeightRange;
    if (color_tool != (kind == PaintKind::Color) && brush.tool != Brush::Tool::Circle && brush.tool != Brush::Tool::Sphere)
        throw std::invalid_argument("tool not available for this paint");
    // GLGizmoMmuSegmentation::get_left_button_state_type(): o filamento, até Extruder16.
    EnforcerBlockerType state = EnforcerBlockerType(brush.state);
    if (kind == PaintKind::Color && brush.state == PaintState::Enforce) {
        if (!m->bundle)
            throw std::runtime_error("no vendor loaded");
        if (brush.color < 1 || size_t(brush.color) > m->filament_count() || brush.color > int(EnforcerBlockerType::ExtruderMax))
            throw std::invalid_argument("invalid filament: " + std::to_string(brush.color));
        state = EnforcerBlockerType(brush.color);
    }
    ModelObject &o = m->object(object);
    const Vec3d  world_origin(origin[0], origin[1], origin[2]);
    const Vec3d  world_direction(direction[0], direction[1], direction[2]);
    if (stroke_start)
        m->paint_last.reset();

    // A peça do objeto mais próxima no raio, como o MeshRaycaster do gizmo.
    const Transform3d instance = o.instances.front()->get_matrix();
    ModelVolume      *hit_volume = nullptr;
    Impl::PaintHit    hit;
    Transform3d       hit_trafo;
    double            best = std::numeric_limits<double>::max();
    for (ModelVolume *volume : o.volumes) {
        if (!volume->is_model_part())
            continue;
        Impl::PaintVolume &pv     = m->paint_volume(o, kind, *volume);
        const Transform3d  trafo  = instance * volume->get_matrix();
        const Transform3d  inv    = trafo.inverse();
        const Vec3d        source = inv * world_origin;
        const Vec3d        dir    = (inv.linear() * world_direction).normalized();
        if (!dir.allFinite())
            continue;
        // O primeiro ponto fora do lado escondido, como o MeshRaycaster com o plano de corte.
        const TriangleSelector::ClippingPlane clp = clipping_plane(clip, trafo);
        std::optional<AABBMesh::hit_result>   found;
        for (const AABBMesh::hit_result &h : pv.raycaster->query_ray_hits(source, dir))
            if (h.is_hit() && !(clp.is_active() && clp.is_mesh_point_clipped(h.position().cast<float>()))) {
                found = h;
                break;
            }
        if (!found)
            continue;
        const AABBMesh::hit_result &result = *found;
        const double distance = (trafo * result.position() - world_origin).norm();
        if (distance < best) {
            best       = distance;
            hit_volume = volume;
            hit        = {size_t(&pv - m->paint_volumes.data()), result.face(), result.position().cast<float>()};
            hit_trafo  = trafo;
        }
    }
    if (hit_volume == nullptr) {
        // Saiu do objeto: o próximo ponto que o atingir começa outro traço.
        m->paint_last.reset();
        return false;
    }

    // Como GLGizmoPainterBase::gizmo_event() com o botão apertado.
    Impl::PaintVolume &pv = m->paint_volumes[hit.volume];
    TriangleSelector  &selector = *pv.selector;
    Transform3d        trafo_no_translate = hit_trafo;
    trafo_no_translate.translation() = Vec3d::Zero();
    const Vec3f camera = (hit_trafo.inverse() * world_origin).cast<float>();
    const TriangleSelector::ClippingPlane no_clipping = clipping_plane(clip, hit_trafo);
    const float overhang = float(std::max(brush.overhang_angle, 0.0));
    if (brush.tool == Brush::Tool::SmartFill) {
        selector.seed_fill_select_triangles(hit.point, hit.facet, trafo_no_translate, no_clipping, float(brush.smart_fill_angle), overhang, true);
        selector.seed_fill_apply_on_triangles(state);
    } else if (brush.tool == Brush::Tool::Triangle || brush.tool == Brush::Tool::BucketFill) {
        const bool bucket = brush.tool == Brush::Tool::BucketFill;
        selector.bucket_fill_select_triangles(hit.point, hit.facet, no_clipping, bucket ? float(brush.smart_fill_angle) : -1.f, bucket, true);
        selector.seed_fill_apply_on_triangles(state);
    } else if (brush.tool == Brush::Tool::HeightRange) {
        // GLGizmoPainterBase::get_projected_height_range(): a faixa começa na altura tocada (na
        // mesa) e vale para todas as peças, cada uma a partir de um triângulo dela dentro da faixa.
        const float  height = float(std::clamp(brush.height, 0.1, 8.0));
        const double z_bottom = (hit_trafo * hit.point.cast<double>()).z();
        for (ModelVolume *volume : o.volumes) {
            if (!volume->is_model_part())
                continue;
            Impl::PaintVolume &other = m->paint_volume(o, kind, *volume);
            const Transform3d  trafo = instance * volume->get_matrix();
            int                first = volume == hit_volume ? hit.facet : -1;
            const indexed_triangle_set &its = other.mesh->its;
            for (size_t i = 0; first < 0 && i < its.indices.size(); ++i) {
                double low = std::numeric_limits<double>::max(), high = std::numeric_limits<double>::lowest();
                for (int j = 0; j < 3; ++j) {
                    const double z = (trafo * its.vertices[its.indices[i][j]].cast<double>()).z();
                    low  = std::min(low, z);
                    high = std::max(high, z);
                }
                if (high >= z_bottom && low <= z_bottom + height)
                    first = int(i);
            }
            if (first < 0)
                continue;
            Transform3d linear = trafo;
            linear.translation() = Vec3d::Zero();
            const Vec3f camera_pos = (trafo.inverse() * world_origin).cast<float>();
            other.selector->select_patch(first,
                                         TriangleSelector::SinglePointCursor::cursor_factory(float(z_bottom), camera_pos, height, trafo, clipping_plane(clip, trafo)),
                                         state, linear, true, 0.f);
            if (volume != hit_volume) {
                FacetsAnnotation &facets = paint_facets(*volume, kind);
                facets.set(*other.selector);
                other.timestamp = facets.timestamp();
            }
        }
    } else {
        const auto  type   = brush.tool == Brush::Tool::Sphere ? TriangleSelector::SPHERE : TriangleSelector::CIRCLE;
        const float radius = float(std::clamp(brush.radius, 0.4, 8.0));
        const std::optional<Impl::PaintHit> &last = m->paint_last;
        if (last && last->volume == hit.volume)
            selector.select_patch(last->facet,
                                  TriangleSelector::DoublePointCursor::cursor_factory(last->point, hit.point, camera, radius, type, hit_trafo, no_clipping),
                                  state, trafo_no_translate, true, overhang);
        else
            selector.select_patch(hit.facet, TriangleSelector::SinglePointCursor::cursor_factory(hit.point, camera, radius, type, hit_trafo, no_clipping),
                                  state, trafo_no_translate, true, overhang);
    }
    m->paint_last = hit;

    // GLGizmoFdmSupports::update_model_object(), aqui a cada toque: a pintura na peça é sempre a
    // atual (para salvar e fatiar).
    FacetsAnnotation &facets = paint_facets(*hit_volume, kind);
    facets.set(selector);
    pv.timestamp = facets.timestamp();
    return true;
}

PaintMesh Engine::painted(size_t object, PaintKind kind) const
{
    const ModelObject &o = m->object(object);
    PaintMesh          out;
    for (const ModelVolume *volume : o.volumes) {
        if (!volume->is_model_part())
            continue;
        const FacetsAnnotation &facets = paint_facets(*volume, kind);
        const bool              color  = kind == PaintKind::Color;
        if (facets.empty() && !color)
            continue;
        // Índice = EnforcerBlockerType.
        std::vector<indexed_triangle_set> per_state;
        if (facets.empty())
            per_state.push_back(volume->mesh().its);
        else
            facets.get_facets(*volume, per_state);
        const Transform3d matrix = volume->get_matrix();
        for (size_t state = color ? 0 : 1; state < per_state.size() && (color || state <= size_t(PaintState::Block)); ++state) {
            const uint32_t first = uint32_t(out.positions.size() / 3);
            for (const stl_vertex &v : per_state[state].vertices) {
                const Vec3f p = (matrix * v.cast<double>()).cast<float>();
                out.positions.insert(out.positions.end(), {p.x(), p.y(), p.z()});
            }
            for (const stl_triangle_vertex_indices &t : per_state[state].indices)
                out.indices.insert(out.indices.end(), {first + uint32_t(t[0]), first + uint32_t(t[1]), first + uint32_t(t[2])});
            out.states.insert(out.states.end(), per_state[state].indices.size(), uint8_t(state == 0 ? std::max(volume->extruder_id(), 1) : int(state)));
        }
    }
    return out;
}

PaintMesh Engine::gaps(size_t object, PaintKind kind, double area)
{
    ModelObject &o = m->object(object);
    PaintMesh    out;
    for (ModelVolume *volume : o.volumes) {
        if (!volume->is_model_part())
            continue;
        const Impl::Selector &selector = *m->paint_volume(o, kind, *volume).selector;
        const auto            all      = selector.patches(area);
        const Transform3d     matrix   = volume->get_matrix();
        for (const auto &[patch, state] : selector.fragments(all, area)) {
            indexed_triangle_set its;
            selector.append_facets(patch->facets, its);
            const uint32_t first = uint32_t(out.positions.size() / 3);
            for (const stl_vertex &v : its.vertices) {
                const Vec3f p = (matrix * v.cast<double>()).cast<float>();
                out.positions.insert(out.positions.end(), {p.x(), p.y(), p.z()});
            }
            for (const stl_triangle_vertex_indices &t : its.indices)
                out.indices.insert(out.indices.end(), {first + uint32_t(t[0]), first + uint32_t(t[1]), first + uint32_t(t[2])});
            const int shown = kind == PaintKind::Color && state == EnforcerBlockerType::NONE ? std::max(volume->extruder_id(), 1) : int(state);
            out.states.insert(out.states.end(), its.indices.size(), uint8_t(shown));
        }
    }
    return out;
}

size_t Engine::fill_gaps(size_t object, PaintKind kind, double area)
{
    ModelObject &o     = m->object(object);
    size_t       count = 0;
    for (ModelVolume *volume : o.volumes) {
        if (!volume->is_model_part())
            continue;
        Impl::PaintVolume &pv      = m->paint_volume(o, kind, *volume);
        const size_t       changed = pv.selector->fill_gaps(area);
        if (changed == 0)
            continue;
        count += changed;
        FacetsAnnotation &facets = paint_facets(*volume, kind);
        facets.set(*pv.selector);
        pv.timestamp = facets.timestamp();
    }
    return count;
}

// --- Calibração (Plater::calib_* do Orca) ---

namespace {

// Primeiro valor de uma opção de vetor de números (com ou sem nil).
double first_float(const DynamicPrintConfig &config, const char *key)
{
    if (const auto *opt = config.option<ConfigOptionFloats>(key))
        return opt->get_at(0);
    if (const auto *opt = config.option<ConfigOptionFloatsNullable>(key))
        return opt->get_at(0);
    throw std::runtime_error(std::string("missing option: ") + key);
}

// has_junction_deviation() de Plater.cpp: Marlin 2 com machine_max_junction_deviation > 0.
bool has_junction_deviation(const DynamicPrintConfig &printer)
{
    const auto *flavor = printer.option<ConfigOptionEnum<GCodeFlavor>>("gcode_flavor");
    const auto *jd     = printer.option<ConfigOptionFloats>("machine_max_junction_deviation");
    return flavor != nullptr && flavor->value == gcfMarlinFirmware && jd != nullptr && !jd->values.empty() && jd->values.front() > 0.0;
}

// Plater::cut_horizontal() + apply_cut_object_to_model(): troca o objeto pelas partes mantidas,
// cada uma apoiada na mesa.
void cut_horizontal(Model &model, size_t index, double z, ModelObjectCutAttribute keep)
{
    ModelObject      *object = model.objects[index];
    const Vec3d       offset = object->instances.front()->get_offset();
    Cut               cut(object, 0, Geometry::translation_transform(z * Vec3d::UnitZ() - offset), keep);
    const ModelObjectPtrs &parts = cut.perform_with_plane();
    std::vector<ModelObject *> added;
    for (const ModelObject *part : parts)
        added.push_back(model.add_object(*part));
    model.delete_object(index);
    for (ModelObject *part : added)
        part->ensure_on_bed();
}

// Padrões de enchimento do topo do teste de fluxo (FlowRateCalibrationDialog).
void adjust_settings_for_flowrate_calib(PresetBundle &bundle, Model &model, const Vec2d &bed_center, bool linear, int pass, InfillPattern pattern)
{
    DynamicPrintConfig &print    = bundle.prints.get_edited_preset().config;
    DynamicPrintConfig &printer  = bundle.printers.get_edited_preset().config;
    DynamicPrintConfig &filament = bundle.filaments.get_edited_preset().config;

    // O modelo é para bico de 0,6 mm; z com 10 camadas (2 de baixo, 3 de enchimento, 5 de cima).
    const float  nozzle_diameter    = float(first_float(printer, "nozzle_diameter"));
    const float  xy_scale           = nozzle_diameter / 0.6f;
    const double layer_height       = nozzle_diameter / 2.0;
    const double first_layer_height = std::max(print.opt_float("initial_layer_print_height"), layer_height);
    const double z_scale            = (first_layer_height + 9 * layer_height) / 2;
    // Como Selection::scale() com todos os objetos: posições em volta do centro também.
    const double xy = xy_scale > 1.2f ? xy_scale : 1.0;
    for (ModelObject *object : model.objects) {
        ModelInstance *instance = object->instances.front();
        instance->set_scaling_factor(instance->get_scaling_factor().cwiseProduct(Vec3d(xy, xy, z_scale)));
        Vec3d o = instance->get_offset();
        o.head<2>() = bed_center + (o.head<2>() - bed_center) * xy;
        instance->set_offset(o);
    }

    const double cur_flowrate = first_float(filament, "filament_flow_ratio");
    const Flow   infill_flow(nozzle_diameter * 1.2f, float(layer_height), nozzle_diameter);
    const double max_volumetric = first_float(filament, "filament_max_volumetric_speed");
    const double max_infill_speed = linear
        ? max_volumetric / (infill_flow.mm3_per_mm() * (cur_flowrate + (pass == 2 ? 0.035 : 0.05)) / cur_flowrate)
        : max_volumetric / (infill_flow.mm3_per_mm() * (pass == 1 ? 1.2 : 1));
    const double internal_solid_speed = std::floor(std::min(print.opt_float("internal_solid_infill_speed"), max_infill_speed));
    const double top_surface_speed    = std::floor(std::min(print.opt_float("top_surface_speed"), max_infill_speed));

    for (ModelObject *object : model.objects) {
        object->ensure_on_bed();
        ModelConfigObject &c = object->config;
        c.set_key_value("wall_loops", new ConfigOptionInt(1));
        c.set_key_value("only_one_wall_top", new ConfigOptionBool(true));
        c.set_key_value("thick_internal_bridges", new ConfigOptionBool(false));
        c.set_key_value("enable_extra_bridge_layer", new ConfigOptionEnum<EnableExtraBridgeLayer>(eblDisabled));
        c.set_key_value("internal_bridge_density", new ConfigOptionPercent(100));
        c.set_key_value("sparse_infill_density", new ConfigOptionPercent(35));
        c.set_key_value("min_width_top_surface", new ConfigOptionFloatOrPercent(100, true));
        c.set_key_value("bottom_shell_layers", new ConfigOptionInt(2));
        c.set_key_value("top_shell_layers", new ConfigOptionInt(5));
        c.set_key_value("top_shell_thickness", new ConfigOptionFloat(0));
        c.set_key_value("bottom_shell_thickness", new ConfigOptionFloat(0));
        c.set_key_value("detect_thin_wall", new ConfigOptionBool(true));
        c.set_key_value("filter_out_gap_fill", new ConfigOptionFloat(0));
        c.set_key_value("sparse_infill_pattern", new ConfigOptionEnum<InfillPattern>(ipRectilinear));
        c.set_key_value("top_surface_line_width", new ConfigOptionFloatOrPercent(nozzle_diameter * 1.2f, false));
        c.set_key_value("internal_solid_infill_line_width", new ConfigOptionFloatOrPercent(nozzle_diameter * 1.2f, false));
        c.set_key_value("top_surface_pattern", new ConfigOptionEnum<InfillPattern>(pattern));
        c.set_key_value("top_solid_infill_flow_ratio", new ConfigOptionFloat(1.0f));
        c.set_key_value("infill_direction", new ConfigOptionFloat(45));
        c.set_key_value("solid_infill_direction", new ConfigOptionFloat(135));
        c.set_key_value("align_infill_direction_to_model", new ConfigOptionBool(true));
        c.set_key_value("ironing_type", new ConfigOptionEnum<IroningType>(IroningType::NoIroning));
        c.set_key_value("internal_solid_infill_speed", new ConfigOptionFloat(internal_solid_speed));
        c.set_key_value("top_surface_speed", new ConfigOptionFloat(top_surface_speed));
        c.set_key_value("seam_slope_type", new ConfigOptionEnum<SeamScarfType>(SeamScarfType::None));
        c.set_key_value("gap_fill_target", new ConfigOptionEnum<GapFillTarget>(GapFillTarget::gftNowhere));
        c.set_key_value("calib_flowrate_topinfill_special_order", new ConfigOptionBool(true));

        // O fluxo de cada peça vem do nome: flowrate_<n>, com "m" no lugar do sinal de menos.
        std::string name = object->name.size() > 9 ? object->name.substr(9) : std::string();
        if (!name.empty() && name[0] == 'm')
            name[0] = '-';
        double modifier = 1.0;
        try {
            modifier = string_to_double_decimal_point(name);
        } catch (...) {
        }
        c.set_key_value("print_flow_ratio", new ConfigOptionFloat(linear ? (cur_flowrate + modifier) / cur_flowrate : 1.0 + modifier / 100.0));
    }

    print.set_key_value("max_volumetric_extrusion_rate_slope", new ConfigOptionFloat(0));
    print.set_key_value("layer_height", new ConfigOptionFloat(layer_height));
    print.set_key_value("alternate_extra_wall", new ConfigOptionBool(false));
    print.set_key_value("initial_layer_print_height", new ConfigOptionFloat(first_layer_height));
    print.set_key_value("reduce_crossing_wall", new ConfigOptionBool(true));
    print.set_key_value("enable_wrapping_detection", new ConfigOptionBool(false));
}

// O que os testes de input shaping e cornering fazem igual.
void prepare_motion_test(DynamicPrintConfig &print, DynamicPrintConfig &filament, DynamicPrintConfig &printer, ModelObject &object)
{
    if (!filament.option<ConfigOptionBools>("enable_pressure_advance")->get_at(0)) {
        filament.set_key_value("enable_pressure_advance", new ConfigOptionBools{true});
        filament.set_key_value("pressure_advance", new ConfigOptionFloats{0.0});
        filament.set_key_value("adaptive_pressure_advance", new ConfigOptionBools{false});
    }
    printer.set_key_value("resonance_avoidance", new ConfigOptionBool{false});
    filament.set_key_value("slow_down_layer_time", new ConfigOptionFloats{0.0});
    filament.set_key_value("slow_down_min_speed", new ConfigOptionFloats{0.0});
    filament.set_key_value("slow_down_for_layer_cooling", new ConfigOptionBools{false});
    print.set_key_value("enable_overhang_speed", new ConfigOptionBool{false});
    print.set_key_value("timelapse_type", new ConfigOptionEnum<TimelapseType>(tlTraditional));
    print.set_key_value("wall_loops", new ConfigOptionInt(1));
    print.set_key_value("top_shell_layers", new ConfigOptionInt(0));
    print.set_key_value("bottom_shell_layers", new ConfigOptionInt(1));
    print.set_key_value("sparse_infill_density", new ConfigOptionPercent(0));
    print.set_key_value("detect_thin_wall", new ConfigOptionBool(false));
    print.set_key_value("spiral_mode", new ConfigOptionBool(true));
    print.set_key_value("spiral_mode_smooth", new ConfigOptionBool(false));
    print.set_key_value("bottom_surface_pattern", new ConfigOptionEnum<InfillPattern>(ipRectilinear));
    print.set_key_value("outer_wall_speed", new ConfigOptionFloat(200));
    print.set_key_value("precise_z_height", new ConfigOptionBool(false));
    object.config.set_key_value("brim_type", new ConfigOptionEnum<BrimType>(btOuterOnly));
    object.config.set_key_value("brim_width", new ConfigOptionFloat(3.0));
    object.config.set_key_value("brim_object_gap", new ConfigOptionFloat(0.0));
}

// Calib_Input_shaping_*: jerk ou junction deviation mínimos para o teste.
void prepare_input_shaping(DynamicPrintConfig &print, DynamicPrintConfig &printer)
{
    if (has_junction_deviation(printer)) {
        printer.set_key_value("machine_max_junction_deviation",
                              new ConfigOptionFloats{std::max(printer.option<ConfigOptionFloats>("machine_max_junction_deviation")->values.front(), 0.25)});
        print.set_key_value("default_junction_deviation", new ConfigOptionFloat(0));
    } else {
        const auto  *flavor = printer.option<ConfigOptionEnum<GCodeFlavor>>("gcode_flavor");
        const double jerk   = flavor != nullptr && flavor->value == gcfKlipper ? 5.0 : 10.0;
        for (const char *key : {"machine_max_jerk_x", "machine_max_jerk_y"})
            printer.set_key_value(key, new ConfigOptionFloats{std::max(printer.option<ConfigOptionFloats>(key)->values.front(), jerk)});
        print.set_key_value("default_jerk", new ConfigOptionFloat(0));
    }
    printer.set_key_value("input_shaping_emit", new ConfigOptionBool{false});
    print.set_key_value("default_acceleration", new ConfigOptionFloat(20000));
    print.set_key_value("outer_wall_acceleration", new ConfigOptionFloat(20000));
}

} // namespace

// Plater::_calib_pa_pattern(): ajustes do teste e uma alça (cubo) por combinação de velocidade e
// aceleração, arranjadas como os padrões que vão ao lado delas, em placas novas se precisar. O
// G-code dos padrões é gerado em slice() (_calib_pa_pattern_gen_gcode()).
void Engine::Impl::calib_pa_pattern(bool junction_deviation)
{
    std::vector<double> speeds = calib.speeds;
    std::vector<double> accels = calib.accelerations;
    DynamicPrintConfig &print    = bundle->prints.get_edited_preset().config;
    DynamicPrintConfig &printer  = bundle->printers.get_edited_preset().config;
    DynamicPrintConfig &filament = bundle->filaments.get_edited_preset().config;
    const double        nozzle_diameter = first_float(printer, "nozzle_diameter");
    filament.set_key_value("filament_retract_when_changing_layer", new ConfigOptionBoolsNullable{false});
    filament.set_key_value("filament_wipe", new ConfigOptionBoolsNullable{false});
    printer.set_key_value("wipe", new ConfigOptionBools{false});
    printer.set_key_value("retract_when_changing_layer", new ConfigOptionBools{false});
    printer.set_key_value("resonance_avoidance", new ConfigOptionBool{false});

    // A aceleração da parede externa, ou a interna, ou a padrão; com várias, a maior, que dá o
    // tamanho do padrão.
    double accel = print.option<ConfigOptionFloat>("outer_wall_acceleration")->value;
    if (accel == 0)
        accel = print.option<ConfigOptionFloat>("inner_wall_acceleration")->value;
    if (accel == 0)
        accel = print.option<ConfigOptionFloat>("default_acceleration")->value;
    if (accels.empty())
        accels.assign({accel});
    else
        accel = *std::max_element(accels.begin(), accels.end());
    print.set_key_value("outer_wall_acceleration", new ConfigOptionFloat(accel));
    print.set_key_value("print_sequence", new ConfigOptionEnum(PrintSequence::ByLayer));

    if (!junction_deviation && print.option<ConfigOptionFloat>("default_jerk")->value > 0) {
        double jerk = print.option<ConfigOptionFloat>("outer_wall_jerk")->value;
        if (jerk == 0)
            jerk = print.option<ConfigOptionFloat>("inner_wall_jerk")->value;
        if (jerk == 0)
            jerk = print.option<ConfigOptionFloat>("default_jerk")->value;
        for (const char *key : {"default_jerk", "outer_wall_jerk", "inner_wall_jerk", "top_surface_jerk", "infill_jerk", "travel_jerk"})
            print.set_key_value(key, new ConfigOptionFloat(jerk));
    }
    if (junction_deviation)
        print.set_key_value("default_junction_deviation", new ConfigOptionFloat(0));

    const SuggestedConfigCalibPAPattern suggested;
    for (const auto &[key, value] : suggested.float_pairs)
        print.set_key_value(key, new ConfigOptionFloat(value));
    for (const auto &[key, ratio] : suggested.nozzle_ratio_pairs)
        print.set_key_value(key, new ConfigOptionFloatOrPercent(nozzle_diameter * ratio / 100, false));
    for (const auto &[key, value] : suggested.int_pairs)
        print.set_key_value(key, new ConfigOptionInt(value));
    print.set_key_value(suggested.brim_pair.first, new ConfigOptionEnum<BrimType>(suggested.brim_pair.second));
    print.set_key_value("enable_wrapping_detection", new ConfigOptionBool(false));

    // Sem velocidades, a ótima para o teste (limitada pela velocidade volumétrica máxima).
    if (speeds.empty()) {
        const double speed = CalibPressureAdvance::find_optimal_PA_speed(bundle->full_config(), print.get_abs_value("line_width", nozzle_diameter),
                                                                         print.get_abs_value("layer_height"), 0, 0);
        print.set_key_value("outer_wall_speed", new ConfigOptionFloat(speed));
        speeds.assign({speed});
    } else if (speeds.size() == 1) {
        print.set_key_value("outer_wall_speed", new ConfigOptionFloat(speeds.front()));
    }

    const DynamicPrintConfig full = bundle->full_config();
    ModelObject             *cube = model.objects.front();
    CalibPressureAdvancePattern pattern(calib, full, bundle->is_bbl_vendor(), *cube, Vec3d::Zero());

    // O cubo do tamanho de um padrão (com folga) serve de referência para o arranjo.
    BoundingBoxf3 cube_bb = cube->raw_bounding_box();
    cube->scale((pattern.print_size_x() + 4) / cube_bb.size().x(), (pattern.print_size_y() + 4) / cube_bb.size().y(),
                pattern.max_layer_z() / cube_bb.size().z());
    arrangement::ArrangePolygons items;
    {
        arrangement::ArrangeParams ap;
        ap.progressind = {}; // o padrão escreve no stdout
        const Points bed = arrangement::get_shrink_bedpts(&full, ap);
        for (size_t i = 0; i < speeds.size() * accels.size(); ++i) {
            arrangement::ArrangePolygon p;
            cube->instances.front()->get_arrange_polygon(&p);
            p.bed_idx = 0;
            items.push_back(std::move(p));
        }
        arrangement::arrange(items, bed, ap);
    }
    cube_bb = cube->raw_bounding_box();
    cube->scale(pattern.handle_xy_size() / cube_bb.size().x(), pattern.handle_xy_size() / cube_bb.size().y(),
                pattern.max_layer_z() / cube_bb.size().z());

    for (size_t i = 0; i < items.size(); ++i) {
        if (items[i].bed_idx < 0)
            throw std::runtime_error("the pressure advance pattern does not fit on the bed");
        const size_t plate_index = size_t(items[i].bed_idx);
        const double speed       = speeds[i % speeds.size()];
        const double acceleration = accels[i / speeds.size()];
        while (plates.size() <= plate_index)
            plates.emplace_back();
        ModelObject *object = i == 0 ? cube : plate_model(plate_index).add_object(*cube);
        object->name = "pa_pattern_" + std::to_string(int(speed)) + "_" + std::to_string(int(acceleration));
        if (speeds.size() > 1)
            object->config.set_key_value("outer_wall_speed", new ConfigOptionFloat(speed));
        if (accels.size() > 1)
            object->config.set_key_value("outer_wall_acceleration", new ConfigOptionFloat(acceleration));
        const Vec3d offset(unscale<double>(items[i].translation.x()), unscale<double>(items[i].translation.y()), 0);
        object->instances.front()->set_offset(offset + pattern.handle_pos_offset());
        object->ensure_on_bed();
    }
    pa_pattern = std::make_unique<CalibPressureAdvancePattern>(pattern);
}

void Engine::calibrate(const Calibration &c)
{
    if (!m->bundle)
        throw std::runtime_error("no vendor loaded");
    using Test = Calibration::Test;
    const fs::path calib = fs::path(Slic3r::resources_dir()) / "calib";
    auto finite = [&](std::initializer_list<double> values) {
        for (double v : values)
            if (!std::isfinite(v))
                throw std::invalid_argument("invalid calibration value");
    };
    finite({c.start, c.end, c.step, c.freq_start_x, c.freq_end_x, c.freq_start_y, c.freq_end_y});
    // As validações dos diálogos de calib_dlg.cpp.
    auto require = [](bool ok, const std::string &message) {
        if (!ok)
            throw std::invalid_argument(message);
    };
    const bool junction_deviation = has_junction_deviation(m->printer());
    switch (c.test) {
    case Test::Temperature:
        require(c.start <= 500 && c.end >= 155 && c.end <= c.start - 5,
                _u8L("Please input valid values:\nStart temp: <= 500\nEnd temp: >= 155\nStart temp >= End temp + 5"));
        break;
    case Test::MaxVolumetricSpeed:
        require(c.start > 0 && c.step > 0 && c.end >= c.start + c.step, _u8L("Please input valid values:\nstart > 0\nstep >= 0\nend > start + step"));
        break;
    case Test::Vfa:
        require(c.start > 10 && c.step > 0 && c.end >= c.start + c.step, _u8L("Please input valid values:\nstart > 10\nstep >= 0\nend > start + step"));
        break;
    case Test::Retraction:
        require(c.start >= 0 && c.step > 0 && c.end >= c.start + c.step, _u8L("Please input valid values:\nstart > 0\nstep >= 0\nend > start + step"));
        break;
    case Test::PressureAdvanceTower:
    case Test::PressureAdvanceLine:
    case Test::PressureAdvancePattern:
        require(c.start >= 0 && c.step >= 10 * EPSILON && c.end >= c.start + c.step,
                _u8L("Please input valid values:\nStart PA: >= 0.0\nEnd PA: > Start PA\nPA step: >= 0.001"));
        if (c.test == Test::PressureAdvancePattern) {
            for (const std::vector<double> *values : {&c.speeds, &c.accelerations})
                for (double v : *values)
                    require(std::isfinite(v) && v > 0, "invalid speed or acceleration");
            // A guarda de PA_Calibration_Dlg::on_start() contra as listas trocadas.
            if (!c.speeds.empty() && !c.accelerations.empty())
                require(*std::min_element(c.accelerations.begin(), c.accelerations.end()) > *std::max_element(c.speeds.begin(), c.speeds.end()),
                        _u8L("Acceleration values must be greater than speed values.\nPlease verify the inputs."));
        }
        break;
    case Test::FlowRate:
        require(c.flow_pass == 1 || c.flow_pass == 2, "invalid flow rate pass");
        break;
    case Test::InputShapingFrequency:
        require(c.freq_start_x >= 0 && c.freq_end_x <= 500 && c.freq_start_x < c.freq_end_x && c.freq_start_y >= 0 && c.freq_end_y <= 500 &&
                    c.freq_start_y < c.freq_end_y,
                _u8L("Please input valid values:\n(0 < FreqStart < FreqEnd < 500)"));
        require(c.start >= 0 && c.start < 1, _u8L("Please input a valid damping factor (0 < Damping/zeta factor <= 1)"));
        break;
    case Test::InputShapingDamping:
        require(c.freq_start_x >= 0 && c.freq_start_x <= 500 && c.freq_start_y >= 0 && c.freq_start_y <= 500,
                _u8L("Please input valid values:\n(0 < Freq < 500)"));
        require(c.start >= 0 && c.end <= 1 && c.start < c.end, _u8L("Please input a valid damping factor (0 <= DampingStart < DampingEnd <= 1)"));
        break;
    case Test::Cornering: {
        const double max_end = junction_deviation ? 0.3 : 100.0;
        require(c.start >= 0 && c.end <= max_end && c.start < c.end,
                format(_u8L("Please input valid values:\n(0 <= Cornering <= %s)"), format("%.3f", max_end)));
        break;
    }
    }
    if ((c.test == Test::InputShapingFrequency || c.test == Test::InputShapingDamping) && (c.model < 0 || c.model > 1))
        throw std::invalid_argument("invalid test model");
    if (c.test == Test::Cornering && (c.model < 0 || c.model > 2))
        throw std::invalid_argument("invalid test model");

    const char *file = nullptr;
    switch (c.test) {
    case Test::Temperature: file = "temperature_tower/temperature_tower.drc"; break;
    case Test::MaxVolumetricSpeed: file = "volumetric_speed/SpeedTestStructure.drc"; break;
    case Test::PressureAdvanceTower: file = "pressure_advance/tower_with_seam.drc"; break;
    case Test::PressureAdvanceLine: file = "pressure_advance/pressure_advance_test.drc"; break;
    case Test::PressureAdvancePattern: break;
    case Test::FlowRate:
        file = c.flow_linear ? (c.flow_pass == 1 ? "filament_flow/Orca-LinearFlow.3mf" : "filament_flow/Orca-LinearFlow_fine.3mf")
                             : (c.flow_pass == 1 ? "filament_flow/flowrate-test-pass1.3mf" : "filament_flow/flowrate-test-pass2.3mf");
        break;
    case Test::Retraction: file = "retraction/retraction_tower.drc"; break;
    case Test::Vfa: file = "vfa/vfa.drc"; break;
    case Test::InputShapingFrequency:
    case Test::InputShapingDamping: file = c.model == 0 ? "input_shaping/ringing_tower.drc" : "input_shaping/fast_tower_test.drc"; break;
    case Test::Cornering:
        file = c.model == 0 ? "input_shaping/ringing_tower.drc" : c.model == 1 ? "input_shaping/fast_tower_test.drc" : "cornering/SCV-V2.drc";
        break;
    }
    if (file != nullptr) {
        load_model((calib / file).string());
    } else {
        // PA padrão: ObjectList::load_shape_object("Cube"), a "alça" de cada padrão, que
        // _calib_pa_pattern() redimensiona e copia.
        m->reset_plates();
        TriangleMesh mesh(its_make_cube(10, 10, 10));
        const BoundingBoxf3 bb = mesh.bounding_box();
        ModelObject *cube = m->model.add_object();
        cube->name = "Cube";
        cube->add_instance();
        cube->add_volume(mesh)->name = "Cube";
        cube->config.set_key_value("extruder", new ConfigOptionInt(1));
        cube->invalidate_bounding_box();
        cube->translate(-bb.center());
        cube->ensure_on_bed();
    }
    // Plater::new_project() pergunta o que fazer com as alterações dos perfis; aqui elas são
    // descartadas, senão os ajustes de um teste (ex.: modo vaso) passariam ao seguinte.
    for (PresetKind kind : {PresetKind::Process, PresetKind::Filament, PresetKind::Printer})
        discard_changes(kind);

    PresetBundle       &bundle   = *m->bundle;
    DynamicPrintConfig &print    = bundle.prints.get_edited_preset().config;
    DynamicPrintConfig &printer  = bundle.printers.get_edited_preset().config;
    DynamicPrintConfig &filament = bundle.filaments.get_edited_preset().config;
    Model              &model    = m->model;
    ModelObject        &object   = *model.objects.front();
    ModelConfigObject  &obj_cfg  = object.config;
    const double        nozzle_diameter = first_float(printer, "nozzle_diameter");

    Calib_Params params;
    params.start = c.start;
    params.end   = c.end;
    params.step  = c.step;
    params.print_numbers = c.print_numbers;
    params.freqStartX = c.freq_start_x;
    params.freqEndX   = c.freq_end_x;
    params.freqStartY = c.freq_start_y;
    params.freqEndY   = c.freq_end_y;
    params.test_model = c.model;
    params.shaper_type = c.shaper_type;
    params.speeds = c.speeds;
    params.accelerations = c.accelerations;

    switch (c.test) {
    case Test::Temperature: {
        // Plater::calib_temp(): blocos de 10 mm, um a cada 5 °C desde 500 no topo do modelo.
        params.mode = CalibMode::Calib_Temp_Tower;
        const double nozzle = nozzle_diameter > 0.0 ? nozzle_diameter : 0.4;
        const double scale  = nozzle / 0.4;
        long blocks = lround((500 - c.end) / 5 + 1);
        if (blocks > 0 && blocks * 10.0 - EPSILON < object.bounding_box_exact().size().z())
            cut_horizontal(model, 0, blocks * 10.0 - EPSILON, ModelObjectCutAttribute::KeepLower);
        blocks = lround((500 - c.start) / 5);
        if (blocks > 0 && blocks * 10.0 + EPSILON < model.objects.front()->bounding_box_exact().size().z())
            cut_horizontal(model, 0, blocks * 10.0 + EPSILON, ModelObjectCutAttribute::KeepUpper);
        ModelObject &tower = *model.objects.front();
        if (std::abs(scale - 1.0) > EPSILON)
            tower.scale(scale, scale, scale);
        tower.ensure_on_bed();
        const int start_temp = int(lround(c.start));
        printer.set_key_value("resonance_avoidance", new ConfigOptionBool{false});
        filament.set_key_value("nozzle_temperature_initial_layer", new ConfigOptionInts(1, start_temp));
        filament.set_key_value("nozzle_temperature", new ConfigOptionInts(1, start_temp));
        ModelConfigObject &cfg = tower.config;
        cfg.set_key_value("layer_height", new ConfigOptionFloat(nozzle / 2));
        cfg.set_key_value("brim_type", new ConfigOptionEnum<BrimType>(btOuterOnly));
        cfg.set_key_value("brim_width", new ConfigOptionFloat(5.0));
        cfg.set_key_value("brim_object_gap", new ConfigOptionFloat(0.0));
        cfg.set_key_value("alternate_extra_wall", new ConfigOptionBool(false));
        cfg.set_key_value("seam_slope_type", new ConfigOptionEnum<SeamScarfType>(SeamScarfType::None));
        cfg.set_key_value("overhang_reverse", new ConfigOptionBool(false));
        cfg.set_key_value("precise_z_height", new ConfigOptionBool(false));
        print.set_key_value("enable_wrapping_detection", new ConfigOptionBool(false));
        print.set_key_value("initial_layer_print_height", new ConfigOptionFloat(nozzle / 2));
        break;
    }
    case Test::MaxVolumetricSpeed: {
        // Plater::calib_max_vol_speed().
        params.mode = CalibMode::Calib_Vol_speed_Tower;
        const BoundingBoxf bed = get_extents(printer.option<ConfigOptionPoints>("printable_area")->values);
        const double       scale_obj = (bed.size().x() - 10) / object.bounding_box_exact().size().x();
        if (scale_obj < 1.0)
            object.scale(scale_obj, 1, 1);
        const double line_width   = nozzle_diameter * 1.75;
        const double layer_height = nozzle_diameter * 0.8;
        auto        *max_lh       = printer.option<ConfigOptionFloats>("max_layer_height");
        if (max_lh->values[0] < layer_height)
            max_lh->values[0] = layer_height;
        filament.set_key_value("filament_max_volumetric_speed", new ConfigOptionFloats{200});
        filament.set_key_value("slow_down_layer_time", new ConfigOptionFloats{0.0});
        printer.set_key_value("resonance_avoidance", new ConfigOptionBool{false});
        obj_cfg.set_key_value("enable_overhang_speed", new ConfigOptionBool{false});
        obj_cfg.set_key_value("wall_loops", new ConfigOptionInt(1));
        obj_cfg.set_key_value("alternate_extra_wall", new ConfigOptionBool(false));
        obj_cfg.set_key_value("top_shell_layers", new ConfigOptionInt(0));
        obj_cfg.set_key_value("bottom_shell_layers", new ConfigOptionInt(0));
        obj_cfg.set_key_value("sparse_infill_density", new ConfigOptionPercent(0));
        obj_cfg.set_key_value("outer_wall_line_width", new ConfigOptionFloatOrPercent(line_width, false));
        obj_cfg.set_key_value("layer_height", new ConfigOptionFloat(layer_height));
        obj_cfg.set_key_value("brim_type", new ConfigOptionEnum<BrimType>(btOuterAndInner));
        obj_cfg.set_key_value("brim_width", new ConfigOptionFloat(5.0));
        obj_cfg.set_key_value("brim_object_gap", new ConfigOptionFloat(0.0));
        obj_cfg.set_key_value("precise_z_height", new ConfigOptionBool(false));
        print.set_key_value("timelapse_type", new ConfigOptionEnum<TimelapseType>(tlTraditional));
        print.set_key_value("spiral_mode", new ConfigOptionBool(true));
        print.set_key_value("max_volumetric_extrusion_rate_slope", new ConfigOptionFloat(0));
        print.set_key_value("enable_wrapping_detection", new ConfigOptionBool(false));
        const double height = (c.end - c.start + 1) / c.step;
        if (height < object.bounding_box_exact().size().z())
            cut_horizontal(model, 0, height, ModelObjectCutAttribute::KeepLower);
        // A velocidade da parede, em mm/s, sobe com a altura (GCode: start + z * step).
        const double mm3_per_mm = Flow(float(line_width), float(layer_height), float(nozzle_diameter)).mm3_per_mm() * first_float(filament, "filament_flow_ratio");
        params.end   = c.end / mm3_per_mm;
        params.start = c.start / mm3_per_mm;
        params.step  = c.step / mm3_per_mm;
        break;
    }
    case Test::PressureAdvanceTower:
    case Test::PressureAdvanceLine:
    case Test::PressureAdvancePattern: {
        // Plater::calib_pa() e _calib_pa_tower().
        print.set_key_value("overhang_reverse", new ConfigOptionBool(false));
        print.set_key_value("precise_z_height", new ConfigOptionBool(false));
        printer.set_key_value("resonance_avoidance", new ConfigOptionBool{false});
        if (c.test == Test::PressureAdvanceLine) {
            params.mode = CalibMode::Calib_PA_Line;
            break;
        }
        if (c.test == Test::PressureAdvancePattern) {
            params.mode = CalibMode::Calib_PA_Pattern;
            m->calib    = params;
            m->calib_pa_pattern(junction_deviation);
            return;
        }
        params.mode = CalibMode::Calib_PA_Tower;
        print.set_key_value("enable_wrapping_detection", new ConfigOptionBool(false));
        filament.set_key_value("slow_down_layer_time", new ConfigOptionFloats{1.0f});
        obj_cfg.set_key_value("alternate_extra_wall", new ConfigOptionBool(false));
        const DynamicPrintConfig full = bundle.full_config();
        const double wall_speed = CalibPressureAdvance::find_optimal_PA_speed(full, full.get_abs_value("line_width", nozzle_diameter),
                                                                              full.get_abs_value("layer_height"), 0, 0);
        obj_cfg.set_key_value("outer_wall_speed", new ConfigOptionFloat(wall_speed));
        obj_cfg.set_key_value("inner_wall_speed", new ConfigOptionFloat(wall_speed));
        obj_cfg.set_key_value("seam_position", new ConfigOptionEnum<SeamPosition>(spRear));
        obj_cfg.set_key_value("wall_loops", new ConfigOptionInt(2));
        obj_cfg.set_key_value("top_shell_layers", new ConfigOptionInt(0));
        obj_cfg.set_key_value("bottom_shell_layers", new ConfigOptionInt(0));
        obj_cfg.set_key_value("sparse_infill_density", new ConfigOptionPercent(0));
        obj_cfg.set_key_value("brim_type", new ConfigOptionEnum<BrimType>(btEar));
        obj_cfg.set_key_value("brim_object_gap", new ConfigOptionFloat(.0f));
        obj_cfg.set_key_value("brim_ears_max_angle", new ConfigOptionFloat(135.f));
        obj_cfg.set_key_value("brim_width", new ConfigOptionFloat(6.f));
        obj_cfg.set_key_value("seam_slope_type", new ConfigOptionEnum<SeamScarfType>(SeamScarfType::None));
        print.set_key_value("max_volumetric_extrusion_rate_slope", new ConfigOptionFloat(0));
        const double height = std::ceil((c.end - c.start) / c.step) + 1;
        if (height < object.bounding_box_exact().size().z())
            cut_horizontal(model, 0, height, ModelObjectCutAttribute::KeepLower);
        break;
    }
    case Test::FlowRate:
        // Plater::calib_flowrate(); não usa os parâmetros do gerador.
        adjust_settings_for_flowrate_calib(bundle, model, m->build_volume().bounding_volume2d().center(), c.flow_linear, c.flow_pass,
                                           c.flow_monotonic ? ipMonotonic : ipArchimedeanChords);
        printer.set_key_value("resonance_avoidance", new ConfigOptionBool{false});
        break;
    case Test::Retraction: {
        // Plater::calib_retraction().
        params.mode = CalibMode::Calib_Retraction_tower;
        print.set_key_value("enable_wrapping_detection", new ConfigOptionBool(false));
        const double layer_height = nozzle_diameter <= 0.1 ? 0.05 : nozzle_diameter <= 0.2 ? 0.1 : 0.2;
        auto        *max_lh       = printer.option<ConfigOptionFloats>("max_layer_height");
        if (max_lh->values[0] < layer_height)
            max_lh->values[0] = layer_height;
        printer.set_key_value("resonance_avoidance", new ConfigOptionBool{false});
        printer.set_key_value("use_firmware_retraction", new ConfigOptionBool(false));
        obj_cfg.set_key_value("wall_loops", new ConfigOptionInt(2));
        obj_cfg.set_key_value("top_shell_layers", new ConfigOptionInt(0));
        obj_cfg.set_key_value("bottom_shell_layers", new ConfigOptionInt(3));
        obj_cfg.set_key_value("sparse_infill_density", new ConfigOptionPercent(0));
        print.set_key_value("initial_layer_print_height", new ConfigOptionFloat(layer_height));
        obj_cfg.set_key_value("layer_height", new ConfigOptionFloat(layer_height));
        obj_cfg.set_key_value("alternate_extra_wall", new ConfigOptionBool(false));
        obj_cfg.set_key_value("seam_position", new ConfigOptionEnum<SeamPosition>(spAligned));
        obj_cfg.set_key_value("wall_sequence", new ConfigOptionEnum<WallSequence>(WallSequence::InnerOuter));
        obj_cfg.set_key_value("overhang_reverse", new ConfigOptionBool(false));
        obj_cfg.set_key_value("precise_z_height", new ConfigOptionBool(false));
        const double height = 1.0 + 0.4 + (c.end - c.start) / c.step - EPSILON;
        if (height < object.bounding_box_exact().size().z())
            cut_horizontal(model, 0, height, ModelObjectCutAttribute::KeepLower);
        break;
    }
    case Test::Vfa: {
        // Plater::calib_VFA().
        params.mode = CalibMode::Calib_VFA_Tower;
        printer.set_key_value("resonance_avoidance", new ConfigOptionBool{false});
        filament.set_key_value("slow_down_layer_time", new ConfigOptionFloats{0.0});
        print.set_key_value("enable_overhang_speed", new ConfigOptionBool{false});
        print.set_key_value("timelapse_type", new ConfigOptionEnum<TimelapseType>(tlTraditional));
        print.set_key_value("wall_loops", new ConfigOptionInt(1));
        print.set_key_value("alternate_extra_wall", new ConfigOptionBool(false));
        print.set_key_value("top_shell_layers", new ConfigOptionInt(0));
        print.set_key_value("bottom_shell_layers", new ConfigOptionInt(1));
        print.set_key_value("sparse_infill_density", new ConfigOptionPercent(0));
        print.set_key_value("detect_thin_wall", new ConfigOptionBool(false));
        print.set_key_value("spiral_mode", new ConfigOptionBool(true));
        print.set_key_value("enable_wrapping_detection", new ConfigOptionBool(false));
        print.set_key_value("precise_z_height", new ConfigOptionBool(false));
        obj_cfg.set_key_value("brim_type", new ConfigOptionEnum<BrimType>(btOuterOnly));
        obj_cfg.set_key_value("brim_width", new ConfigOptionFloat(3.0));
        obj_cfg.set_key_value("brim_object_gap", new ConfigOptionFloat(0.0));
        const double height = 5 * ((c.end - c.start) / c.step + 1);
        if (height < object.bounding_box_exact().size().z())
            cut_horizontal(model, 0, height, ModelObjectCutAttribute::KeepLower);
        break;
    }
    case Test::InputShapingFrequency:
    case Test::InputShapingDamping:
        // Plater::calib_input_shaping_freq() e _damp().
        params.mode = c.test == Test::InputShapingFrequency ? CalibMode::Calib_Input_shaping_freq : CalibMode::Calib_Input_shaping_damp;
        prepare_input_shaping(print, printer);
        prepare_motion_test(print, filament, printer, object);
        if (c.test == Test::InputShapingFrequency)
            print.set_key_value("layer_height", new ConfigOptionFloat(0.2));
        break;
    case Test::Cornering:
        // Plater::Calib_Cornering().
        params.mode = CalibMode::Calib_Cornering;
        if (junction_deviation) {
            printer.set_key_value("machine_max_junction_deviation", new ConfigOptionFloats{c.end});
            print.set_key_value("default_junction_deviation", new ConfigOptionFloat(0.0));
        } else {
            printer.set_key_value("machine_max_jerk_x", new ConfigOptionFloats{c.end});
            printer.set_key_value("machine_max_jerk_y", new ConfigOptionFloats{c.end});
            print.set_key_value("default_jerk", new ConfigOptionFloat(0));
        }
        prepare_motion_test(print, filament, printer, object);
        printer.set_key_value("input_shaping_emit", new ConfigOptionBool{true});
        printer.set_key_value("input_shaping_type", new ConfigOptionEnum<InputShaperType>(InputShaperType::Disable));
        filament.set_key_value("filament_max_volumetric_speed", new ConfigOptionFloats{200});
        print.set_key_value("default_acceleration", new ConfigOptionFloat(2000));
        print.set_key_value("outer_wall_acceleration", new ConfigOptionFloat(2000));
        break;
    }
    // A torre (ou o conjunto) no centro da mesa, como depois de add_model() no Orca.
    m->place_on_bed();
    m->calib = params;
}

void Engine::clear_paint(size_t object, PaintKind kind)
{
    for (ModelVolume *volume : m->object(object).volumes)
        if (volume->is_model_part())
            paint_facets(*volume, kind).reset();
    m->paint_last.reset();
}

double Engine::overhang_highlight_angle(size_t object) const
{
    if (!m->bundle)
        return 0.0;
    // GLGizmoFdmSupports::get_selection_support_threshold_angle().
    const ModelConfig        &object_config = m->object(object).config;
    const DynamicPrintConfig &process       = m->bundle->prints.get_edited_preset().config;
    auto option = [&](const char *key) { return object_config.has(key) ? object_config.option(key) : process.option(key); };
    const bool enable_support = option("enable_support")->getBool();
    const auto support_type   = SupportType(option("support_type")->getInt());
    return enable_support && is_auto(support_type) ? double(option("support_threshold_angle")->getInt()) : 0.0;
}

std::vector<double> Engine::flush_volumes() const
{
    if (!m->bundle)
        return {};
    return m->bundle->project_config.option<ConfigOptionFloats>("flush_volumes_matrix")->values;
}

void Engine::set_flush_volumes(const std::vector<double> &matrix)
{
    if (!m->bundle)
        throw std::runtime_error("no vendor loaded");
    auto &values = m->bundle->project_config.option<ConfigOptionFloats>("flush_volumes_matrix")->values;
    if (matrix.size() != values.size())
        throw std::invalid_argument("flush volumes matrix has the wrong size");
    for (double v : matrix)
        if (!(v >= 0 && v <= g_max_flush_volume))
            throw std::invalid_argument("flush volume out of range");
    values = matrix;
}

std::vector<double> Engine::flush_multipliers() const
{
    if (!m->bundle)
        return {};
    return m->bundle->project_config.option<ConfigOptionFloats>("flush_multiplier")->values;
}

void Engine::set_flush_multiplier(size_t extruder, double multiplier)
{
    if (!m->bundle)
        throw std::runtime_error("no vendor loaded");
    auto &values = m->bundle->project_config.option<ConfigOptionFloats>("flush_multiplier")->values;
    if (extruder >= values.size())
        throw std::out_of_range("no extruder " + std::to_string(extruder));
    if (!(multiplier > 0 && multiplier <= 3))
        throw std::invalid_argument("flush multiplier out of range");
    values[extruder] = multiplier;
}

namespace {

// Tipos de mesa que a impressora selecionada aceita, como Sidebar::reset_bed_type_combox_choices():
// todos menos os do not_support_bed_type do modelo. Os perfis escrevem ali o rótulo do Orca
// ("Smooth Cool Plate"), o valor ("Cool Plate") ou o nome do Bambu Studio ("Smooth PEI Plate").
std::vector<BedType> supported_bed_types(PresetBundle &bundle)
{
    const Preset &printer = bundle.printers.get_selected_preset();
    const auto *multi = printer.config.option<ConfigOptionBool>("support_multi_bed_types");
    if (!bundle.is_bbl_vendor() && !(multi != nullptr && multi->value))
        return {};
    const Preset *system = printer.is_system ? &printer : bundle.printers.get_selected_preset_parent();
    const VendorProfile::PrinterModel *model = system != nullptr ? PresetUtils::system_printer_model(*system) : nullptr;
    const ConfigOptionDef *def = print_config_def.get("curr_bed_type");
    std::vector<BedType> types;
    for (size_t i = 0; i < def->enum_values.size(); ++i) {
        const BedType type = BedType(i + 1);
        std::vector<std::string> names{def->enum_values[i], def->enum_labels[i]};
        if (type == btPEI)
            names.emplace_back("Smooth PEI Plate");
        const bool unsupported = model != nullptr && std::any_of(names.begin(), names.end(), [&](const std::string &name) {
            return std::find(model->not_support_bed_types.begin(), model->not_support_bed_types.end(), name) != model->not_support_bed_types.end();
        });
        if (!unsupported)
            types.push_back(type);
    }
    return types;
}

} // namespace

std::string Engine::bed_types_json() const
{
    if (!m->bundle)
        throw std::runtime_error("no vendor loaded");
    const ConfigOptionDef *def = print_config_def.get("curr_bed_type");
    const BedType current = m->bundle->project_config.opt_enum<BedType>("curr_bed_type");
    nlohmann::json values = nlohmann::json::array(), labels = nlohmann::json::array();
    for (BedType type : supported_bed_types(*m->bundle)) {
        values.push_back(def->enum_values[type - 1]);
        labels.push_back(i18n::translate(def->enum_labels[type - 1]));
    }
    const std::string current_value = current > btDefault && current < btCount ? def->enum_values[current - 1] : "";
    return nlohmann::json{{"current", current_value}, {"values", values}, {"labels", labels}}.dump();
}

void Engine::set_bed_type(const std::string &value)
{
    if (!m->bundle)
        throw std::runtime_error("no vendor loaded");
    BedType type;
    const std::vector<BedType> supported = supported_bed_types(*m->bundle);
    if (!ConfigOptionEnum<BedType>::from_string(value, type) || std::find(supported.begin(), supported.end(), type) == supported.end())
        throw std::invalid_argument("bed type not available: " + value);
    m->bundle->project_config.set_key_value("curr_bed_type", new ConfigOptionEnum<BedType>(type));
}

std::optional<WipeTower> Engine::wipe_tower() const
{
    if (!m->bundle || m->model.objects.empty())
        return std::nullopt;
    const DynamicPrintConfig config = m->bundle->full_config();
    if (!config.opt_bool("enable_prime_tower"))
        return std::nullopt;

    const std::set<int> filaments = m->object_filaments();
    double              height    = 0.0;
    for (const ModelObject *object : m->model.objects)
        height = std::max(height, object->instance_bounding_box(0).size().z());
    const auto *timelapse = config.option<ConfigOptionEnum<TimelapseType>>("timelapse_type");
    const bool  wrapping  = config.has("enable_wrapping_detection") && config.opt_bool("enable_wrapping_detection");
    const bool  needed    = (timelapse != nullptr && timelapse->value == TimelapseType::tlSmooth) || wrapping;
    if (filaments.size() < 2 && !needed)
        return std::nullopt;

    // PartPlate::estimate_wipe_tower_size() do Orca.
    const int    nozzles      = m->bundle->get_printer_extruder_count();
    const int    used         = int(std::max<size_t>(filaments.size(), 1));
    const double layer_height = config.opt_float("layer_height");
    const double width        = config.opt_float("prime_tower_width");
    const double spacing      = config.option("prime_tower_infill_gap")->getFloat() / 100.;
    const double change_length = [&] {
        const auto *opt = config.option<ConfigOptionFloats>("filament_change_length");
        return opt == nullptr || opt->values.empty() ? 0.0 : *std::max_element(opt->values.begin(), opt->values.end());
    }();
    const auto  *diameters = config.option<ConfigOptionFloats>("filament_diameter");
    const double diameter  = diameters->values.empty() ? 1.75 : *std::max_element(diameters->values.begin(), diameters->values.end());
    double volume = config.opt_float("prime_volume") * (nozzles == 2 ? used : used - 1);
    if (nozzles == 2)
        volume += change_length * PI * diameter * diameter / 4. * (used / 2);

    WipeTower tower;
    tower.height = height;
    const float min_depth = Slic3r::WipeTower::get_limit_depth_by_height(float(height));
    const auto *wall      = config.option<ConfigOptionEnum<WipeTowerWallType>>("wipe_tower_wall_type");
    if (wall != nullptr && wall->value == WipeTowerWallType::wtwRib) {
        const double volume_depth = std::sqrt(volume / layer_height * spacing);
        const double depth        = std::max(double(min_depth), volume_depth);
        const double rib_width    = std::min(config.opt_float("wipe_tower_rib_width"), depth / 2);
        tower.width = tower.depth = rib_width / std::sqrt(2) + std::max(depth + config.opt_float("wipe_tower_extra_rib_length"), volume_depth);
    } else {
        tower.width = width;
        tower.depth = std::max(double(min_depth), volume / (layer_height * width) * spacing);
    }
    const DynamicPrintConfig &project = m->bundle->project_config;
    tower.x        = project.option<ConfigOptionFloats>("wipe_tower_x")->get_at(m->plate);
    tower.y        = project.option<ConfigOptionFloats>("wipe_tower_y")->get_at(m->plate);
    tower.rotation = config.opt_float("wipe_tower_rotation_angle");
    return tower;
}

void Engine::set_wipe_tower_position(double x, double y)
{
    if (!m->bundle)
        throw std::runtime_error("no vendor loaded");
    // Um valor por placa, como no Orca (as que faltam ficam com o da primeira).
    for (const auto &[key, value] : {std::pair{"wipe_tower_x", x}, std::pair{"wipe_tower_y", y}}) {
        auto &values = m->bundle->project_config.option<ConfigOptionFloats>(key)->values;
        values.resize(std::max(values.size(), m->plates.size()), values.empty() ? 0.0 : values.front());
        values[m->plate] = value;
    }
}

std::vector<LayerChange> Engine::layer_changes() const
{
    std::vector<LayerChange> out;
    const CustomGCode::Info  info = m->model.get_curr_plate_custom_gcodes();
    for (const CustomGCode::Item &item : info.gcodes) {
        LayerChange change;
        change.z = item.print_z;
        switch (item.type) {
        case CustomGCode::PausePrint: change.action = LayerChange::Action::Pause; break;
        case CustomGCode::ToolChange:
            change.action   = LayerChange::Action::FilamentChange;
            change.filament = size_t(std::max(item.extruder, 1) - 1);
            break;
        case CustomGCode::Custom:
            change.action = LayerChange::Action::Custom;
            change.gcode  = item.extra;
            break;
        default: continue; // os outros tipos ficam como estão, sem aparecer aqui
        }
        out.push_back(std::move(change));
    }
    return out;
}

void Engine::set_layer_changes(const std::vector<LayerChange> &changes)
{
    if (!m->bundle)
        throw std::runtime_error("no vendor loaded");
    CustomGCode::Info &info = m->model.plates_custom_gcodes[m->model.curr_plate_index];
    std::vector<CustomGCode::Item> items;
    for (const CustomGCode::Item &item : info.gcodes)
        if (item.type != CustomGCode::PausePrint && item.type != CustomGCode::ToolChange && item.type != CustomGCode::Custom)
            items.push_back(item);
    const std::vector<std::string> colors = filament_colors();
    for (const LayerChange &change : changes) {
        if (!(change.z > 0))
            throw std::invalid_argument("layer change below the first layer");
        CustomGCode::Item item{change.z, CustomGCode::PausePrint, 1, "", ""};
        switch (change.action) {
        case LayerChange::Action::Pause: break;
        case LayerChange::Action::FilamentChange:
            m->check_filament(change.filament);
            item.type     = CustomGCode::ToolChange;
            item.extruder = int(change.filament) + 1;
            item.color    = colors[change.filament];
            break;
        case LayerChange::Action::Custom:
            item.type  = CustomGCode::Custom;
            item.extra = change.gcode;
            break;
        }
        items.push_back(std::move(item));
    }
    std::sort(items.begin(), items.end());
    info.gcodes = std::move(items);
    info.mode   = m->layer_change_mode();
}

bool Engine::filament_change_available() const
{
    return m->bundle && m->layer_change_mode() == CustomGCode::MultiAsSingle;
}

void Engine::calc_flush_volumes()
{
    if (!m->bundle)
        throw std::runtime_error("no vendor loaded");
    m->calc_flush_volumes(std::nullopt);
}

std::vector<Placement> Engine::placements() const
{
    std::vector<Placement> out;
    const BuildVolume volume = m->bundle ? m->build_volume() : BuildVolume();
    for (const ModelObject *object : m->model.objects) {
        const ModelInstance &instance = *object->instances.front();
        Placement p = decompose(instance.get_transformation());
        const Vec3d size = object->instance_bounding_box(0).size();
        std::copy(size.data(), size.data() + 3, p.size.begin());
        p.outside = m->bundle && instance.calc_print_volume_state(volume) != ModelInstancePVS_Inside;
        out.push_back(p);
    }
    return out;
}

void Engine::Impl::add(Model &&objects, bool keep_layout)
{
    const size_t first_new = model.objects.size();
    for (const ModelObject *object : objects.objects) {
        ModelObject *added = model.add_object(*object);
        // Uma instância por objeto: cópias são objetos (duplicate()).
        while (added->instances.size() > 1)
            added->delete_instance(added->instances.size() - 1);
    }
    if (first_new == 0 && keep_layout) {
        // Mesa vazia: mantém o arranjo do arquivo (3MF) e centraliza o conjunto.
        place_on_bed();
        return;
    }
    if (bundle)
        arrange_all();
}

void Engine::Impl::store_3mf(const std::string &path, const Thumbnail &thumbnail, const std::string &gcode_path)
{
    if (!bundle)
        throw std::runtime_error("no vendor loaded");
    DynamicPrintConfig config = bundle->full_config_secure();

    ThumbnailData image;
    if (thumbnail.width > 0 && thumbnail.rgba.size() == size_t(thumbnail.width) * thumbnail.height * 4) {
        image.set(thumbnail.width, thumbnail.height);
        image.pixels = thumbnail.rgba;
    }
    // Como PartPlateList::store_to_3mf_structure(): o projeto com as placas lado a lado na
    // grade do Orca; o .gcode.3mf, só com a da mesa.
    Model                                   all;
    std::vector<std::unique_ptr<PlateData>> plates_data;
    const size_t count = gcode_path.empty() ? plates.size() : 1;
    for (size_t index = 0; index < count; ++index) {
        const size_t source = gcode_path.empty() ? index : plate;
        auto        &data   = plates_data.emplace_back(std::make_unique<PlateData>());
        data->plate_index = int(index);
        data->printer_model_id = bundle->printers.get_edited_preset().get_printer_type(bundle.get());
        if (const ConfigOption *nozzle = config.option("nozzle_diameter"))
            data->nozzle_diameters = nozzle->serialize();
        const Vec3d origin = to_3d(gcode_path.empty() ? plate_origin(index, count) : Vec2d::Zero(), 0.);
        const Model &source_model = plate_model(source);
        if (auto it = source_model.plates_custom_gcodes.find(0); it != source_model.plates_custom_gcodes.end())
            all.plates_custom_gcodes[int(index)] = it->second;
        for (const ModelObject *object : source_model.objects) {
            ModelInstance *placed = copy_object(all, *object)->instances.front();
            placed->set_offset(placed->get_offset() + origin);
            data->objects_and_instances.emplace_back(int(all.objects.size() - 1), 0);
        }
    }
    PlateData &plate = *plates_data[gcode_path.empty() ? this->plate : 0];

    StoreParams  params;
    SaveStrategy strategy = SaveStrategy::Zip64;
    if (!gcode_path.empty()) {
        params.export_plate_idx = 0;
        strategy = strategy | SaveStrategy::Silence | SaveStrategy::SplitModel | SaveStrategy::WithGcode | SaveStrategy::SkipModel;
        plate.plate_thumbnail.load_from(image);
        plate.gcode_file = fs::absolute(gcode_path).string();
        plate.is_sliced_valid = true;
        plate.gcode_prediction = std::to_string(int(result.print_statistics.modes.front().time));
        plate.toolpath_outside = result.toolpath_outside;
        plate.timelapse_warning_code = result.timelapse_warning_code;
        plate.is_label_object_enabled = result.label_object_enabled;
        plate.limit_filament_maps = result.limit_filament_maps;
        plate.layer_filaments = result.layer_filaments;
        plate.filament_change_sequence = result.filament_change_sequence;
        plate.nozzle_change_sequence = result.nozzle_change_sequence;
        plate.optimal_assignment = result.optimal_assignment;
        if (print.print_statistics().total_weight != 0.0)
            plate.gcode_weight = float_to_string_decimal_point(print.print_statistics().total_weight, 2);
        plate.is_support_used = print.is_support_used();
        plate.parse_filament_info(&result);
        // Tipo, id e cor de cada filamento usado, como Plater::export_3mf().
        const auto *colors = config.option<ConfigOptionStrings>("filament_colour");
        const auto *ids = config.option<ConfigOptionStrings>("filament_ids");
        for (FilamentInfo &info : plate.slice_filaments_info) {
            std::string displayed_type;
            info.type = config.get_filament_type(displayed_type, info.id);
            info.filament_id = ids != nullptr ? ids->get_at(info.id) : "";
            info.color = colors != nullptr ? colors->get_at(info.id) : "#FFFFFF";
        }
    }

    // get_current_project_embedded_presets() devolve cópias que o chamador apaga.
    std::vector<std::unique_ptr<Preset>> presets;
    for (Preset *preset : bundle->get_current_project_embedded_presets()) {
        presets.emplace_back(preset);
        params.project_presets.push_back(preset);
    }
    const std::string absolute = fs::absolute(path).string();
    params.path = absolute.c_str();
    params.model = &all;
    for (const auto &data : plates_data)
        params.plate_data_list.push_back(data.get());
    params.config = &config;
    // Uma por placa, na ordem delas; só a da mesa tem imagem.
    std::vector<ThumbnailData> images(plates_data.size());
    if (image.is_valid()) {
        images[size_t(plate.plate_index)] = image;
        for (ThumbnailData &each : images)
            params.thumbnail_data.push_back(&each);
    }
    params.strategy = strategy;
    if (!store_bbs_3mf(params))
        throw std::runtime_error(format(_u8L("Could not write %1%."), fs::path(path).filename().string()));
}

ModelObject &Engine::Impl::object(size_t index)
{
    if (index >= model.objects.size())
        throw std::out_of_range("no object " + std::to_string(index));
    return *model.objects[index];
}

void Engine::set_transform(size_t object, const std::array<double, 16> &transform)
{
    ModelObject &o = m->object(object);
    Transform3d matrix;
    std::copy(transform.begin(), transform.end(), matrix.data());
    o.instances.front()->set_transformation(Geometry::Transformation(matrix));
    o.invalidate_bounding_box();
    o.ensure_on_bed();
}

void Engine::set_placement(size_t object, const Vec3 &offset, const Vec3 &rotation, const Vec3 &scale)
{
    ModelObject &o = m->object(object);
    o.instances.front()->set_transformation(compose(offset, rotation, scale));
    o.invalidate_bounding_box();
    o.ensure_on_bed();
}

void Engine::lay_on_face(size_t object, size_t triangle)
{
    ModelObject &o = m->object(object);
    const indexed_triangle_set its = object_mesh(o, nullptr);
    if (triangle >= its.indices.size())
        throw std::out_of_range("no triangle " + std::to_string(triangle));
    const stl_triangle_vertex_indices &t = its.indices[triangle];
    const Vec3d a = its.vertices[t[0]].cast<double>(), b = its.vertices[t[1]].cast<double>(), c = its.vertices[t[2]].cast<double>();

    ModelInstance &instance = *o.instances.front();
    Transform3d matrix = instance.get_matrix();
    // Normais se transformam pela inversa transposta (escala não uniforme).
    const Vec3d normal = (matrix.linear().inverse().transpose() * (b - a).cross(c - a)).normalized();
    if (!normal.allFinite())
        throw std::invalid_argument("degenerate triangle");
    // Gira em torno da origem da instância, como o gizmo "Lay on face" do Orca.
    const Eigen::Quaterniond rotation = Eigen::Quaterniond::FromTwoVectors(normal, -Vec3d::UnitZ());
    matrix.linear() = rotation.toRotationMatrix() * matrix.linear();
    instance.set_transformation(Geometry::Transformation(matrix));
    o.invalidate_bounding_box();
    o.ensure_on_bed();
}

std::string Engine::options_json(PresetKind kind) const
{
    // Página, grupo e linha de cada opção nas abas do Orca; as opções saem nessa ordem e as
    // que a GUI do Orca não mostra vêm depois, na categoria do PrintConfigDef.
    std::map<std::string, std::pair<size_t, const OptionPlace *>> places;
    for (const OptionPlace &place : option_layout(kind))
        places.emplace(place.key, std::make_pair(places.size(), &place));
    // printer_options() repete opções de extruder_option_keys() (extruder_type).
    std::vector<std::string> keys;
    std::set<std::string> unique;
    for (const std::string &key : option_keys(kind))
        if (editable_def(key) != nullptr && unique.insert(key).second)
            keys.push_back(key);
    std::stable_sort(keys.begin(), keys.end(), [&](const std::string &a, const std::string &b) {
        auto pa = places.find(a), pb = places.find(b);
        return (pa == places.end() ? places.size() : pa->second.first) < (pb == places.end() ? places.size() : pb->second.first);
    });
    // Rótulos curtos repetidos fora de uma linha do Orca ("Fan speed") usam o full_label.
    std::map<std::string, int> label_count;
    for (const std::string &key : keys)
        ++label_count[editable_def(key)->label];
    nlohmann::json out = nlohmann::json::array();
    for (const std::string &key : keys) {
        const ConfigOptionDef *def = editable_def(key);
        auto it = places.find(key);
        const OptionPlace *place = it == places.end() ? nullptr : it->second.second;
        std::string label;
        if (place != nullptr && *place->line != '\0' && def->label != place->line)
            label = i18n::translate(place->line) + " · " + i18n::translate(def->label);
        else
            label = i18n::translate(label_count[def->label] > 1 && !def->full_label.empty() ? def->full_label : def->label);
        nlohmann::json o = {
            {"key", key},
            {"label", label},
            {"category", i18n::translate(place != nullptr ? place->page : def->category)},
            {"page", place != nullptr ? place->page : def->category},
            {"group", place != nullptr ? i18n::translate(place->group) : std::string()},
            {"tooltip", i18n::translate(def->tooltip)},
            {"sidetext", i18n::translate(def->sidetext)},
            {"type", def->type == coPoints ? "points" : type_name(def->type)},
            {"vector", (int(def->type) & int(coVectorType)) != 0},
            {"mode", int(def->mode)},
            {"multiline", def->multiline || def->is_code},
            {"nullable", def->nullable},
            {"enum_values", def->enum_values},
            {"enum_labels", translated(def->enum_labels)},
        };
        if (def->min > -FLT_MAX)
            o["min"] = def->min;
        if (def->max < FLT_MAX)
            o["max"] = def->max;
        out.push_back(std::move(o));
    }
    return out.dump();
}

std::string Engine::values_json(PresetKind kind) const
{
    const PresetCollection &collection = m->collection(kind);
    const DynamicPrintConfig &config = collection.get_edited_preset().config;
    nlohmann::json values = nlohmann::json::object();
    for (const std::string &key : option_keys(kind))
        if (editable_def(key) != nullptr && config.has(key))
            values[key] = config.opt_serialize(key);
    // Valor no preset salvo de cada opção alterada (para mostrar e restaurar).
    const DynamicPrintConfig &saved_config = collection.get_selected_preset().config;
    const std::vector<std::string> dirty = collection.current_dirty_options();
    nlohmann::json saved = nlohmann::json::object();
    for (const std::string &key : dirty)
        if (saved_config.has(key))
            saved[key] = saved_config.opt_serialize(key);
    // Substituições do filamento ("nil" = não substitui): o valor que vale no lugar, da opção sem
    // o prefixo "filament_" na impressora ou no processo, como a página Setting Overrides do Orca.
    nlohmann::json fallback = nlohmann::json::object();
    const std::string prefix = "filament_";
    if (kind == PresetKind::Filament)
        for (const std::string &key : option_keys(kind)) {
            const ConfigOptionDef *def = editable_def(key);
            if (def == nullptr || !def->nullable || key.rfind(prefix, 0) != 0)
                continue;
            const std::string base = key.substr(prefix.size());
            for (PresetKind source : {PresetKind::Printer, PresetKind::Process}) {
                const DynamicPrintConfig &other = m->collection(source).get_edited_preset().config;
                if (const auto *vector = dynamic_cast<const ConfigOptionVectorBase *>(other.option(base)); vector != nullptr && vector->size() > 0) {
                    fallback[key] = vector->vserialize().front();
                    break;
                }
                if (const ConfigOption *option = other.option(base); option != nullptr) {
                    fallback[key] = option->serialize();
                    break;
                }
            }
        }
    return nlohmann::json{{"values", values}, {"dirty", dirty}, {"saved", saved}, {"fallback", fallback}}.dump();
}

void Engine::set_option(PresetKind kind, const std::string &key, const std::string &value)
{
    const std::vector<std::string> &keys = option_keys(kind);
    const ConfigOptionDef *def = editable_def(key);
    if (def == nullptr || std::find(keys.begin(), keys.end(), key) == keys.end())
        throw std::invalid_argument("not an editable option: " + key);
    PresetCollection &collection = m->collection(kind);
    // Sobre uma cópia: um valor inválido não deixa o preset pela metade.
    DynamicPrintConfig config = collection.get_edited_preset().config;
    try {
        config.set_deserialize_strict(key, value);
    } catch (const BadOptionValueException &) {
        throw std::invalid_argument(format(_u8L("Invalid value provided for parameter %1%: %2%"), i18n::translate(def->label), value));
    }
    collection.get_edited_preset().config = std::move(config);
    if (key == "layer_height")
        m->wanted_layer_height.reset();
}

void Engine::discard_changes(PresetKind kind)
{
    m->collection(kind).discard_current_changes();
}

std::vector<std::string> Engine::user_presets(PresetKind kind) const
{
    std::vector<std::string> out;
    for (const Preset &preset : m->collection(kind))
        if (preset.is_user() && preset.is_visible && preset.is_compatible)
            out.push_back(preset.name);
    return out;
}

void Engine::save_preset(PresetKind kind, const std::string &name)
{
    if (name.empty())
        throw std::invalid_argument(_u8L("The name is not allowed to be empty."));
    PresetCollection &collection = m->collection(kind);
    if (const Preset *existing = collection.find_preset(name, false); existing != nullptr && !existing->is_user())
        throw std::invalid_argument(format(_u8L("The system preset %1% cannot be overwritten."), name));
    // Como a GUI: grava em <data_dir>/user/default só a diferença para o preset pai.
    m->bundle->save_changes_for_preset(name, preset_type(kind), {});
}

void Engine::delete_preset(PresetKind kind, const std::string &name)
{
    PresetCollection &collection = m->collection(kind);
    const Preset *preset = collection.find_preset(name, false);
    if (preset == nullptr || !preset->is_user())
        throw std::invalid_argument("not a user preset: " + name);
    const bool was_selected = collection.get_selected_preset_name() == name;
    if (!collection.delete_preset(name))
        throw std::runtime_error(format(_u8L("Could not delete the preset %1%."), name));
    if (was_selected) {
        // Volta ao padrão da impressora (ou ao primeiro compatível), como ao trocar de impressora.
        m->bundle->update_compatible(PresetSelectCompatibleType::Always);
        if (kind == PresetKind::Printer) {
            select(PresetKind::Printer, presets(PresetKind::Printer).front());
        } else {
            m->select_printer_defaults();
            m->bundle->set_filament_preset(0, m->bundle->filaments.get_selected_preset_name());
        }
    }
}

std::vector<std::string> Engine::import_preset(const std::string &path)
{
    std::vector<std::string> files{path};
    // 1 = sobrescrever um preset de usuário com o mesmo nome.
    m->bundle->import_presets(files, [](const std::string &) { return 1; },
                              ForwardCompatibilitySubstitutionRule::EnableSilent, m->app_config);
    if (files.empty())
        throw std::runtime_error(format(_u8L("No compatible preset found in %1%."), fs::path(path).filename().string()));
    m->bundle->update_compatible(PresetSelectCompatibleType::Never);
    // import_presets devolve os arquivos importados; o nome do preset está no JSON.
    std::vector<std::string> names;
    for (const std::string &file : files) {
        boost::nowide::ifstream in(file);
        names.push_back(nlohmann::json::parse(in).value("name", fs::path(file).stem().string()));
    }
    return names;
}

void Engine::export_preset(PresetKind kind, const std::string &path) const
{
    const Preset &preset = m->collection(kind).get_edited_preset();
    preset.config.save_to_json(path, preset.name, "User", SLIC3R_VERSION);
}

std::vector<std::array<unsigned, 2>> Engine::gcode_thumbnail_sizes() const
{
    if (!m->bundle)
        throw std::runtime_error("no vendor loaded");
    std::vector<std::array<unsigned, 2>> out;
    // Como GCode::_do_export(): Bambu grava a configuração no lugar das miniaturas.
    if (m->bundle->is_bbl_vendor())
        return out;
    for (const auto &[format, size] : GCodeThumbnails::make_and_check_thumbnail_list(m->printer()).first)
        out.push_back({unsigned(size.x()), unsigned(size.y())});
    return out;
}

SliceStats Engine::slice(const std::string &gcode_path, const ProgressFn &progress, const std::vector<Thumbnail> &thumbnails)
{
    if (!m->bundle)
        throw std::runtime_error("no vendor loaded");
    if (m->model.objects.empty())
        throw std::runtime_error("no model loaded");

    // Print ignoraria em silêncio as instâncias fora do volume; a GUI do Orca recusa fatiar.
    const BuildVolume volume = m->build_volume();
    for (const ModelObject *object : m->model.objects)
        if (object->instances.front()->calc_print_volume_state(volume) != ModelInstancePVS_Inside)
            throw std::runtime_error("object outside the printable volume: " + object->name);

    DynamicPrintConfig config = m->bundle->full_config();
    // Print lê a torre na posição da placa 0 (sem set_plate_index); aqui, a da placa da mesa.
    for (const char *key : {"wipe_tower_x", "wipe_tower_y"}) {
        auto *opt = config.option<ConfigOptionFloats>(key);
        opt->values = {opt->get_at(m->plate)};
    }

    // Como o Print::apply() do Creality Print: default_filament_colour com a cor de cada filamento
    // usado (vazio nos outros). O firmware da K3 lê esse campo para escolher o primeiro bico a
    // aquecer e para em BOX_FIRST_NOZZLE_HEATING quando ele está vazio.
    const bool creality = m->vendor.rfind("CrealityPrint/", 0) == 0;
    // O leitor de metadados do firmware da Creality (metadata.py da K2/K3) só lê
    // default_filament_colour de arquivos que não se identificam como de outro fatiador
    // (OrcaSlicer cai no leitor do PrusaSlicer); com o gerador "PocketSlicer" e o modelo da
    // impressora no cabeçalho, cai no leitor Creality.
    set_gcode_generator_name(creality ? "PocketSlicer" : "");
    // O QIDI Studio considera carregado o filamento inicial depois do G-code inicial (patch 0005);
    // o Orca, só nas Bambu, e nas outras roda a troca do filamento inicial para ele mesmo, que
    // na Q2 corta e descarrega o filamento.
    set_bambu_start_sequence(m->vendor.rfind("QIDIStudio/", 0) == 0);
    if (creality) {
        std::set<int> used = m->object_filaments();
        if (auto it = m->model.plates_custom_gcodes.find(m->model.curr_plate_index); it != m->model.plates_custom_gcodes.end())
            for (const CustomGCode::Item &item : it->second.gcodes)
                if (item.type == CustomGCode::ToolChange)
                    used.insert(item.extruder);
        const auto        &colors   = config.option<ConfigOptionStrings>("filament_colour")->values;
        std::vector<std::string> defaults(colors.size());
        for (int filament : used)
            if (filament >= 1 && size_t(filament) <= colors.size())
                defaults[filament - 1] = colors[filament - 1];
        config.option<ConfigOptionStrings>("default_filament_colour", true)->values = defaults;
    }

    // Os filamentos dos objetos podem ter mudado desde que as ações por altura foram gravadas.
    if (auto it = m->model.plates_custom_gcodes.find(m->model.curr_plate_index); it != m->model.plates_custom_gcodes.end())
        it->second.mode = m->layer_change_mode();

    // Plater::_calib_pa_pattern_gen_gcode(): no teste de PA padrão, o G-code dos padrões ao lado
    // de cada objeto da placa, por camada, toma o lugar das ações por altura só neste fatiamento.
    const int                        plate_key = m->model.curr_plate_index;
    std::optional<CustomGCode::Info> layer_actions;
    if (m->pa_pattern) {
        auto it = m->model.plates_custom_gcodes.find(plate_key);
        layer_actions = it != m->model.plates_custom_gcodes.end() ? it->second : CustomGCode::Info();
        CustomGCode::Info gcodes;
        for (const ModelObject *object : m->model.objects) {
            CustomGCode::Info info = m->pa_pattern->generate_custom_gcodes(config, m->bundle->is_bbl_vendor(), *object, Vec3d::Zero());
            if (object == m->model.objects.front())
                gcodes = std::move(info);
            else
                for (size_t i = 0; i < gcodes.gcodes.size() && i < info.gcodes.size(); ++i)
                    gcodes.gcodes[i].extra += info.gcodes[i].extra;
        }
        m->model.plates_custom_gcodes[plate_key] = std::move(gcodes);
    }

    Print &print = m->print;
    // Igual à GUI (Plater): firmware Bambu pula validações/ajustes feitos para Marlin.
    print.is_BBL_printer() = m->bundle->is_bbl_vendor();
    print.set_status_callback([&progress](const PrintBase::SlicingStatus &s) {
        if (progress)
            progress(s.percent, i18n::translate(s.text));
    });
    print.set_calib_params(m->calib);
    print.apply(m->model, config);
    if (layer_actions) {
        if (layer_actions->gcodes.empty())
            m->model.plates_custom_gcodes.erase(plate_key);
        else
            m->model.plates_custom_gcodes[plate_key] = std::move(*layer_actions);
    }
    print.restart();

    StringObjectException warning;
    StringObjectException err = print.validate(&warning);
    if (!err.string.empty())
        throw std::runtime_error(err.string);

    // Sem mudança desde o último slice, GCode::do_export() não refaz o arquivo e deixaria o
    // resultado vazio ("Fatiar de novo" fechava o app na pré-visualização): vale o anterior.
    if (print.is_step_done(psGCodeExport) && fs::exists(gcode_path) && !m->result.moves.empty())
        return stats_of(m->result, m->firmware_flush);

    GCodeProcessorResult result;
    m->result = GCodeProcessorResult();
    m->firmware_flush.clear();
    try {
        print.process();
        // GCode::do_export cria o diretório pai; com caminho relativo ele seria vazio.
        // Chamado por tamanho de gcode_thumbnail_sizes(); a GUI renderiza nessa hora.
        ThumbnailsGeneratorCallback thumbnail_cb = [&thumbnails](const ThumbnailsParams &params) {
            ThumbnailsList out;
            for (const Vec2d &size : params.sizes)
                for (const Thumbnail &image : thumbnails)
                    if (image.width == unsigned(size.x()) && image.height == unsigned(size.y()) &&
                        image.rgba.size() == size_t(image.width) * image.height * 4) {
                        ThumbnailData &data = out.emplace_back();
                        data.set(image.width, image.height);
                        data.pixels = image.rgba;
                        break;
                    }
            return out;
        };
        print.export_gcode(fs::absolute(gcode_path).string(), &result, thumbnail_cb);
    } catch (const CanceledException &) {
        throw Canceled();
    }
    if (print.canceled())
        throw Canceled();

    m->result         = std::move(result);
    m->firmware_flush = creality ? firmware_flush(m->result, print.config()) : std::map<size_t, double>();
    return stats_of(m->result, m->firmware_flush);
}

void Engine::cancel()
{
    m->print.cancel();
}

Toolpaths Engine::toolpaths() const
{
    using Move = GCodeProcessorResult::MoveVertex;
    Toolpaths out;
    const std::vector<Move> &moves = m->result.moves;
    // O G-code aberto traz as cores; o do slice(), não (a GUI as tira da configuração).
    out.filament_colors = m->result.extruder_colors;
    if (out.filament_colors.empty())
        out.filament_colors = m->print.config().filament_colour.values;
    for (size_t i = 1; i < moves.size(); ++i) {
        const Move &prev = moves[i - 1];
        const Move &move = moves[i];
        const uint32_t layer = move.layer_id;
        if (out.layer_z.size() <= layer)
            out.layer_z.resize(layer + 1, 0.f);
        if (move.type == EMoveType::Extrude && move.width > 0.f && move.height > 0.f) {
            out.extrusions.insert(out.extrusions.end(), {prev.position.x(), prev.position.y(), prev.position.z(),
                                                         move.position.x(), move.position.y(), move.position.z(),
                                                         move.width, move.height, move.feedrate});
            out.roles.push_back(uint8_t(move.extrusion_role));
            out.layers.push_back(layer);
            out.filaments.push_back(move.extruder_id);
            out.layer_z[layer] = std::max(out.layer_z[layer], move.position.z());
        } else if (move.type == EMoveType::Travel && prev.position != move.position) {
            out.travels.insert(out.travels.end(), {prev.position.x(), prev.position.y(), prev.position.z(),
                                                   move.position.x(), move.position.y(), move.position.z()});
            out.travel_layers.push_back(layer);
        }
    }
    return out;
}

std::vector<std::string> Engine::role_names()
{
    std::vector<std::string> out;
    for (int role = 0; role < erCount; ++role)
        out.push_back(ExtrusionEntity::role_to_string(ExtrusionRole(role)));
    return out;
}

} // namespace slicer
