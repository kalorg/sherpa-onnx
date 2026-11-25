// sherpa-onnx/csrc/offline-tts-impl.cc
//
// Copyright (c)  2023  Xiaomi Corporation

#include "sherpa-onnx/csrc/offline-tts-impl.h"

#include <memory>
#include <vector>

#if __ANDROID_API__ >= 9
#include "android/asset_manager.h"
#include "android/asset_manager_jni.h"
#endif

#include "sherpa-onnx/csrc/offline-tts-vits-impl.h"

#if SHERPA_ONNX_ENABLE_QNN
#include "sherpa-onnx/csrc/qnn/offline-tts-vits-model-qnn.h"
#endif

namespace sherpa_onnx {

std::vector<int64_t> OfflineTtsImpl::AddBlank(const std::vector<int64_t> &x,
                                              int32_t blank_id /*= 0*/) const {
  std::vector<int64_t> buffer(x.size() * 2 + 1, blank_id);
  int32_t i = 1;
  for (auto k : x) {
    buffer[i] = k;
    i += 2;
  }
  return buffer;
}

std::unique_ptr<OfflineTtsImpl> OfflineTtsImpl::Create(
    const OfflineTtsConfig &config) {
  if (config.model.provider == "qnn") {
#if SHERPA_ONNX_ENABLE_QNN
    if (!config.model.vits.model.empty() ||
        !config.model.vits.qnn_config.context_binary.empty()) {
      return std::make_unique<OfflineTtsVitsTplImpl<OfflineTtsVitsModelQnn>>(
          config);
    }
#else
    SHERPA_ONNX_LOGE(
        "Please rebuild sherpa-onnx with -DSHERPA_ONNX_ENABLE_QNN=ON if you "
        "want to use qnn for TTS.");
    return {};
#endif
  }

  if (!config.model.vits.model.empty()) {
    return std::make_unique<OfflineTtsVitsImpl>(config);
  }

  SHERPA_ONNX_LOGE("Please provide a Piper/VITS TTS model.");
  return {};
}

template <typename Manager>
std::unique_ptr<OfflineTtsImpl> OfflineTtsImpl::Create(
    Manager *mgr, const OfflineTtsConfig &config) {
  if (config.model.provider == "qnn") {
#if SHERPA_ONNX_ENABLE_QNN
    if (!config.model.vits.model.empty() ||
        !config.model.vits.qnn_config.context_binary.empty()) {
      return std::make_unique<OfflineTtsVitsTplImpl<OfflineTtsVitsModelQnn>>(
          mgr, config);
    }
#else
    SHERPA_ONNX_LOGE(
        "Please rebuild sherpa-onnx with -DSHERPA_ONNX_ENABLE_QNN=ON if you "
        "want to use qnn for TTS.");
    return {};
#endif
  }

  if (!config.model.vits.model.empty()) {
    return std::make_unique<OfflineTtsVitsImpl>(mgr, config);
  }

  SHERPA_ONNX_LOGE("Please provide a Piper/VITS TTS model.");
  return {};
}

#if __ANDROID_API__ >= 9
template std::unique_ptr<OfflineTtsImpl> OfflineTtsImpl::Create(
    AAssetManager *mgr, const OfflineTtsConfig &config);
#endif

}  // namespace sherpa_onnx
