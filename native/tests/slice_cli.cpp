// Fatia um modelo no Android via adb shell, pela mesma fachada (engine.hpp) que o app usa.
//
//   slice_cli <resources_dir> <vendor> <printer> <process> <filament> <model> <out.gcode>
//   slice_cli <resources_dir> --list <vendor>
//
// resources_dir é uma cópia de resources/ do OrcaSlicer (profiles/, info/, flush/).
// Os perfis são nomes de preset do fabricante (ex.: "Bambu Lab A1 0.4 nozzle").
// O diretório de dados (presets carregados) fica em ./data.

#include <cstdio>
#include <string>

#include "engine.hpp"

using slicer::Engine;
using slicer::PresetKind;

static void list(Engine &engine, const std::string &vendor)
{
    engine.load_vendor(vendor);
    for (const std::string &printer : engine.presets(PresetKind::Printer)) {
        engine.select(PresetKind::Printer, printer);
        std::printf("printer: %s\n", printer.c_str());
        for (const std::string &p : engine.presets(PresetKind::Process))
            std::printf("  process: %s%s\n", p.c_str(), p == engine.selected(PresetKind::Process) ? " *" : "");
        for (const std::string &f : engine.presets(PresetKind::Filament))
            std::printf("  filament: %s%s\n", f.c_str(), f == engine.selected(PresetKind::Filament) ? " *" : "");
    }
}

int main(int argc, char **argv)
{
    const bool listing = argc == 4 && std::string(argv[2]) == "--list";
    if (argc != 8 && !listing) {
        std::fprintf(stderr,
                     "usage: %s <resources_dir> <vendor> <printer> <process> <filament> <model> <out.gcode>\n"
                     "       %s <resources_dir> --list <vendor>\n",
                     argv[0], argv[0]);
        return 2;
    }

    try {
        Engine engine(argv[1], "data");
        if (listing) {
            list(engine, argv[3]);
            return 0;
        }
        engine.load_vendor(argv[2]);
        engine.select(PresetKind::Printer, argv[3]);
        engine.select(PresetKind::Process, argv[4]);
        engine.select(PresetKind::Filament, argv[5]);
        engine.load_model(argv[6]);
        slicer::SliceStats stats = engine.slice(argv[7], [](int percent, const std::string &text) {
            std::fprintf(stderr, "[%3d%%] %s\n", percent, text.c_str());
        });
        std::printf("gcode: %s\nestimated time: %.0f s\nfilament: %.2f cm3\n",
                    argv[7], stats.print_time_s, stats.filament_mm3 / 1000.0);
        return 0;
    } catch (const std::exception &e) {
        std::fprintf(stderr, "error: %s\n", e.what());
        return 1;
    }
}
