#pragma once

#include <android/bitmap.h>
#include <jni.h>
#include <opencv2/opencv.hpp>
#include <string>
#include <vector>

inline std::string jstring_to_cpp_string(JNIEnv *env, jstring jstr) {
  if (!jstr) {
    return "";
  }
  const jclass stringClass = env->GetObjectClass(jstr);
  const jmethodID getBytes =
      env->GetMethodID(stringClass, "getBytes", "(Ljava/lang/String;)[B");
  const jbyteArray stringJbytes = (jbyteArray)env->CallObjectMethod(
      jstr, getBytes, env->NewStringUTF("UTF-8"));

  size_t length = (size_t)env->GetArrayLength(stringJbytes);
  jbyte *pBytes = env->GetByteArrayElements(stringJbytes, NULL);

  std::string ret = std::string(reinterpret_cast<char *>(pBytes), length);
  env->ReleaseByteArrayElements(stringJbytes, pBytes, JNI_ABORT);

  env->DeleteLocalRef(stringJbytes);
  env->DeleteLocalRef(stringClass);
  return ret;
}

inline jfloatArray cpp_array_to_jfloatarray(JNIEnv *env, const float *buf,
                                            int64_t len) {
  if (len == 0) {
    return env->NewFloatArray(0);
  }
  jfloatArray result = env->NewFloatArray(len);
  env->SetFloatArrayRegion(result, 0, len, buf);
  return result;
}

inline cv::Mat bitmap_to_cv_mat(JNIEnv *env, jobject bitmap) {
  AndroidBitmapInfo info;
  int result = AndroidBitmap_getInfo(env, bitmap, &info);
  if (result != ANDROID_BITMAP_RESULT_SUCCESS) {
    return cv::Mat{};
  }
  if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
    return cv::Mat{};
  }
  unsigned char *srcData = NULL;
  AndroidBitmap_lockPixels(env, bitmap, (void **)&srcData);
  cv::Mat mat = cv::Mat::zeros(info.height, info.width, CV_8UC4);
  memcpy(mat.data, srcData, info.height * info.width * 4);
  AndroidBitmap_unlockPixels(env, bitmap);
  cv::cvtColor(mat, mat, cv::COLOR_RGBA2BGR);
  return mat;
}
