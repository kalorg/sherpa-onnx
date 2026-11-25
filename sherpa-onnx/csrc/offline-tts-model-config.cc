// sherpa-onnx/csrc/offline-tts-model-config.cc
//
// Copyright (c)  2023  Xiaomi Corporation

#include "sherpa-onnx/csrc/offline-tts-model-config.h"

#include <sstream>
#include <string>

#include "sherpa-onnx/csrc/macros.h"

namespace sherpa_onnx {

void OfflineTtsModelConfig::Register(ParseOptions *po) {
  vits.Register(po);

  po->Register("num-threads", &num_threads,
               "Number of threads to run the neural network");

  po->Register("debug", &debug,
               "true to print model information while loading it.");

  po->Register("provider", &provider,
               "Specify a provider to use: cpu, qnn");
}

bool OfflineTtsModelConfig::Validate() const {
  if (num_threads < 1) {
    SHERPA_ONNX_LOGE("num_threads should be > 0. Given %d", num_threads);
    return false;
  }

  if (!vits.model.empty() || !vits.qnn_config.context_binary.empty()) {
    return vits.Validate();
  }

  SHERPA_ONNX_LOGE("Please provide a Piper/VITS TTS model (QNN or ONNX).");

  return false;
}

std::string OfflineTtsModelConfig::ToString() const {
  std::ostringstream os;

  os << "OfflineTtsModelConfig(";
  os << "vits=" << vits.ToString() << ", ";
  os << "num_threads=" << num_threads << ", ";
  os << "debug=" << (debug ? "True" : "False") << ", ";
  os << "provider=\"" << provider << "\")";

  return os.str();
}

}  // namespace sherpa_onnx
