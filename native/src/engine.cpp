#include "engine.hpp"

#include <algorithm>
#include <atomic>

#include <boost/filesystem.hpp>
#include <boost/log/core.hpp>
#include <boost/log/expressions.hpp>
#include <boost/log/trivial.hpp>

#include "libslic3r/libslic3r.h"
#include "libslic3r/AppConfig.hpp"
#include "libslic3r/GCode/GCodeProcessor.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/PresetBundle.hpp"
#include "libslic3r/Print.hpp"
#include "libslic3r/PrintConfig.hpp"
#include "libslic3r/Utils.hpp"

namespace fs = boost::filesystem;
using namespace Slic3r;

namespace slicer {

struct Engine::Impl {
    fs::path                      profiles_dir;
    AppConfig                     app_config;
    std::unique_ptr<PresetBundle> bundle;
    Model                         model;
    Print                         print;

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

    // Sem PartPlate: os objetos ficam apoiados na mesa e centralizados nela.
    void place_on_bed()
    {
        if (!bundle || model.objects.empty())
            return;
        for (ModelObject *object : model.objects)
            object->ensure_on_bed();
        const auto *area = bundle->printers.get_edited_preset().config.opt<ConfigOptionPoints>("printable_area");
        if (area != nullptr && !area->values.empty())
            model.center_instances_around_point(BoundingBoxf(area->values).center());
    }
};

Engine::Engine(const std::string &resources_dir, const std::string &data_dir) : m(new Impl)
{
    boost::log::core::get()->set_filter(boost::log::trivial::severity >= boost::log::trivial::warning);
    // Absolutos: load_vendor() cria symlinks para os perfis.
    set_resources_dir(fs::absolute(resources_dir).string());
    set_data_dir(fs::absolute(data_dir).string());
    m->profiles_dir = fs::path(Slic3r::resources_dir()) / "profiles";
    PresetBundle().setup_directories();
}

Engine::~Engine() = default;

std::vector<std::string> Engine::vendors() const
{
    std::vector<std::string> out;
    for (const fs::directory_entry &entry : fs::directory_iterator(m->profiles_dir)) {
        const fs::path &path = entry.path();
        if (path.extension() == ".json" && fs::is_directory(path.parent_path() / path.stem()) &&
            path.stem() != PresetBundle::ORCA_FILAMENT_LIBRARY)
            out.push_back(path.stem().string());
    }
    std::sort(out.begin(), out.end());
    return out;
}

void Engine::load_vendor(const std::string &vendor)
{
    if (!fs::exists(m->profiles_dir / (vendor + ".json")))
        throw std::invalid_argument("unknown vendor: " + vendor);

    // PresetBundle carrega todo fabricante presente em <data_dir>/system. Como na
    // GUI do Orca, só entram os "instalados": aqui, o escolhido e a biblioteca de
    // filamentos genéricos (da qual os filamentos dos fabricantes herdam).
    // Carregar os 130+ fabricantes de uma vez custaria centenas de MB de RAM.
    const fs::path system_dir = fs::path(data_dir()) / PRESET_SYSTEM_DIR;
    fs::remove_all(system_dir);
    fs::create_directories(system_dir);
    for (const std::string &name : {std::string(PresetBundle::ORCA_FILAMENT_LIBRARY), vendor}) {
        fs::create_symlink(m->profiles_dir / (name + ".json"), system_dir / (name + ".json"));
        fs::create_directory_symlink(m->profiles_dir / name, system_dir / name);
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

void Engine::load_model(const std::string &path)
{
    DynamicPrintConfig        config;
    ConfigSubstitutionContext substitutions(ForwardCompatibilitySubstitutionRule::EnableSilent);
    Model model = Model::read_from_file(path, &config, &substitutions,
                                        LoadStrategy::LoadModel | LoadStrategy::AddDefaultInstances);
    if (model.objects.empty())
        throw std::runtime_error("no objects in " + path);
    m->model = std::move(model);
    m->place_on_bed();
}

std::vector<ObjectMesh> Engine::objects() const
{
    std::vector<ObjectMesh> out;
    for (const ModelObject *object : m->model.objects) {
        const indexed_triangle_set its = object->raw_indexed_triangle_set();
        ObjectMesh mesh;
        mesh.positions.reserve(its.vertices.size() * 3);
        for (const stl_vertex &v : its.vertices)
            mesh.positions.insert(mesh.positions.end(), {v.x(), v.y(), v.z()});
        mesh.indices.reserve(its.indices.size() * 3);
        for (const stl_triangle_vertex_indices &t : its.indices)
            mesh.indices.insert(mesh.indices.end(), {uint32_t(t[0]), uint32_t(t[1]), uint32_t(t[2])});
        const Eigen::Matrix4f matrix = object->instances.front()->get_matrix().matrix().cast<float>();
        std::copy(matrix.data(), matrix.data() + 16, mesh.transform.begin());
        out.push_back(std::move(mesh));
    }
    return out;
}

SliceStats Engine::slice(const std::string &gcode_path, const ProgressFn &progress)
{
    if (!m->bundle)
        throw std::runtime_error("no vendor loaded");
    if (m->model.objects.empty())
        throw std::runtime_error("no model loaded");

    DynamicPrintConfig config = m->bundle->full_config();

    Print &print = m->print;
    // Igual à GUI (Plater): firmware Bambu pula validações/ajustes feitos para Marlin.
    print.is_BBL_printer() = m->bundle->is_bbl_vendor();
    print.set_status_callback([&progress](const PrintBase::SlicingStatus &s) {
        if (progress)
            progress(s.percent, s.text);
    });
    print.apply(m->model, config);
    print.restart();

    StringObjectException warning;
    StringObjectException err = print.validate(&warning);
    if (!err.string.empty())
        throw std::runtime_error(err.string);

    GCodeProcessorResult result;
    try {
        print.process();
        // GCode::do_export cria o diretório pai; com caminho relativo ele seria vazio.
        print.export_gcode(fs::absolute(gcode_path).string(), &result, nullptr);
    } catch (const CanceledException &) {
        throw Canceled();
    }
    if (print.canceled())
        throw Canceled();

    SliceStats stats;
    stats.print_time_s = result.print_statistics.modes.front().time;
    for (const auto &kv : result.print_statistics.total_volumes_per_extruder)
        stats.filament_mm3 += kv.second;
    return stats;
}

void Engine::cancel()
{
    m->print.cancel();
}

} // namespace slicer
