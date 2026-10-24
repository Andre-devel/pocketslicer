// JNI de io.github.andredevel.slicer.core.SlicerEngine (funções nativas de
// SlicerEngine.kt, classe JVM SlicerEngineKt) sobre a fachada engine.hpp.
//
// O handle Java é um ponteiro para slicer::Engine. Exceções C++ viram
// io.github.andredevel.slicer.core.SlicerException; slicer::Canceled vira
// java.util.concurrent.CancellationException.

#include <jni.h>

#include <string>
#include <thread>
#include <vector>

#include "engine.hpp"

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

Engine *engine(jlong handle) { return reinterpret_cast<Engine *>(handle); }

// Executa f convertendo exceções C++ em exceções Java pendentes.
template<typename F>
auto guarded(JNIEnv *env, F &&f) -> decltype(f())
{
    try {
        return f();
    } catch (const slicer::Canceled &e) {
        env->ThrowNew(env->FindClass("java/util/concurrent/CancellationException"), e.what());
    } catch (const std::exception &e) {
        env->ThrowNew(env->FindClass("io/github/andredevel/slicer/core/SlicerException"), e.what());
    } catch (...) {
        env->ThrowNew(env->FindClass("io/github/andredevel/slicer/core/SlicerException"), "unknown native error");
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

JNIEXPORT jobjectArray JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativeObjects(JNIEnv *env, jclass, jlong handle)
{
    return guarded(env, [&]() -> jobjectArray {
        const std::vector<slicer::ObjectMesh> meshes = engine(handle)->objects();
        jclass cls = env->FindClass("io/github/andredevel/slicer/core/ObjectMesh");
        jmethodID init = env->GetMethodID(cls, "<init>", "([F[I[F)V");
        jobjectArray out = env->NewObjectArray(jsize(meshes.size()), cls, nullptr);
        for (size_t i = 0; i < meshes.size(); ++i) {
            const slicer::ObjectMesh &mesh = meshes[i];
            jfloatArray positions = to_float_array(env, mesh.positions);
            jintArray indices = env->NewIntArray(jsize(mesh.indices.size()));
            env->SetIntArrayRegion(indices, 0, jsize(mesh.indices.size()), reinterpret_cast<const jint *>(mesh.indices.data()));
            jfloatArray transform = to_float_array(env, std::vector<jfloat>(mesh.transform.begin(), mesh.transform.end()));
            jobject object = env->NewObject(cls, init, positions, indices, transform);
            env->SetObjectArrayElement(out, jsize(i), object);
            for (jobject ref : {jobject(positions), jobject(indices), jobject(transform), object})
                env->DeleteLocalRef(ref);
        }
        return out;
    });
}

// Retorna {tempo estimado em s, volume de filamento em mm³}.
JNIEXPORT jdoubleArray JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativeSlice(JNIEnv *env, jclass, jlong handle, jstring gcode_path, jobject listener)
{
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
        });
        jdoubleArray out = env->NewDoubleArray(2);
        const jdouble values[2] = {stats.print_time_s, stats.filament_mm3};
        env->SetDoubleArrayRegion(out, 0, 2, values);
        return out;
    });
}

JNIEXPORT void JNICALL
Java_io_github_andredevel_slicer_core_SlicerEngineKt_nativeCancel(JNIEnv *, jclass, jlong handle)
{
    engine(handle)->cancel();
}

} // extern "C"
