// Stubs for libslic3r entry points left out of the Android build: SVG
// extrusion and 3D text (OCCT fonts) and the OBJ color clustering (OpenCV,
// not built for Android; see docs/arquitetura.md §2.3). They are out of
// scope for the app. Each stub fails cleanly.
// At the end, one GUI-side helper that libslic3r calls.

#include <string>
#include <vector>
#include <map>

#include "libslic3r/Model.hpp"
#include "libslic3r/TriangleMesh.hpp"
#include "libslic3r/Format/svg.hpp"
#include "libslic3r/Shape/TextShape.hpp"

namespace Slic3r {

bool load_svg(const char * /*path*/, Model * /*model*/, std::string &message)
{
    message = "SVG import is not supported in this build.";
    return false;
}

std::vector<std::string> init_occt_fonts() { return {}; }
std::map<std::string, std::string> get_occt_fonts_maps() { return {}; }
void load_text_shape(const char * /*text*/, const char * /*font*/, const float /*text_height*/, const float /*thickness*/,
                     bool /*is_bold*/, bool /*is_italic*/, TextResult &text_result)
{
    text_result.text_mesh  = TriangleMesh();
    text_result.text_width = 0.0;
}

} // namespace Slic3r

// libslic3r/FlushVolCalc.cpp calls RGB2HSV() from slic3r/Utils/ColorSpaceConvert.cpp, which
// is left out of the build because it needs wxWidgets. Same implementation as there.
#include <algorithm>
#include <cmath>

#include "slic3r/Utils/ColorSpaceConvert.hpp"

void RGB2HSV(float r, float g, float b, float *h, float *s, float *v)
{
    float Cmax  = std::max(std::max(r, g), b);
    float Cmin  = std::min(std::min(r, g), b);
    float delta = Cmax - Cmin;

    if (std::abs(delta) < 0.001)
        *h = 0.f;
    else if (Cmax == r)
        *h = 60.f * fmod((g - b) / delta, 6.f);
    else if (Cmax == g)
        *h = 60.f * ((b - r) / delta + 2);
    else
        *h = 60.f * ((r - g) / delta + 4);

    *s = std::abs(Cmax) < 0.001 ? 0.f : delta / Cmax;
    *v = Cmax;
}
