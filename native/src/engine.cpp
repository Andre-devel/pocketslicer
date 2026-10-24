#include "engine.hpp"

#include <algorithm>
#include <atomic>
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
#include "libslic3r/Model.hpp"
#include "libslic3r/PresetBundle.hpp"
#include "libslic3r/Print.hpp"
#include "libslic3r/PrintConfig.hpp"
#include "libslic3r/Utils.hpp"

#include "nlohmann/json.hpp"

namespace fs = boost::filesystem;
using namespace Slic3r;

namespace slicer {

struct Engine::Impl {
    fs::path                      profiles_dir;
    AppConfig                     app_config;
    std::unique_ptr<PresetBundle> bundle;
    std::string                   vendor; // de bundle
    Model                         model;
    Print                         print;
    GCodeProcessorResult          result; // do último slice()

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

SliceStats stats_of(const GCodeProcessorResult &result)
{
    SliceStats stats;
    stats.print_time_s = result.print_statistics.modes.front().time;
    for (const auto &kv : result.print_statistics.total_volumes_per_extruder)
        stats.filament_mm3 += kv.second;
    for (const auto &[role, used] : result.print_statistics.used_filaments_per_role) {
        stats.filament_m += used.first;
        stats.filament_g += used.second;
    }
    if (!result.filament_costs.empty())
        stats.cost = result.filament_costs.front() * stats.filament_g / 1000.0;
    return stats;
}

// Fabricante (em resources/profiles) que declara uma das impressoras.
std::string vendor_of(const fs::path &profiles_dir, const std::set<std::string> &printers)
{
    for (const fs::directory_entry &entry : fs::directory_iterator(profiles_dir)) {
        const fs::path &path = entry.path();
        if (path.extension() != ".json" || !fs::is_directory(path.parent_path() / path.stem()))
            continue;
        boost::nowide::ifstream in(path.string());
        const nlohmann::json index = nlohmann::json::parse(in, nullptr, false);
        if (!index.is_object() || !index.contains("machine_list"))
            continue;
        for (const nlohmann::json &machine : index["machine_list"])
            if (printers.count(machine.value("name", "")) > 0)
                return path.stem().string();
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
        throw std::runtime_error("no objects in " + path);
    // Como a GUI do Orca: a origem de cada objeto passa a ser o centro da sua malha,
    // então a posição exibida é a do centro e girar/escalar usa esse pivô.
    for (ModelObject *object : model.objects)
        object->center_around_origin();
    return model;
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

void Engine::load_model(const std::string &path)
{
    // Lê antes de limpar: se o arquivo for inválido, a mesa continua como estava.
    Model model = read_model(path);
    m->model.clear_objects();
    m->model.clear_materials();
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
    // Instâncias (objeto, instância) da primeira placa; vazio = todas.
    std::set<std::pair<int, int>> first_plate;
    if (plates.size() > 1)
        first_plate.insert(plates.front()->objects_and_instances.begin(), plates.front()->objects_and_instances.end());
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
    std::string vendor = vendor_of(m->profiles_dir, printers);
    if (vendor.empty())
        vendor = m->vendor;
    if (vendor.empty())
        throw std::runtime_error("unknown printer: " + config.opt_string("printer_settings_id"));
    if (vendor != m->vendor)
        load_vendor(vendor);
    // Como Plater::load_files(): presets embutidos, depois a configuração do projeto.
    if (!project_presets.empty())
        m->bundle->load_project_embedded_presets(project_presets, ForwardCompatibilitySubstitutionRule::EnableSilent);
    m->bundle->load_config_model(path, std::move(config), version);
    for (Preset &filament : m->bundle->filaments)
        filament.is_visible = true;

    // Uma instância por objeto, nas posições do arquivo.
    m->model.clear_objects();
    m->model.clear_materials();
    for (size_t i = 0; i < model.objects.size(); ++i) {
        const ModelObject *source = model.objects[i];
        for (size_t j = 0; j < source->instances.size(); ++j) {
            if (!first_plate.empty() && first_plate.count({int(i), int(j)}) == 0)
                continue;
            ModelObject *added = m->model.add_object(*source);
            const Transform3d transform = source->instances[j]->get_matrix();
            while (added->instances.size() > 1)
                added->delete_instance(added->instances.size() - 1);
            added->instances.front()->set_transformation(Geometry::Transformation(transform));
        }
    }
    if (m->model.objects.empty())
        throw std::runtime_error("no objects in " + path);
    return m->vendor;
}

void Engine::save_project(const std::string &path, const Thumbnail &thumbnail)
{
    if (!m->bundle)
        throw std::runtime_error("no vendor loaded");
    DynamicPrintConfig config = m->bundle->full_config_secure();

    ThumbnailData image;
    if (thumbnail.width > 0 && thumbnail.rgba.size() == size_t(thumbnail.width) * thumbnail.height * 4) {
        image.set(thumbnail.width, thumbnail.height);
        image.pixels = thumbnail.rgba;
    }
    // Uma placa com todos os objetos, como PartPlateList::store_to_3mf_structure().
    PlateData plate;
    plate.plate_index = 0;
    for (size_t i = 0; i < m->model.objects.size(); ++i)
        plate.objects_and_instances.emplace_back(int(i), 0);
    plate.printer_model_id = m->bundle->printers.get_edited_preset().get_printer_type(m->bundle.get());
    if (const ConfigOption *nozzle = config.option("nozzle_diameter"))
        plate.nozzle_diameters = nozzle->serialize();

    StoreParams params;
    const std::string absolute = fs::absolute(path).string();
    params.path = absolute.c_str();
    params.model = &m->model;
    params.plate_data_list = {&plate};
    params.project_presets = m->bundle->get_current_project_embedded_presets();
    params.config = &config;
    if (image.is_valid())
        params.thumbnail_data = {&image};
    params.strategy = SaveStrategy::Zip64;
    if (!store_bbs_3mf(params))
        throw std::runtime_error("could not write " + path);
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
        const indexed_triangle_set its = object->raw_indexed_triangle_set();
        ObjectMesh mesh;
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
    const indexed_triangle_set its = o.raw_indexed_triangle_set();
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
            {"label", def->label},
            {"category", def->category},
            {"tooltip", def->tooltip},
            {"sidetext", def->sidetext},
            {"type", def->type == coPoints ? "points" : type_name(def->type)},
            {"vector", (int(def->type) & int(coVectorType)) != 0},
            {"mode", int(def->mode)},
            {"multiline", def->multiline || def->is_code},
            {"enum_values", def->enum_values},
            {"enum_labels", def->enum_labels},
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
    return nlohmann::json{{"values", values}, {"dirty", collection.current_dirty_options()}}.dump();
}

void Engine::set_option(PresetKind kind, const std::string &key, const std::string &value)
{
    const std::vector<std::string> &keys = option_keys(kind);
    if (editable_def(key) == nullptr || std::find(keys.begin(), keys.end(), key) == keys.end())
        throw std::invalid_argument("not an editable option: " + key);
    PresetCollection &collection = m->collection(kind);
    // Sobre uma cópia: um valor inválido não deixa o preset pela metade.
    DynamicPrintConfig config = collection.get_edited_preset().config;
    config.set_deserialize_strict(key, value);
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
        throw std::invalid_argument("empty preset name");
    PresetCollection &collection = m->collection(kind);
    if (const Preset *existing = collection.find_preset(name, false); existing != nullptr && !existing->is_user())
        throw std::invalid_argument("system preset cannot be overwritten: " + name);
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
        throw std::runtime_error("cannot delete preset: " + name);
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
        throw std::runtime_error("no compatible preset in " + path);
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
            progress(s.percent, s.text);
    });
    print.apply(m->model, config);
    print.restart();

    StringObjectException warning;
    StringObjectException err = print.validate(&warning);
    if (!err.string.empty())
        throw std::runtime_error(err.string);

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
