#include "engine.hpp"

#include <algorithm>
#include <atomic>
#include <regex>
#include <set>

#include <boost/filesystem.hpp>
#include <boost/nowide/fstream.hpp>
#include <boost/log/core.hpp>
#include <boost/log/expressions.hpp>
#include <boost/log/trivial.hpp>

#include "libslic3r/libslic3r.h"
#include "libslic3r/AppConfig.hpp"
#include "libslic3r/Arrange.hpp"
#include "libslic3r/BuildVolume.hpp"
#include "libslic3r/ExtrusionEntity.hpp"
#include "libslic3r/Format/bbs_3mf.hpp"
#include "libslic3r/GCode/GCodeProcessor.hpp"
#include "libslic3r/GCode/ThumbnailData.hpp"
#include "libslic3r/GCode/Thumbnails.hpp"
#include "libslic3r/I18N.hpp"
#include "libslic3r/LocalesUtils.hpp"
#include "libslic3r/format.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/PresetBundle.hpp"
#include "libslic3r/Print.hpp"
#include "libslic3r/PrintConfig.hpp"
#include "libslic3r/Utils.hpp"

#include "nlohmann/json.hpp"

#include "i18n.hpp"

namespace fs = boost::filesystem;
using namespace Slic3r;

namespace slicer {

namespace {
void copy_for_orca(const fs::path &src, const fs::path &dst);
} // namespace

struct Engine::Impl {
    fs::path                      profiles_dir;
    fs::path                      vendors_dir; // perfis dos fatiadores dos fabricantes: <app>/<fabricante>.json
    AppConfig                     app_config;
    std::unique_ptr<PresetBundle> bundle;
    std::string                   vendor; // de bundle
    Model                         model;
    Print                         print;
    GCodeProcessorResult          result; // do último slice()
    // Projeto aberto com várias placas: todos os objetos do arquivo e as placas, com a
    // posição de cada uma e os pares (objeto, instância) dela em project.
    struct Plate {
        Vec2d                                 origin = Vec2d::Zero();
        std::vector<std::pair<size_t, size_t>> instances;
    };
    Model              project;
    std::vector<Plate> plates;
    size_t             plate = 0; // a da mesa

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

    ModelObject &object(size_t index);
    void add(Model &&objects, bool keep_layout = true);
    // 3MF dos objetos da mesa como uma placa. Com gcode_path, um .gcode.3mf como
    // Plater::export_gcode_3mf(): sem as malhas, com o G-code e os dados do último slice().
    void store_3mf(const std::string &path, const Thumbnail &thumbnail, const std::string &gcode_path = {});

    void forget_project()
    {
        project.clear_objects();
        project.clear_materials();
        plates.clear();
    }

    // Uma instância por objeto da placa, nas posições do arquivo em relação a ela.
    void show_plate(size_t index)
    {
        const Plate &plate = plates[index];
        this->plate = index;
        model.clear_objects();
        model.clear_materials();
        for (const auto &[object, instance] : plate.instances) {
            const ModelObject *source = project.objects[object];
            ModelObject       *added = model.add_object(*source);
            while (added->instances.size() > 1)
                added->delete_instance(added->instances.size() - 1);
            ModelInstance *placed = added->instances.front();
            placed->set_transformation(source->instances[instance]->get_transformation());
            placed->set_offset(placed->get_offset() - to_3d(plate.origin, 0.));
        }
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
    void arrange_all()
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

void Engine::select(PresetKind kind, const std::string &name)
{
    PresetCollection &collection = m->collection(kind);
    const Preset *preset = collection.find_preset(name);
    if (preset == nullptr || preset->is_default || !preset->is_compatible)
        throw std::invalid_argument("preset not available: " + name);

    switch (kind) {
    case PresetKind::Printer:
        collection.select_preset_by_name(name, true);
        m->bundle->update_multi_material_filament_presets();
        // Troca processo/filamento incompatíveis pelos primeiros compatíveis...
        m->bundle->update_compatible(PresetSelectCompatibleType::Always);
        // ...e prefere os padrões declarados pela impressora.
        m->select_printer_defaults();
        // Tipo de mesa padrão da impressora, como o Plater da GUI faz (a validação
        // recusa filamentos sem temperatura para a mesa escolhida).
        m->bundle->project_config.set_key_value("curr_bed_type",
            new ConfigOptionEnum<BedType>(m->bundle->printers.get_selected_preset().get_default_bed_type(m->bundle.get())));
        m->place_on_bed();
        break;
    case PresetKind::Filament:
        m->select_filament(name);
        break;
    case PresetKind::Process:
        collection.select_preset_by_name(name, true);
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

// Copia os perfis de um fatiador do fabricante (Creality Print...) para dst, ajustando as
// opções cujo valor o Orca não aceita: com uma só, PresetBundle descartaria o fabricante
// inteiro ("Invalid value provided for parameter X"). O Creality Print grava "" onde o Orca
// usa "nil" (sem valor) nas listas por extrusora; se trocar não basta, a opção sai e o perfil
// fica com o valor do pai.
void copy_for_orca(const fs::path &src, const fs::path &dst)
{
    static const std::regex invalid_option("Invalid value provided for parameter ([A-Za-z0-9_]+)");
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
    }
}

SliceStats stats_of(const GCodeProcessorResult &result)
{
    SliceStats stats;
    stats.print_time_s = result.print_statistics.modes.front().time;
    // Por filamento, do volume (com a purga das trocas), como o "filament used" no fim do
    // G-code: diâmetro, densidade e preço de cada um. Os totais são a soma.
    auto at = [](const std::vector<float> &values, size_t i, double fallback) {
        return i < values.size() ? double(values[i]) : fallback;
    };
    for (const auto &[index, mm3] : result.print_statistics.total_volumes_per_extruder) {
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
    m->forget_project();
    m->model.clear_objects();
    m->model.clear_materials();
    m->add(std::move(model));
}

void Engine::add_model(const std::string &path)
{
    m->add(read_model(path));
    m->forget_project();
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
    // Como Plater::load_files(): presets embutidos, depois a configuração do projeto.
    if (!project_presets.empty())
        m->bundle->load_project_embedded_presets(project_presets, ForwardCompatibilitySubstitutionRule::EnableSilent);
    m->bundle->load_config_model(path, std::move(config), version);
    for (Preset &filament : m->bundle->filaments)
        filament.is_visible = true;

    // O arquivo diz a placa de cada objeto pelo id do 3MF, que o carregamento não devolve;
    // mas as placas ficam numa grade, como em PartPlateList::compute_shape_position():
    // a primeira na origem, colunas para +x e linhas para -y, separadas por 1/5 da mesa.
    // Cada instância vai para a placa mais próxima do seu centro.
    const BoundingBoxf bed     = m->build_volume().bounding_volume2d();
    const Vec2d        stride  = bed.size() * (1. + 1. / 5.);
    const int          columns = int(std::ceil(std::sqrt(double(plate_count)) - 1e-6));
    m->forget_project();
    m->plates.resize(plate_count);
    for (size_t i = 0; i < plate_count; ++i)
        m->plates[i].origin = Vec2d(double(i % columns) * stride.x(), -double(i / columns) * stride.y());
    for (size_t i = 0; i < model.objects.size(); ++i)
        for (size_t j = 0; j < model.objects[i]->instances.size(); ++j) {
            const Vec2d offset = to_2d(model.objects[i]->instance_bounding_box(j).center()) - bed.center();
            const int   column = int(std::lround(offset.x() / stride.x()));
            const int   row    = int(std::lround(-offset.y() / stride.y()));
            const long  plate  = long(row) * columns + column;
            if (plate_count == 1)
                m->plates[0].instances.emplace_back(i, j);
            else if (column >= 0 && column < columns && row >= 0 && plate < long(plate_count))
                m->plates[plate].instances.emplace_back(i, j);
        }
    m->project = std::move(model);
    // A primeira placa com objetos.
    const auto first = std::find_if(m->plates.begin(), m->plates.end(), [](const Impl::Plate &p) { return !p.instances.empty(); });
    if (first == m->plates.end())
        throw std::runtime_error(format(_u8L("No objects found in %1%."), fs::path(path).filename().string()));
    m->show_plate(first - m->plates.begin());
    if (m->plates.size() == 1)
        m->forget_project();
    return m->vendor;
}

std::vector<size_t> Engine::project_plates() const
{
    std::vector<size_t> objects;
    for (const Impl::Plate &plate : m->plates)
        objects.push_back(plate.instances.size());
    return objects;
}

size_t Engine::project_plate() const
{
    return m->plate;
}

void Engine::select_plate(size_t plate)
{
    if (plate >= m->plates.size())
        throw std::out_of_range("invalid plate");
    if (m->plates[plate].instances.empty())
        throw std::runtime_error(format(_u8L("Plate %1% is empty."), plate + 1));
    m->show_plate(plate);
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

std::vector<Placement> Engine::placements() const
{
    std::vector<Placement> out;
    const BuildVolume volume = m->bundle ? m->build_volume() : BuildVolume();
    for (const ModelObject *object : m->model.objects) {
        const ModelInstance &instance = *object->instances.front();
        const Geometry::Transformation &t = instance.get_transformation();
        Placement p;
        std::copy(t.get_matrix().data(), t.get_matrix().data() + 16, p.transform.begin());
        const Vec3d offset = t.get_offset(), rotation = t.get_rotation(), scale = t.get_scaling_factor();
        for (int i = 0; i < 3; ++i) {
            p.offset[i] = offset[i];
            p.rotation[i] = Geometry::rad2deg(rotation[i]);
            p.scale[i] = scale[i];
        }
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
    // Uma placa com todos os objetos, como PartPlateList::store_to_3mf_structure().
    PlateData plate;
    plate.plate_index = 0;
    for (size_t i = 0; i < model.objects.size(); ++i)
        plate.objects_and_instances.emplace_back(int(i), 0);
    plate.printer_model_id = bundle->printers.get_edited_preset().get_printer_type(bundle.get());
    if (const ConfigOption *nozzle = config.option("nozzle_diameter"))
        plate.nozzle_diameters = nozzle->serialize();

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
    params.model = &model;
    params.plate_data_list = {&plate};
    params.config = &config;
    if (image.is_valid())
        params.thumbnail_data = {&image};
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
    for (double s : scale)
        if (!(s > 0.0))
            throw std::invalid_argument("scale must be positive");
    ModelObject &o = m->object(object);
    Geometry::Transformation t;
    t.set_offset(Vec3d(offset[0], offset[1], offset[2]));
    t.set_rotation(Vec3d(Geometry::deg2rad(rotation[0]), Geometry::deg2rad(rotation[1]), Geometry::deg2rad(rotation[2])));
    t.set_scaling_factor(Vec3d(scale[0], scale[1], scale[2]));
    o.instances.front()->set_transformation(t);
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
    nlohmann::json out = nlohmann::json::array();
    for (const std::string &key : option_keys(kind)) {
        const ConfigOptionDef *def = editable_def(key);
        if (def == nullptr)
            continue;
        nlohmann::json o = {
            {"key", key},
            {"label", i18n::translate(def->label)},
            {"category", i18n::translate(def->category)},
            {"tooltip", i18n::translate(def->tooltip)},
            {"sidetext", i18n::translate(def->sidetext)},
            {"type", def->type == coPoints ? "points" : type_name(def->type)},
            {"vector", (int(def->type) & int(coVectorType)) != 0},
            {"mode", int(def->mode)},
            {"multiline", def->multiline || def->is_code},
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
    return nlohmann::json{{"values", values}, {"dirty", dirty}, {"saved", saved}}.dump();
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

    Print &print = m->print;
    // Igual à GUI (Plater): firmware Bambu pula validações/ajustes feitos para Marlin.
    print.is_BBL_printer() = m->bundle->is_bbl_vendor();
    print.set_status_callback([&progress](const PrintBase::SlicingStatus &s) {
        if (progress)
            progress(s.percent, i18n::translate(s.text));
    });
    print.apply(m->model, config);
    print.restart();

    StringObjectException warning;
    StringObjectException err = print.validate(&warning);
    if (!err.string.empty())
        throw std::runtime_error(err.string);

    // Sem mudança desde o último slice, GCode::do_export() não refaz o arquivo e deixaria o
    // resultado vazio ("Fatiar de novo" fechava o app na pré-visualização): vale o anterior.
    if (print.is_step_done(psGCodeExport) && fs::exists(gcode_path) && !m->result.moves.empty())
        return stats_of(m->result);

    GCodeProcessorResult result;
    m->result = GCodeProcessorResult();
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

    m->result = std::move(result);
    return stats_of(m->result);
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
