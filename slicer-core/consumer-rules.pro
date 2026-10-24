# Usados pelo JNI (native/src/jni_bridge.cpp) por nome.
-keep class io.github.andredevel.slicer.core.SlicerException { <init>(java.lang.String); }
-keep interface io.github.andredevel.slicer.core.SliceProgressListener { *; }
-keepclasseswithmembernames class io.github.andredevel.slicer.core.SlicerEngineKt { native <methods>; }
-keep class io.github.andredevel.slicer.core.Bed { <init>(float[], float); }
-keep class io.github.andredevel.slicer.core.ObjectMesh { <init>(float[], int[]); }
