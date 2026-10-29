// Fatia um modelo no Android via adb shell, pela mesma fachada (engine.hpp) que o app usa.
//
//   slice_cli <resources_dir> <vendor> <printer> <process> <filament> <model> <out.gcode>
//   slice_cli <resources_dir> --list <vendor>
//   slice_cli <resources_dir> --project <project.3mf> <out.gcode> [plate]
//
// resources_dir é uma cópia de resources/ do OrcaSlicer (profiles/, info/, flush/).
// Os perfis são nomes de preset do fabricante (ex.: "Bambu Lab A1 0.4 nozzle").
// plate começa em 1 (padrão: a primeira com objetos).
// Com out terminado em .gcode.3mf, grava também o .gcode.3mf (sem miniatura) e o G-code
// fica ao lado, sem o ".3mf".
// O diretório de dados (presets carregados) fica em ./data.

#include <cstdio>
#include <cstdlib>
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
    const bool project = (argc == 5 || argc == 6) && std::string(argv[2]) == "--project";
    if (argc != 8 && !listing && !project) {
        std::fprintf(stderr,
                     "usage: %s <resources_dir> <vendor> <printer> <process> <filament> <model> <out.gcode>\n"
                     "       %s <resources_dir> --list <vendor>\n"
                     "       %s <resources_dir> --project <project.3mf> <out.gcode> [plate]\n",
                     argv[0], argv[0], argv[0]);
        return 2;
    }

    try {
        Engine engine(argv[1], "data");
        if (listing) {
            list(engine, argv[3]);
            return 0;
        }
        const std::string out = argv[project ? 4 : 7];
        const std::string suffix = ".3mf";
        const bool gcode_3mf = out.size() > suffix.size() && out.compare(out.size() - suffix.size(), suffix.size(), suffix) == 0;
        const std::string gcode_path = gcode_3mf ? out.substr(0, out.size() - suffix.size()) : out;
        const char *gcode = gcode_path.c_str();
        if (project) {
            std::printf("vendor: %s\n", engine.open_project(argv[3]).c_str());
            std::printf("plates: %zu\n", engine.project_plates().size());
            if (argc == 6)
                engine.select_plate(std::strtoul(argv[5], nullptr, 10) - 1);
            for (const slicer::ObjectMesh &mesh : engine.objects())
                std::printf("object mesh: %zu vertices, %zu triangles\n", mesh.positions.size() / 3, mesh.indices.size() / 3);
            for (const slicer::Placement &p : engine.placements())
                std::printf("object at %.1f %.1f %.1f%s\n", p.offset[0], p.offset[1], p.offset[2], p.outside ? " (outside)" : "");
        } else {
            engine.load_vendor(argv[2]);
            engine.select(PresetKind::Printer, argv[3]);
            engine.select(PresetKind::Process, argv[4]);
            engine.select(PresetKind::Filament, argv[5]);
            engine.load_model(argv[6]);
        }
        slicer::SliceStats stats = engine.slice(gcode, [](int percent, const std::string &text) {
            std::fprintf(stderr, "[%3d%%] %s\n", percent, text.c_str());
        });
        std::printf("gcode: %s\nestimated time: %.0f s\nfilament: %.2f cm3\n",
                    gcode, stats.print_time_s, stats.filament_mm3 / 1000.0);
        std::printf("printer wants .gcode.3mf: %s\n", engine.gcode_3mf() ? "yes" : "no");
        if (gcode_3mf) {
            engine.export_gcode_3mf(gcode_path, out, {});
            std::printf("gcode.3mf: %s\n", out.c_str());
        }
        return 0;
    } catch (const std::exception &e) {
        std::fprintf(stderr, "error: %s\n", e.what());
        return 1;
    }
}
