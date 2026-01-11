#pragma once
#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jlong JNICALL Java_com_guaishoudejia_x4doublesysfserv_ocr_RecPredictorNative_init(
    JNIEnv* env, jobject thiz, jstring j_model_path, jint j_use_opencl,
    jint j_thread_num, jstring j_cpu_mode);

JNIEXPORT jfloatArray JNICALL Java_com_guaishoudejia_x4doublesysfserv_ocr_RecPredictorNative_forward(
    JNIEnv* env, jobject thiz, jlong native_ptr, jfloatArray j_input,
    jint j_height, jint j_width);

JNIEXPORT void JNICALL Java_com_guaishoudejia_x4doublesysfserv_ocr_RecPredictorNative_release(
    JNIEnv* env, jobject thiz, jlong native_ptr);

#ifdef __cplusplus
}
#endif
