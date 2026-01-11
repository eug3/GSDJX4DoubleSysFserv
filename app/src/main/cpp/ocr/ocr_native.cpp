#include "ocr_native.h"
#include "ocr_ppredictor.h"
#include <android/log.h>
#include <paddle_api.h>
#include <string>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "OCRNative", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "OCRNative", __VA_ARGS__)

static paddle::lite_api::PowerMode str_to_cpu_mode(const std::string &cpu_mode);

extern "C" JNIEXPORT jlong JNICALL
Java_com_guaishoudejia_x4doublesysfserv_ocr_OcrNative_init(
    JNIEnv *env, jobject thiz, jstring j_det_model_path,
    jstring j_rec_model_path, jstring j_cls_model_path, jint j_use_opencl,
    jint j_thread_num, jstring j_cpu_mode) {
  std::string det_model_path = jstring_to_cpp_string(env, j_det_model_path);
  std::string rec_model_path = jstring_to_cpp_string(env, j_rec_model_path);
  std::string cls_model_path = jstring_to_cpp_string(env, j_cls_model_path);
  int thread_num = j_thread_num;
  std::string cpu_mode = jstring_to_cpp_string(env, j_cpu_mode);

  ppredictor::OCR_Config conf;
  conf.use_opencl = j_use_opencl;
  conf.thread_num = thread_num;
  conf.mode = str_to_cpu_mode(cpu_mode);

  ppredictor::OCR_PPredictor *ocr_predictor =
      new ppredictor::OCR_PPredictor{conf};
  ocr_predictor->init_from_file(det_model_path, rec_model_path, cls_model_path);

  LOGI("OCR Native initialized successfully");
  return reinterpret_cast<jlong>(ocr_predictor);
}

static paddle::lite_api::PowerMode
str_to_cpu_mode(const std::string &cpu_mode) {
  if (cpu_mode == "LITE_POWER_HIGH") {
    return paddle::lite_api::LITE_POWER_HIGH;
  } else if (cpu_mode == "LITE_POWER_LOW") {
    return paddle::lite_api::LITE_POWER_LOW;
  } else if (cpu_mode == "LITE_POWER_FULL") {
    return paddle::lite_api::LITE_POWER_FULL;
  } else if (cpu_mode == "LITE_POWER_NO_BIND") {
    return paddle::lite_api::LITE_POWER_NO_BIND;
  } else if (cpu_mode == "LITE_POWER_RAND_HIGH") {
    return paddle::lite_api::LITE_POWER_RAND_HIGH;
  } else if (cpu_mode == "LITE_POWER_RAND_LOW") {
    return paddle::lite_api::LITE_POWER_RAND_LOW;
  }
  return paddle::lite_api::LITE_POWER_HIGH;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_guaishoudejia_x4doublesysfserv_ocr_OcrNative_forward(
    JNIEnv *env, jobject thiz, jlong java_pointer, jobject original_image,
    jint j_max_size_len, jint j_run_det, jint j_run_cls, jint j_run_rec) {
  LOGI("begin to run native forward");
  if (java_pointer == 0) {
    LOGE("JAVA pointer is NULL");
    return cpp_array_to_jfloatarray(env, nullptr, 0);
  }

  cv::Mat origin = bitmap_to_cv_mat(env, original_image);
  if (origin.size == 0) {
    LOGE("origin bitmap cannot convert to CV Mat");
    return cpp_array_to_jfloatarray(env, nullptr, 0);
  }

  int max_size_len = j_max_size_len;
  int run_det = j_run_det;
  int run_cls = j_run_cls;
  int run_rec = j_run_rec;

  ppredictor::OCR_PPredictor *ppredictor =
      (ppredictor::OCR_PPredictor *)java_pointer;
  std::vector<ppredictor::OCRPredictResult> results =
      ppredictor->infer_ocr(origin, max_size_len, run_det, run_cls, run_rec);
  LOGI("infer_ocr finished with boxes %ld", results.size());

  // 序列化结果到 float 数组
  std::vector<float> float_arr;
  for (const ppredictor::OCRPredictResult &r : results) {
    float_arr.push_back(r.points.size());        // point count
    float_arr.push_back(r.word_index.size());    // word count
    float_arr.push_back(r.score);                // confidence score

    // add det points
    for (const std::vector<int> &point : r.points) {
      float_arr.push_back(point.at(0));  // x
      float_arr.push_back(point.at(1));  // y
    }

    // add rec word indices
    for (int index : r.word_index) {
      float_arr.push_back(index);
    }

    // add cls result
    float_arr.push_back(r.cls_label);
    float_arr.push_back(r.cls_score);
  }

  return cpp_array_to_jfloatarray(env, float_arr.data(), float_arr.size());
}

extern "C" JNIEXPORT void JNICALL
Java_com_guaishoudejia_x4doublesysfserv_ocr_OcrNative_release(
    JNIEnv *env, jobject thiz, jlong java_pointer) {
  if (java_pointer == 0) {
    LOGE("JAVA pointer is NULL");
    return;
  }
  ppredictor::OCR_PPredictor *ppredictor =
      (ppredictor::OCR_PPredictor *)java_pointer;
  delete ppredictor;
  LOGI("OCR Native released");
}
