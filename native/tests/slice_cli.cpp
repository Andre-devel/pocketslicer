// Fase 0 — prova de conceito: fatia um modelo com libslic3r no Android.
//
//   slice_cli <profiles_dir> <machine.json> <process.json> <filament.json> <model> <out.gcode>
//
// Os JSONs são os perfis do OrcaSlicer (resources/profiles/<Vendor>/...). A
// cadeia "inherits" é resolvida procurando o pai pelo nome no mesmo diretório.
// Não usa PresetBundle nem PartPlate: um único objeto, uma única mesa.

#include <cstdio>
#include <iostream>
#include <string>
#include <vector>

#include <boost/filesystem.hpp>
#include <boost/log/core.hpp>
#include <boost/log/trivial.hpp>
#include <boost/log/expressions.hpp>

#include "libslic3r/libslic3r.h"
#include "libslic3r/Config.hpp"
#include "libslic3r/PrintConfig.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/Print.hpp"
#include "libslic3r/GCode/GCodeProcessor.hpp"
#include "libslic3r/Utils.hpp"

namespace fs = boost::filesystem;
using namespace Slic3r;

static void load_with_inherits(const fs::path &file, DynamicPrintConfig &config)
{
    std::map<std::string, std::string> key_values;
    std::string reason;
    DynamicPrintConfig own;
    ConfigSubstitutions subst = own.load_from_json(file.string(), ForwardCompatibilitySubstitutionRule::Enable, key_values, reason);
    if (!reason.empty())
        throw std::runtime_error("failed to load " + file.string() + ": " + reason);

    auto inherits = key_values.find("inherits");
    if (inherits != key_values.end() && !inherits->second.empty()) {
        fs::path parent = file.parent_path() / (inherits->second + ".json");
        if (!fs::exists(parent))
            throw std::runtime_error("parent profile not found: " + parent.string());
        load_with_inherits(parent, config);
    }
    config.apply(own, true);
}

int main(int argc, char **argv)
{
    if (argc != 7) {
        std::fprintf(stderr, "usage: %s <profiles_dir> <machine.json> <process.json> <filament.json> <model> <out.gcode>\n", argv[0]);
        return 2;
    }
    const std::string profiles_dir = argv[1];
    const fs::path machine = argv[2], process = argv[3], filament = argv[4];
    const std::string model_path = argv[5];
    // GCode::do_export cria o diretório pai da saída; com caminho relativo ele é vazio.
    const std::string out_path = fs::absolute(argv[6]).string();

    boost::log::core::get()->set_filter(boost::log::trivial::severity >= boost::log::trivial::warning);
    set_resources_dir(profiles_dir + "/..");
    // No Android não há /tmp; o diretório de dados fica junto da saída.
    set_data_dir(fs::path(out_path).parent_path().string());

    try {
        DynamicPrintConfig config;
        config.apply(FullPrintConfig::defaults());
        load_with_inherits(machine, config);
        load_with_inherits(process, config);
        load_with_inherits(filament, config);
        config.normalize_fdm();

        std::map<std::string, std::string> invalid = config.validate(true);
        for (auto &kv : invalid)
            std::fprintf(stderr, "config warning: %s: %s\n", kv.first.c_str(), kv.second.c_str());

        Model model = Model::read_from_file(model_path, nullptr, nullptr, LoadStrategy::AddDefaultInstances);
        for (ModelObject *o : model.objects)
            o->ensure_on_bed();
        model.center_instances_around_point(Vec2d(
            config.opt<ConfigOptionPoints>("printable_area")->values.empty() ? 100.0 : BoundingBoxf(config.opt<ConfigOptionPoints>("printable_area")->values).center().x(),
            config.opt<ConfigOptionPoints>("printable_area")->values.empty() ? 100.0 : BoundingBoxf(config.opt<ConfigOptionPoints>("printable_area")->values).center().y()));

        Print print;
        // Igual ao CLI do Orca (OrcaSlicer.cpp): impressoras Bambu têm firmware
        // próprio e pulam validações/ajustes feitos para Marlin.
        print.is_BBL_printer() = config.opt_string("printer_model").rfind("Bambu Lab", 0) == 0;
        print.set_status_callback([](const PrintBase::SlicingStatus &s) {
            std::fprintf(stderr, "[%3d%%] %s\n", s.percent, s.text.c_str());
        });
        print.apply(model, config);
        StringObjectException warning;
        StringObjectException err = print.validate(&warning);
        if (!err.string.empty()) {
            std::fprintf(stderr, "validate: %s\n", err.string.c_str());
            return 1;
        }
        print.process();
        GCodeProcessorResult result;
        std::string written = print.export_gcode(out_path, &result, nullptr);
        double volume_mm3 = 0.0;
        for (const auto &kv : result.print_statistics.total_volumes_per_extruder)
            volume_mm3 += kv.second;
        std::printf("gcode: %s\nestimated time: %.0f s\nfilament: %.2f cm3\n",
                    written.c_str(),
                    result.print_statistics.modes.front().time,
                    volume_mm3 / 1000.0);
        return 0;
    } catch (const std::exception &e) {
        std::fprintf(stderr, "error: %s\n", e.what());
        return 1;
    }
}
