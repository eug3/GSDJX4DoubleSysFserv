#include "rec_native.h"
#include <string>
#include <vector>
#include <android/log.h>
#include <paddle_api.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "RecNative", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "RecNative", __VA_ARGS__)

static paddle::lite_api::PowerMode str_to_power_mode(const std::string& cpu_mode) {
  using namespace paddle::lite_api;
  if (cpu_mode == "LITE_POWER_HIGH") return LITE_POWER_HIGH;
  if (cpu_mode == "LITE_POWER_LOW") return LITE_POWER_LOW;
  if (cpu_mode == "LITE_POWER_FULL") return LITE_POWER_FULL;
  if (cpu_mode == "LITE_POWER_NO_BIND") return LITE_POWER_NO_BIND;
  return LITE_POWER_HIGH;
}

static std::string jstring_to_std(JNIEnv* env, jstring js) {
  const char* chars = env->GetStringUTFChars(js, nullptr);
  std::string s(chars ? chars : "");
  env->ReleaseStringUTFChars(js, chars);
  return s;
}

struct RecContext {
  std::unique_ptr<paddle::lite_api::PaddlePredictor> predictor;
};

JNIEXPORT jlong JNICALL Java_com_guaishoudejia_x4doublesysfserv_ocr_RecPredictorNative_init(
    JNIEnv* env, jobject, jstring j_model_path, jint j_use_opencl,
    jint j_thread_num, jstring j_cpu_mode) {
  std::string model_path = jstring_to_std(env, j_model_path);
  std::string cpu_mode = jstring_to_std(env, j_cpu_mode);
  int thread_num = (int)j_thread_num;
  int use_opencl = (int)j_use_opencl;

  LOGI("Rec init model=%s threads=%d opencl=%d", model_path.c_str(), thread_num, use_opencl);

  paddle::lite_api::MobileConfig config;
  config.set_model_from_file(model_path);
  config.set_threads(thread_num);
  config.set_power_mode(str_to_power_mode(cpu_mode));
  config.set_valid_places({paddle::lite_api::Place({paddle::lite_api::TARGET(kARM), paddle::lite_api::PRECISION(kFloat), paddle::lite_api::DATA_LAYOUT(kNCHW)}),
                           paddle::lite_api::Place({paddle::lite_api::TARGET(kHost), paddle::lite_api::PRECISION(kFloat), paddle::lite_api::DATA_LAYOUT(kNCHW)})});
  if (use_opencl) {
    config.set_opencl_binary_path("");
    config.set_opencl_kernel_path("");
    config.set_enable_opencl(true);
  }

  auto ctx = new RecContext();
  ctx->predictor = paddle::lite_api::CreatePaddlePredictor<paddle::lite_api::MobileConfig>(config);
  if (!ctx->predictor) {
    LOGE("Create predictor failed");
    delete ctx;
    return 0;
  }
  return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jfloatArray JNICALL Java_com_guaishoudejia_x4doublesysfserv_ocr_RecPredictorNative_forward(
    JNIEnv* env, jobject, jlong native_ptr, jfloatArray j_input,
    jint j_height, jint j_width) {
  if (native_ptr == 0) {
    LOGE("native_ptr is null");
    return env->NewFloatArray(0);
  }
  auto ctx = reinterpret_cast<RecContext*>(native_ptr);
  int H = (int)j_height;
  int W = (int)j_width;

  jsize len = env->GetArrayLength(j_input);
  std::vector<float> input(len);
  env->GetFloatArrayRegion(j_input, 0, len, input.data());

  auto in = ctx->predictor->GetInput(0);
  in->Resize({1, 3, H, W});
  float* in_data = in->mutable_data<float>();
  memcpy(in_data, input.data(), sizeof(float) * input.size());

  ctx->predictor->Run();

  auto out = ctx->predictor->GetOutput(0);
  const float* out_data = out->data<float>();
  auto shape = out->shape();
  int out_len = 1;
  for (auto d : shape) out_len *= d;
  jfloatArray j_out = env->NewFloatArray(out_len);
  env->SetFloatArrayRegion(j_out, 0, out_len, out_data);
  return j_out;
}

JNIEXPORT void JNICALL Java_com_guaishoudejia_x4doublesysfserv_ocr_RecPredictorNative_release(
    JNIEnv* , jobject, jlong native_ptr) {
  if (native_ptr == 0) return;
  auto ctx = reinterpret_cast<RecContext*>(native_ptr);
  delete ctx;
}
