// sherpa-onnx/csrc/qnn/macros.h
//
// Copyright      2025  Xiaomi Corporation

#ifndef SHERPA_ONNX_CSRC_QNN_MACROS_H_
#define SHERPA_ONNX_CSRC_QNN_MACROS_H_

#include <cstdio>
#include <stdexcept>
#include <string>

#include "sherpa-onnx/csrc/macros.h"

// On failure we throw a std::runtime_error instead of calling _Exit() so that
// callers (e.g., the JNI layer via SafeJNI) can report the exact QNN error to
// the user instead of killing the whole Android process.
#define SHERPA_ONNX_QNN_CHECK(ret, msg, ...)                             \
  do {                                                                   \
    if (ret != QNN_SUCCESS) {                                            \
      SHERPA_ONNX_LOGE("Return code is: %d", static_cast<int32_t>(ret)); \
      SHERPA_ONNX_LOGE(msg, ##__VA_ARGS__);                              \
      char sherpa_onnx_qnn_buf_[1024];                                   \
      snprintf(sherpa_onnx_qnn_buf_, sizeof(sherpa_onnx_qnn_buf_), msg,  \
               ##__VA_ARGS__);                                           \
      throw std::runtime_error(                                          \
          std::string("QNN error (code ") +                              \
          std::to_string(static_cast<int64_t>(ret)) + "): " +            \
          sherpa_onnx_qnn_buf_);                                         \
    }                                                                    \
  } while (0)

#endif  // SHERPA_ONNX_CSRC_QNN_MACROS_H_
