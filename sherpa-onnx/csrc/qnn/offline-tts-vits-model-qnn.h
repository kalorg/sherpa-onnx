// sherpa-onnx/csrc/qnn/offline-tts-vits-model-qnn.h
//
// Copyright (c)  2025  Xiaomi Corporation
#ifndef SHERPA_ONNX_CSRC_QNN_OFFLINE_TTS_VITS_MODEL_QNN_H_
#define SHERPA_ONNX_CSRC_QNN_OFFLINE_TTS_VITS_MODEL_QNN_H_

#include <memory>
#include <string>
#include <vector>

#include "onnxruntime_cxx_api.h"  // NOLINT
#include "sherpa-onnx/csrc/offline-tts-model-config.h"
#include "sherpa-onnx/csrc/offline-tts-vits-model-meta-data.h"

namespace sherpa_onnx {

class OfflineTtsVitsModelQnn {
 public:
  ~OfflineTtsVitsModelQnn();

  explicit OfflineTtsVitsModelQnn(const OfflineTtsModelConfig &config);

  template <typename Manager>
  OfflineTtsVitsModelQnn(Manager *mgr, const OfflineTtsModelConfig &config);

  /** Run the 4-component QNN Piper/VITS TTS model (encoder -> sdp -> flow ->
   * decoder).
   *
   * @param x A int64 tensor of shape (1, num_tokens)
   * @param sid Speaker ID (ignored for single-speaker Piper QNN models)
   * @param speed Speech speed multiplier (1.0 = default)
   * @param emotion_id Emotion ID (ignored)
   * @return Return a float32 Ort::Value tensor containing audio samples.
   */
  Ort::Value Run(Ort::Value x, int64_t sid = 0, float speed = 1.0,
                 int64_t emotion_id = 0, float noise_scale = -1.0f,
                 float noise_scale_w = -1.0f);

  // Overload for MeloTTS signature compatibility
  Ort::Value Run(Ort::Value x, Ort::Value tones, int64_t sid = 0,
                 float speed = 1.0) const;

  const OfflineTtsVitsModelMetaData &GetMetaData() const;

 private:
  class Impl;
  std::unique_ptr<Impl> impl_;
};

}  // namespace sherpa_onnx

#endif  // SHERPA_ONNX_CSRC_QNN_OFFLINE_TTS_VITS_MODEL_QNN_H_
