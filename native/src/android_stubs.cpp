// Stubs for libslic3r entry points whose implementations need OpenCASCADE,
// Draco or OpenCV. Those libraries are not built for Android (see
// docs/arquitetura.md §2.3); the formats they back (STEP, SVG extrusion,
// Draco, 3D text) are out of scope for the app. Each stub fails cleanly.

#include <string>
#include <vector>
#include <map>

#include "libslic3r/Model.hpp"
#include "libslic3r/TriangleMesh.hpp"
#include "libslic3r/Format/DRC.hpp"
#include "libslic3r/Format/svg.hpp"
#include "libslic3r/Shape/TextShape.hpp"

namespace Slic3r {

bool load_svg(const char * /*path*/, Model * /*model*/, std::string &message)
{
    message = "SVG import is not supported in this build.";
    return false;
}

bool load_drc(const char * /*path*/, TriangleMesh * /*meshptr*/) { return false; }
bool load_drc(const char * /*path*/, Model * /*model*/, const char * /*object_name*/) { return false; }
bool store_drc(const char * /*path*/, TriangleMesh * /*mesh*/, int /*bits*/, int /*speed*/) { return false; }
bool store_drc(const char * /*path*/, ModelObject * /*model_object*/, int /*bits*/, int /*speed*/) { return false; }
bool store_drc(const char * /*path*/, Model * /*model*/, int /*bits*/, int /*speed*/) { return false; }

std::vector<std::string> init_occt_fonts() { return {}; }
std::map<std::string, std::string> get_occt_fonts_maps() { return {}; }
void load_text_shape(const char * /*text*/, const char * /*font*/, const float /*text_height*/, const float /*thickness*/,
                     bool /*is_bold*/, bool /*is_italic*/, TextResult &text_result)
{
    text_result.text_mesh  = TriangleMesh();
    text_result.text_width = 0.0;
}

} // namespace Slic3r
