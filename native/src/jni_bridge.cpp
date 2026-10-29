// JNI de io.github.andredevel.slicer.core.SlicerEngine (funções nativas de
// SlicerEngine.kt, classe JVM SlicerEngineKt) sobre a fachada engine.hpp.
//
// O handle Java é um ponteiro para slicer::Engine. Exceções C++ viram
// io.github.andredevel.slicer.core.SlicerException; slicer::Canceled vira
// java.util.concurrent.CancellationException.

#include <jni.h>

#include <array>
#include <iterator>
#include <new>
#include <string>
#include <thread>
#include <vector>

#include "engine.hpp"
#include "i18n.hpp"

using slicer::Engine;
using slicer::PresetKind;

namespace {

std::string to_string(JNIEnv *env, jstring s)
{
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

jobjectArray to_array(JNIEnv *env, const std::vector<std::string> &values)
{
    jobjectArray out = env->NewObjectArray(jsize(values.size()), env->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < values.size(); ++i) {
        jstring s = env->NewStringUTF(values[i].c_str());
        env->SetObjectArrayElement(out, jsize(i), s);
        env->DeleteLocalRef(s);
    }
    return out;
}

jfloatArray to_float_array(JNIEnv *env, const std::vector<jfloat> &values)
{
    jfloatArray out = env->NewFloatArray(jsize(values.size()));
    env->SetFloatArrayRegion(out, 0, jsize(values.size()), values.data());
    return out;
}

jdoubleArray to_array(JNIEnv *env, const slicer::SliceStats &stats)
{
    const jdouble values[] = {stats.print_time_s, stats.filament_mm3, stats.filament_m, stats.filament_g, stats.cost};
    jdoubleArray out = env->NewDoubleArray(jsize(std::size(values)));
    env->SetDoubleArrayRegion(out, 0, jsize(std::size(values)), values);
    return out;
}

// sizes: largura e altura intercaladas, uma dupla por imagem de pixels (RGBA).
std::vector<slicer::Thumbnail> to_thumbnails(JNIEnv *env, jintArray sizes, jobjectArray pixels)
{
    std::vector<slicer::Thumbnail> out(size_t(env->GetArrayLength(pixels)));
    std::vector<jint> dims(size_t(env->GetArrayLength(sizes)));
    env->GetIntArrayRegion(sizes, 0, jsize(dims.size()), dims.data());
    for (size_t i = 0; i < out.size() && i * 2 + 1 < dims.size(); ++i) {
        auto rgba = static_cast<jbyteArray>(env->GetObjectArrayElement(pixels, jsize(i)));
        out[i].width = unsigned(dims[i * 2]);
        out[i].height = unsigned(dims[i * 2 + 1]);
        out[i].rgba.resize(size_t(env->GetArrayLength(rgba)));
        env->GetByteArrayRegion(rgba, 0, jsize(out[i].rgba.size()), reinterpret_cast<jbyte *>(out[i].rgba.data()));
        env->DeleteLocalRef(rgba);
    }
    return out;
}

Engine *engine(jlong handle) { return reinterpret_cast<Engine *>(handle); }

void throw_slicer_exception(JNIEnv *env, const std::string &message)
{
    env->ThrowNew(env->FindClass("io/github/andredevel/slicer/core/SlicerException"), message.c_str());
}

// Executa f convertendo exceções C++ em exceções Java pendentes. As mensagens vão
// traduzidas: muitos erros do libslic3r (Print::validate...) só marcam o texto com L().
template<typename F>
auto guarded(JNIEnv *env, F &&f) -> decltype(f())
{
    try {
        return f();
    } catch (const slicer::Canceled &e) {
        env->ThrowNew(env->FindClass("java/util/concurrent/CancellationException"), e.what());
    } catch (const std::bad_alloc &) {
        throw_slicer_exception(env, slicer::i18n::translate("Not enough memory for this operation."));
    } catch (const std::exception &e) {
        throw_slicer_exception(env, slicer::i18n::translate(e.what()));
    } catch (...) {
        throw_slicer_exception(env, slicer::i18n::translate("Unknown error."));
    }
    return decltype(f())();
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativeCreate(JNIEnv *env, jclass, jstring resources_dir, jstring data_dir)
{
    return guarded(env, [&] {
        return reinterpret_cast<jlong>(new Engine(to_string(env, resources_dir), to_string(env, data_dir)));
    });
}

JNIEXPORT void JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativeSetLanguage(JNIEnv *env, jclass, jlong handle, jstring language)
{
    guarded(env, [&] { engine(handle)->set_language(to_string(env, language)); });
}

JNIEXPORT jstring JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativeTranslate(JNIEnv *env, jclass, jstring text)
{
    return guarded(env, [&] { return env->NewStringUTF(slicer::i18n::translate(to_string(env, text)).c_str()); });
}

JNIEXPORT void JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativeDestroy(JNIEnv *, jclass, jlong handle)
{
    delete engine(handle);
}

JNIEXPORT jobjectArray JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativeVendors(JNIEnv *env, jclass, jlong handle)
{
    return guarded(env, [&] { return to_array(env, engine(handle)->vendors()); });
}

JNIEXPORT void JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativeLoadVendor(JNIEnv *env, jclass, jlong handle, jstring vendor)
{
    guarded(env, [&] { engine(handle)->load_vendor(to_string(env, vendor)); });
}

JNIEXPORT jobjectArray JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativePresets(JNIEnv *env, jclass, jlong handle, jint kind)
{
    return guarded(env, [&] { return to_array(env, engine(handle)->presets(PresetKind(kind))); });
}

JNIEXPORT jstring JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativeSelected(JNIEnv *env, jclass, jlong handle, jint kind)
{
    return guarded(env, [&] { return env->NewStringUTF(engine(handle)->selected(PresetKind(kind)).c_str()); });
}

JNIEXPORT void JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativeSelect(JNIEnv *env, jclass, jlong handle, jint kind, jstring name)
{
    guarded(env, [&] { engine(handle)->select(PresetKind(kind), to_string(env, name)); });
}

JNIEXPORT jstring JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativeOptionsJson(JNIEnv *env, jclass, jlong handle, jint kind)
{
    return guarded(env, [&] { return env->NewStringUTF(engine(handle)->options_json(PresetKind(kind)).c_str()); });
}

JNIEXPORT jstring JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativeValuesJson(JNIEnv *env, jclass, jlong handle, jint kind)
{
    return guarded(env, [&] { return env->NewStringUTF(engine(handle)->values_json(PresetKind(kind)).c_str()); });
}

JNIEXPORT void JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativeSetOption(JNIEnv *env, jclass, jlong handle, jint kind, jstring key, jstring value)
{
    guarded(env, [&] { engine(handle)->set_option(PresetKind(kind), to_string(env, key), to_string(env, value)); });
}

JNIEXPORT void JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativeDiscardChanges(JNIEnv *env, jclass, jlong handle, jint kind)
{
    guarded(env, [&] { engine(handle)->discard_changes(PresetKind(kind)); });
}

JNIEXPORT jobjectArray JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativeUserPresets(JNIEnv *env, jclass, jlong handle, jint kind)
{
    return guarded(env, [&] { return to_array(env, engine(handle)->user_presets(PresetKind(kind))); });
}

JNIEXPORT void JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativeSavePreset(JNIEnv *env, jclass, jlong handle, jint kind, jstring name)
{
    guarded(env, [&] { engine(handle)->save_preset(PresetKind(kind), to_string(env, name)); });
}

JNIEXPORT void JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativeDeletePreset(JNIEnv *env, jclass, jlong handle, jint kind, jstring name)
{
    guarded(env, [&] { engine(handle)->delete_preset(PresetKind(kind), to_string(env, name)); });
}

JNIEXPORT jobjectArray JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativeImportPreset(JNIEnv *env, jclass, jlong handle, jstring path)
{
    return guarded(env, [&] { return to_array(env, engine(handle)->import_preset(to_string(env, path))); });
}

JNIEXPORT void JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativeExportPreset(JNIEnv *env, jclass, jlong handle, jint kind, jstring path)
{
    guarded(env, [&] { engine(handle)->export_preset(PresetKind(kind), to_string(env, path)); });
}

JNIEXPORT void JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativeLoadModel(JNIEnv *env, jclass, jlong handle, jstring path)
{
    guarded(env, [&] { engine(handle)->load_model(to_string(env, path)); });
}

// Bed(shape: FloatArray com x, y intercalados, maxHeight: Float).
JNIEXPORT jobject JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativeBed(JNIEnv *env, jclass, jlong handle)
{
    return guarded(env, [&]() -> jobject {
        const slicer::Bed bed = engine(handle)->bed();
        std::vector<jfloat> shape;
        for (const auto &p : bed.shape)
            shape.insert(shape.end(), {jfloat(p[0]), jfloat(p[1])});
        jclass cls = env->FindClass("io/github/andredevel/slicer/core/Bed");
        jmethodID init = env->GetMethodID(cls, "<init>", "([FF)V");
        return env->NewObject(cls, init, to_float_array(env, shape), jfloat(bed.max_height));
    });
}

JNIEXPORT void JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativeAddModel(JNIEnv *env, jclass, jlong handle, jstring path)
{
    guarded(env, [&] { engine(handle)->add_model(to_string(env, path)); });
}

JNIEXPORT jstring JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativeOpenProject(JNIEnv *env, jclass, jlong handle, jstring path)
{
    return guarded(env, [&] { return env->NewStringUTF(engine(handle)->open_project(to_string(env, path)).c_str()); });
}

// Objetos por placa.
JNIEXPORT jintArray JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativeProjectPlates(JNIEnv *env, jclass, jlong handle)
{
    return guarded(env, [&] {
        const std::vector<size_t> plates = engine(handle)->project_plates();
        const std::vector<jint>   values(plates.begin(), plates.end());
        jintArray out = env->NewIntArray(jsize(values.size()));
        env->SetIntArrayRegion(out, 0, jsize(values.size()), values.data());
        return out;
    });
}

JNIEXPORT jint JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativeProjectPlate(JNIEnv *env, jclass, jlong handle)
{
    return guarded(env, [&] { return jint(engine(handle)->project_plate()); });
}

JNIEXPORT void JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativeSelectPlate(JNIEnv *env, jclass, jlong handle, jint plate)
{
    guarded(env, [&] { engine(handle)->select_plate(size_t(plate)); });
}

// Miniatura como em to_thumbnails(); sem imagens = sem miniatura.
JNIEXPORT void JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativeSaveProject(JNIEnv *env, jclass, jlong handle, jstring path,
                                                                     jintArray thumbnail_size, jobjectArray thumbnail)
{
    guarded(env, [&] {
        const std::vector<slicer::Thumbnail> images = to_thumbnails(env, thumbnail_size, thumbnail);
        engine(handle)->save_project(to_string(env, path), images.empty() ? slicer::Thumbnail() : images.front());
    });
}

JNIEXPORT jboolean JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativeGcode3mf(JNIEnv *env, jclass, jlong handle)
{
    return guarded(env, [&] { return jboolean(engine(handle)->gcode_3mf()); });
}

// Miniatura como em nativeSaveProject().
JNIEXPORT void JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativeExportGcode3mf(JNIEnv *env, jclass, jlong handle, jstring gcode_path,
                                                                        jstring path, jintArray thumbnail_size, jobjectArray thumbnail)
{
    guarded(env, [&] {
        const std::vector<slicer::Thumbnail> images = to_thumbnails(env, thumbnail_size, thumbnail);
        engine(handle)->export_gcode_3mf(to_string(env, gcode_path), to_string(env, path),
                                         images.empty() ? slicer::Thumbnail() : images.front());
    });
}

// Largura e altura intercaladas.
JNIEXPORT jintArray JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativeGcodeThumbnailSizes(JNIEnv *env, jclass, jlong handle)
{
    return guarded(env, [&] {
        std::vector<jint> values;
        for (const auto &[width, height] : engine(handle)->gcode_thumbnail_sizes())
            values.insert(values.end(), {jint(width), jint(height)});
        jintArray out = env->NewIntArray(jsize(values.size()));
        env->SetIntArrayRegion(out, 0, jsize(values.size()), values.data());
        return out;
    });
}

JNIEXPORT void JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativeDuplicate(JNIEnv *env, jclass, jlong handle, jint object)
{
    guarded(env, [&] { engine(handle)->duplicate(size_t(object)); });
}

JNIEXPORT void JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativeRemove(JNIEnv *env, jclass, jlong handle, jint object)
{
    guarded(env, [&] { engine(handle)->remove(size_t(object)); });
}

JNIEXPORT void JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativeArrange(JNIEnv *env, jclass, jlong handle)
{
    guarded(env, [&] { engine(handle)->arrange(); });
}

JNIEXPORT jobjectArray JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativeObjects(JNIEnv *env, jclass, jlong handle)
{
    return guarded(env, [&]() -> jobjectArray {
        const std::vector<slicer::ObjectMesh> meshes = engine(handle)->objects();
        jclass cls = env->FindClass("io/github/andredevel/slicer/core/ObjectMesh");
        jmethodID init = env->GetMethodID(cls, "<init>", "(Ljava/lang/String;[F[I[B)V");
        jobjectArray out = env->NewObjectArray(jsize(meshes.size()), cls, nullptr);
        for (size_t i = 0; i < meshes.size(); ++i) {
            const slicer::ObjectMesh &mesh = meshes[i];
            jfloatArray positions = to_float_array(env, mesh.positions);
            jintArray indices = env->NewIntArray(jsize(mesh.indices.size()));
            env->SetIntArrayRegion(indices, 0, jsize(mesh.indices.size()), reinterpret_cast<const jint *>(mesh.indices.data()));
            jbyteArray filaments = env->NewByteArray(jsize(mesh.filaments.size()));
            env->SetByteArrayRegion(filaments, 0, jsize(mesh.filaments.size()), reinterpret_cast<const jbyte *>(mesh.filaments.data()));
            jstring name = env->NewStringUTF(mesh.name.c_str());
            jobject object = env->NewObject(cls, init, name, positions, indices, filaments);
            env->SetObjectArrayElement(out, jsize(i), object);
            for (jobject ref : {jobject(name), jobject(positions), jobject(indices), jobject(filaments), object})
                env->DeleteLocalRef(ref);
        }
        return out;
    });
}

JNIEXPORT jobjectArray JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativeFilamentColors(JNIEnv *env, jclass, jlong handle)
{
    return guarded(env, [&] { return to_array(env, engine(handle)->filament_colors()); });
}

JNIEXPORT jobjectArray JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativeFilamentPresets(JNIEnv *env, jclass, jlong handle)
{
    return guarded(env, [&] { return to_array(env, engine(handle)->filament_presets()); });
}

// Por objeto, 29 valores: matriz 4x4 (coluna), offset, rotação (graus), escala, tamanho (mm) e
// 1 se estiver fora do volume de impressão (senão 0).
JNIEXPORT jdoubleArray JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativePlacements(JNIEnv *env, jclass, jlong handle)
{
    return guarded(env, [&]() -> jdoubleArray {
        std::vector<jdouble> values;
        for (const slicer::Placement &p : engine(handle)->placements()) {
            values.insert(values.end(), p.transform.begin(), p.transform.end());
            for (const slicer::Vec3 *v : {&p.offset, &p.rotation, &p.scale, &p.size})
                values.insert(values.end(), v->begin(), v->end());
            values.push_back(p.outside ? 1.0 : 0.0);
        }
        jdoubleArray out = env->NewDoubleArray(jsize(values.size()));
        env->SetDoubleArrayRegion(out, 0, jsize(values.size()), values.data());
        return out;
    });
}

JNIEXPORT void JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativeSetTransform(JNIEnv *env, jclass, jlong handle, jint object, jdoubleArray transform)
{
    guarded(env, [&] {
        std::array<double, 16> matrix{};
        env->GetDoubleArrayRegion(transform, 0, 16, matrix.data());
        engine(handle)->set_transform(size_t(object), matrix);
    });
}

// values: offset, rotação (graus) e escala, 3 valores cada.
JNIEXPORT void JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativeSetPlacement(JNIEnv *env, jclass, jlong handle, jint object, jdoubleArray values)
{
    guarded(env, [&] {
        std::array<double, 9> v{};
        env->GetDoubleArrayRegion(values, 0, 9, v.data());
        engine(handle)->set_placement(size_t(object), {v[0], v[1], v[2]}, {v[3], v[4], v[5]}, {v[6], v[7], v[8]});
    });
}

JNIEXPORT void JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativeLayOnFace(JNIEnv *env, jclass, jlong handle, jint object, jint triangle)
{
    guarded(env, [&] { engine(handle)->lay_on_face(size_t(object), size_t(triangle)); });
}

// Retorna {tempo estimado em s, volume em mm³, comprimento em m, peso em g, custo}.
JNIEXPORT jdoubleArray JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativeSlice(JNIEnv *env, jclass, jlong handle, jstring gcode_path,
                                                               jintArray thumbnail_sizes, jobjectArray thumbnails, jobject listener)
{
    const std::vector<slicer::Thumbnail> images = to_thumbnails(env, thumbnail_sizes, thumbnails);
    jmethodID on_progress = env->GetMethodID(env->GetObjectClass(listener), "onProgress", "(ILjava/lang/String;)V");
    return guarded(env, [&] {
        const std::thread::id jni_thread = std::this_thread::get_id();
        slicer::SliceStats stats = engine(handle)->slice(to_string(env, gcode_path), [&](int percent, const std::string &text) {
            // libslic3r pode reportar de threads do TBB, onde este JNIEnv não vale.
            if (std::this_thread::get_id() != jni_thread)
                return;
            jstring jtext = env->NewStringUTF(text.c_str());
            env->CallVoidMethod(listener, on_progress, jint(percent), jtext);
            env->DeleteLocalRef(jtext);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
        }, images);
        return to_array(env, stats);
    });
}

// Mesmos valores de nativeSlice.
JNIEXPORT jdoubleArray JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativeLoadGcode(JNIEnv *env, jclass, jlong handle, jstring path)
{
    return guarded(env, [&] { return to_array(env, engine(handle)->load_gcode(to_string(env, path))); });
}

// Toolpaths(extrusions: FloatArray, roles: ByteArray, layers: IntArray, travels: FloatArray,
//           travelLayers: IntArray, layerZ: FloatArray)
JNIEXPORT jobject JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativeToolpaths(JNIEnv *env, jclass, jlong handle)
{
    return guarded(env, [&]() -> jobject {
        const slicer::Toolpaths t = engine(handle)->toolpaths();
        auto ints = [env](const std::vector<uint32_t> &v) {
            jintArray a = env->NewIntArray(jsize(v.size()));
            env->SetIntArrayRegion(a, 0, jsize(v.size()), reinterpret_cast<const jint *>(v.data()));
            return a;
        };
        jbyteArray roles = env->NewByteArray(jsize(t.roles.size()));
        env->SetByteArrayRegion(roles, 0, jsize(t.roles.size()), reinterpret_cast<const jbyte *>(t.roles.data()));
        jclass cls = env->FindClass("io/github/andredevel/slicer/core/Toolpaths");
        jmethodID init = env->GetMethodID(cls, "<init>", "([F[B[I[F[I[F)V");
        return env->NewObject(cls, init, to_float_array(env, t.extrusions), roles, ints(t.layers),
                              to_float_array(env, t.travels), ints(t.travel_layers), to_float_array(env, t.layer_z));
    });
}

JNIEXPORT jobjectArray JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativeRoleNames(JNIEnv *env, jclass)
{
    return guarded(env, [&] { return to_array(env, Engine::role_names()); });
}

JNIEXPORT void JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativeCancel(JNIEnv *, jclass, jlong handle)
{
    engine(handle)->cancel();
}

} // extern "C"
