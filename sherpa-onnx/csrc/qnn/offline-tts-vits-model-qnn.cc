// sherpa-onnx/csrc/qnn/offline-tts-vits-model-qnn.cc
//
// Copyright (c)  2025  Xiaomi Corporation

#include "sherpa-onnx/csrc/qnn/offline-tts-vits-model-qnn.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

#if __ANDROID_API__ >= 9
#include "android/asset_manager.h"
#include "android/asset_manager_jni.h"
#endif

#if __OHOS__
#include "rawfile/raw_file_manager.h"
#endif

#include "nlohmann/json.hpp"
#include "sherpa-onnx/csrc/file-utils.h"
#include "sherpa-onnx/csrc/qnn/macros.h"
#include "sherpa-onnx/csrc/qnn/qnn-backend.h"
#include "sherpa-onnx/csrc/qnn/qnn-model.h"
#include "sherpa-onnx/csrc/text-utils.h"

namespace sherpa_onnx {

class OfflineTtsVitsModelQnn::Impl {
 public:
  explicit Impl(const OfflineTtsModelConfig &config)
      : config_(config), allocator_{} {
    InitMetaData();

    std::vector<std::string> filenames;
    SplitStringToVector(config_.vits.model, ",", true, &filenames);
    if (!filenames.empty() && filenames.size() != 4) {
      SHERPA_ONNX_LOGE(
          "For Piper/VITS QNN with shared libs, please provide 4 files "
          "(libencoder.so,libsdp.so,libflow.so,libdecoder.so). Actual: %d "
          "('%s')",
          static_cast<int32_t>(filenames.size()), config_.vits.model.c_str());
      throw std::runtime_error(
          "Piper QNN: expected 4 model libs, got '" + config_.vits.model + "'");
    }

    std::vector<std::string> binary_filenames;
    SplitStringToVector(config_.vits.qnn_config.context_binary, ",", true,
                        &binary_filenames);
    if (!binary_filenames.empty() && binary_filenames.size() != 4) {
      SHERPA_ONNX_LOGE(
          "There should be 4 files for Piper/VITS QNN context binary "
          "(encoder.bin,sdp.bin,flow.bin,decoder.bin). Actual: %d ('%s')",
          static_cast<int32_t>(binary_filenames.size()),
          config_.vits.qnn_config.context_binary.c_str());
      throw std::runtime_error(
          "Piper QNN: expected 4 context binaries "
          "(encoder.bin,sdp.bin,flow.bin,decoder.bin), got '" +
          config_.vits.qnn_config.context_binary + "'");
    }

    if (filenames.empty() && binary_filenames.empty()) {
      throw std::runtime_error(
          "You need to provide either a model or a context binary for "
          "Piper/VITS TTS with QNN");
    }

    backend_ = std::make_unique<QnnBackend>(
        config_.vits.qnn_config.backend_lib,
        QnnBackendOptions{config_.debug,
                          config_.vits.qnn_config.htp_perf_mode});
    if (!backend_->IsInitialized()) {
      throw std::runtime_error(
          "Failed to initialize QnnBackend with '" +
          config_.vits.qnn_config.backend_lib + "'");
    }

    bool ok = InitComponent(
        "encoder", filenames.empty() ? "" : filenames[0],
        binary_filenames.empty() ? "" : binary_filenames[0], &encoder_model_);
    if (!ok) {
      throw std::runtime_error("Failed to initialize Piper QNN encoder");
    }
    CheckEncoderModel();

    ok = InitComponent("sdp", filenames.empty() ? "" : filenames[1],
                       binary_filenames.empty() ? "" : binary_filenames[1],
                       &sdp_model_);
    if (!ok) {
      throw std::runtime_error("Failed to initialize Piper QNN sdp");
    }
    CheckSdpModel();

    ok = InitComponent("flow", filenames.empty() ? "" : filenames[2],
                       binary_filenames.empty() ? "" : binary_filenames[2],
                       &flow_model_);
    if (!ok) {
      throw std::runtime_error("Failed to initialize Piper QNN flow");
    }
    CheckFlowModel();

    ok = InitComponent("decoder", filenames.empty() ? "" : filenames[3],
                       binary_filenames.empty() ? "" : binary_filenames[3],
                       &decoder_model_);
    if (!ok) {
      throw std::runtime_error("Failed to initialize Piper QNN decoder");
    }
    CheckDecoderModel();
  }

  template <typename Manager>
  Impl(Manager * /*mgr*/, const OfflineTtsModelConfig & /*config*/)
      : allocator_{} {
    SHERPA_ONNX_LOGE(
        "Please copy all QNN files from assets to internal storage and set "
        "assetManager to null");
    throw std::runtime_error(
        "Piper QNN: copy model files to internal storage and pass "
        "assetManager = null");
  }

  Ort::Value Run(Ort::Value x, int64_t /*sid*/, float speed,
                 int64_t /*emotion_id*/) {
    std::lock_guard<std::mutex> lock(mutex_);

    std::vector<int64_t> x_shape = x.GetTensorTypeAndShapeInfo().GetShape();
    if (x_shape[0] != 1) {
      SHERPA_ONNX_LOGE("Support only batch_size == 1. Given: %d",
                       static_cast<int32_t>(x_shape[0]));
      SHERPA_ONNX_EXIT(-1);
    }

    const int64_t *x_ptr = x.GetTensorData<int64_t>();
    int32_t num_tokens = static_cast<int32_t>(x_shape[1]);

    std::vector<float> audio_samples;

    // If the input sequence exceeds max_seq_len_ (e.g., 512), process it in
    // chunks of max_seq_len_
    for (int32_t offset = 0; offset < num_tokens; offset += max_seq_len_) {
      int32_t chunk_len = std::min(max_seq_len_, num_tokens - offset);
      std::vector<float> chunk_audio =
          RunSingleChunk(x_ptr + offset, chunk_len, speed);
      audio_samples.insert(audio_samples.end(), chunk_audio.begin(),
                           chunk_audio.end());
    }

    if (audio_samples.empty()) {
      audio_samples.push_back(0.0f);
    }

    int64_t out_shape[1] = {static_cast<int64_t>(audio_samples.size())};
    Ort::Value out = Ort::Value::CreateTensor<float>(
        allocator_, out_shape, 1);
    float *out_ptr = out.GetTensorMutableData<float>();
    std::memcpy(out_ptr, audio_samples.data(),
                audio_samples.size() * sizeof(float));
    return out;
  }

  Ort::Value Run(Ort::Value /*x*/, Ort::Value /*tones*/, int64_t /*sid*/,
                 float /*speed*/) const {
    SHERPA_ONNX_LOGE("MeloTTS with tones is not supported in Piper QNN mode.");
    SHERPA_ONNX_EXIT(-1);
    return Ort::Value{nullptr};
  }

  const OfflineTtsVitsModelMetaData &GetMetaData() const { return meta_data_; }

 private:
  void InitMetaData() {
    meta_data_.sample_rate = 22050;
    meta_data_.add_blank = 1;
    meta_data_.num_speakers = 1;
    meta_data_.is_piper = true;
    meta_data_.voice = "en-us";
    meta_data_.language = "English";

    // Look for config.json in the same directory as tokens.txt to read
    // espeak.voice (e.g. "fr", "en-us"), audio.sample_rate, or Qualcomm
    // config.json voices[0].language / sample_rate.
    std::string dir;
    auto slash_pos = config_.vits.tokens.find_last_of("/\\");
    if (slash_pos != std::string::npos) {
      dir = config_.vits.tokens.substr(0, slash_pos);
    }
    if (!dir.empty()) {
      std::string cfg_path = dir + "/config.json";
      if (FileExists(cfg_path)) {
        try {
          auto buf = ReadFile(cfg_path);
          auto j = nlohmann::json::parse(buf.begin(), buf.end());
          if (j.contains("espeak") && j["espeak"].contains("voice")) {
            meta_data_.voice = j["espeak"]["voice"].get<std::string>();
          } else if (j.contains("voices") && j["voices"].is_array() &&
                     !j["voices"].empty()) {
            const auto &v0 = j["voices"][0];
            if (v0.contains("language")) {
              std::string lang = v0["language"].get<std::string>();
              meta_data_.voice = (lang == "en") ? "en-us" : lang;
            }
            if (v0.contains("sample_rate")) {
              meta_data_.sample_rate = v0["sample_rate"].get<int32_t>();
            }
          }
          if (j.contains("audio") && j["audio"].contains("sample_rate")) {
            meta_data_.sample_rate = j["audio"]["sample_rate"].get<int32_t>();
          }
          if (j.contains("num_speakers")) {
            meta_data_.num_speakers = j["num_speakers"].get<int32_t>();
          }
          if (config_.debug) {
            SHERPA_ONNX_LOGE(
                "Loaded Piper QNN config.json: voice='%s', sample_rate=%d",
                meta_data_.voice.c_str(), meta_data_.sample_rate);
          }
        } catch (const std::exception &e) {
          SHERPA_ONNX_LOGE("Warning: failed to parse '%s': %s",
                           cfg_path.c_str(), e.what());
        }
      }
    }
  }

  bool InitComponent(const std::string &name, const std::string &lib_filename,
                     const std::string &context_binary,
                     std::unique_ptr<QnnModel> *model) {
    if (context_binary.empty()) {
      if (config_.debug) {
        SHERPA_ONNX_LOGE(
            "Init %s from model lib '%s' since context binary is not given.",
            name.c_str(), lib_filename.c_str());
      }
      if (!backend_->ContextHandle()) {
        backend_->InitContext();
      }
      *model = std::make_unique<QnnModel>(lib_filename, backend_.get(),
                                          config_.debug);
    } else if (!FileExists(context_binary)) {
      if (lib_filename.empty()) {
        throw std::runtime_error("Piper QNN " + name +
                                 ": context binary not found: '" +
                                 context_binary + "'");
      }
      if (config_.debug) {
        SHERPA_ONNX_LOGE(
            "Init %s from model lib '%s' since context binary '%s' does not "
            "exist",
            name.c_str(), lib_filename.c_str(), context_binary.c_str());
      }
      if (!backend_->ContextHandle()) {
        backend_->InitContext();
      }
      *model = std::make_unique<QnnModel>(lib_filename, backend_.get(),
                                          config_.debug);
      (*model)->SaveBinaryContext(context_binary);
    } else {
      if (config_.debug) {
        SHERPA_ONNX_LOGE("Init %s from context binary '%s'", name.c_str(),
                         context_binary.c_str());
      }
      if (config_.vits.qnn_config.system_lib.empty()) {
        SHERPA_ONNX_LOGE(
            "You should provide --vits.qnn-system-lib if you also provide "
            "context binary");
        return false;
      }
      *model = std::make_unique<QnnModel>(
          context_binary, config_.vits.qnn_config.system_lib, backend_.get(),
          BinaryContextTag{}, config_.debug);
    }

    return (*model)->IsInitialized();
  }

  void CheckEncoderModel() {
    if (!encoder_model_->HasTensor("x") ||
        !encoder_model_->HasTensor("x_lengths")) {
      SHERPA_ONNX_LOGE(
          "Piper QNN encoder must have inputs 'x' and 'x_lengths'");
      throw std::runtime_error("Piper QNN encoder: unexpected tensor names in context binary (see logcat)");
    }
    auto x_shape = encoder_model_->TensorShape("x");
    if (x_shape.size() == 2) {
      max_seq_len_ = x_shape[1];
    }
    auto m_p_shape = encoder_model_->TensorShape("m_p");
    if (m_p_shape.size() == 3) {
      hidden_dim_ = m_p_shape[1];
    }
    if (config_.debug) {
      SHERPA_ONNX_LOGE("Piper QNN encoder: max_seq_len=%d, hidden_dim=%d",
                       max_seq_len_, hidden_dim_);
    }
  }

  void CheckSdpModel() {
    if (!sdp_model_->HasTensor("x_encoded") ||
        !sdp_model_->HasTensor("x_mask") ||
        !sdp_model_->HasTensor("length_scale") ||
        !sdp_model_->HasTensor("noise_scale_w")) {
      SHERPA_ONNX_LOGE(
          "Piper QNN sdp must have inputs 'x_encoded', 'x_mask', "
          "'length_scale', 'noise_scale_w'");
      throw std::runtime_error("Piper QNN sdp: unexpected tensor names in context binary (see logcat)");
    }
  }

  void CheckFlowModel() {
    if (!flow_model_->HasTensor("m_p") || !flow_model_->HasTensor("logs_p") ||
        !flow_model_->HasTensor("y_mask") ||
        !flow_model_->HasTensor("attn_squeezed") ||
        !flow_model_->HasTensor("noise_scale")) {
      SHERPA_ONNX_LOGE(
          "Piper QNN flow must have inputs 'm_p', 'logs_p', 'y_mask', "
          "'attn_squeezed', 'noise_scale'");
      throw std::runtime_error("Piper QNN flow: unexpected tensor names in context binary (see logcat)");
    }
    auto y_mask_shape = flow_model_->TensorShape("y_mask");
    if (y_mask_shape.size() == 3) {
      upsampled_max_seq_len_ = y_mask_shape[2];
    }
    if (config_.debug) {
      SHERPA_ONNX_LOGE("Piper QNN flow: upsampled_max_seq_len=%d",
                       upsampled_max_seq_len_);
    }
  }

  void CheckDecoderModel() {
    if (!decoder_model_->HasTensor("z") ||
        !decoder_model_->HasTensor("audio")) {
      SHERPA_ONNX_LOGE(
          "Piper QNN decoder must have input 'z' and output 'audio'");
      throw std::runtime_error("Piper QNN decoder: unexpected tensor names in context binary (see logcat)");
    }
    auto z_shape = decoder_model_->TensorShape("z");
    if (z_shape.size() == 3) {
      dec_seq_len_ = z_shape[2];
    }
    auto audio_shape = decoder_model_->TensorShape("audio");
    if (!audio_shape.empty() && dec_seq_len_ > 0) {
      int32_t audio_len = audio_shape.back();
      upsample_factor_ = audio_len / dec_seq_len_;
    }
    if (config_.debug) {
      SHERPA_ONNX_LOGE(
          "Piper QNN decoder: dec_seq_len=%d, upsample_factor=%d",
          dec_seq_len_, upsample_factor_);
    }
  }

  std::vector<float> RunSingleChunk(const int64_t *tokens, int32_t actual_len,
                                    float speed) const {
    // 1. Prepare padded int32 inputs for encoder: x [1, max_seq_len_],
    // x_lengths [1]
    std::vector<int32_t> x_padded(max_seq_len_, 0);
    for (int32_t i = 0; i < actual_len; ++i) {
      x_padded[i] = static_cast<int32_t>(tokens[i]);
    }
    int32_t x_lengths[1] = {actual_len};

    encoder_model_->SetInputTensorData("x", x_padded.data(), x_padded.size());
    encoder_model_->SetInputTensorData("x_lengths", x_lengths, 1);
    if (!encoder_model_->Run()) {
      SHERPA_ONNX_LOGE("Failed to run Piper QNN encoder");
      return {};
    }

    std::vector<float> x_encoded =
        encoder_model_->GetOutputTensorData("x_encoded");
    std::vector<float> m_p = encoder_model_->GetOutputTensorData("m_p");
    std::vector<float> logs_p = encoder_model_->GetOutputTensorData("logs_p");
    std::vector<float> x_mask = encoder_model_->GetOutputTensorData("x_mask");

    // 2. Run SDP (Stochastic Duration Predictor)
    float length_scale = config_.vits.length_scale;
    if (speed != 1.0f && speed > 0.0f) {
      length_scale = 1.0f / speed;
    }
    float noise_scale_w = config_.vits.noise_scale_w;

    sdp_model_->SetInputTensorData("x_encoded", x_encoded.data(),
                                   x_encoded.size());
    sdp_model_->SetInputTensorData("x_mask", x_mask.data(), x_mask.size());
    sdp_model_->SetInputTensorData("length_scale", &length_scale, 1);
    sdp_model_->SetInputTensorData("noise_scale_w", &noise_scale_w, 1);
    if (!sdp_model_->Run()) {
      SHERPA_ONNX_LOGE("Failed to run Piper QNN sdp");
      return {};
    }

    std::vector<float> y_lengths_vec =
        sdp_model_->GetOutputTensorData("y_lengths");
    std::vector<float> w_ceil = sdp_model_->GetOutputTensorData("w_ceil");

    if (y_lengths_vec.empty() || w_ceil.empty()) {
      SHERPA_ONNX_LOGE("Empty output from Piper QNN sdp");
      return {};
    }

    int32_t y_length = static_cast<int32_t>(y_lengths_vec[0]);
    if (y_length <= 0) {
      return {};
    }
    if (y_length > upsampled_max_seq_len_) {
      y_length = upsampled_max_seq_len_;
    }

    // 3. CPU glue: compute y_mask [1, 1, upsampled_max_seq_len_] and
    // attn_squeezed [1, upsampled_max_seq_len_, max_seq_len_] via generate_path
    std::vector<float> y_mask(upsampled_max_seq_len_, 0.0f);
    for (int32_t j = 0; j < y_length; ++j) {
      y_mask[j] = 1.0f;
    }

    // Monotonic alignment path generation (matches Qualcomm AI Hub Models
    // generate_path)
    std::vector<float> attn_squeezed(
        upsampled_max_seq_len_ * max_seq_len_, 0.0f);
    float cum_dur = 0.0f;
    for (int32_t i = 0; i < max_seq_len_; ++i) {
      float prev_cum = cum_dur;
      cum_dur += w_ceil[i];
      if (x_mask[i] <= 0.0f) {
        continue;
      }
      int32_t start_j = std::max(0, static_cast<int32_t>(std::ceil(prev_cum)));
      int32_t end_j = std::min(y_length, static_cast<int32_t>(std::ceil(cum_dur)));
      for (int32_t j = start_j; j < end_j; ++j) {
        attn_squeezed[j * max_seq_len_ + i] = 1.0f;
      }
    }

    // 4. Run Flow
    float noise_scale = config_.vits.noise_scale;
    flow_model_->SetInputTensorData("m_p", m_p.data(), m_p.size());
    flow_model_->SetInputTensorData("logs_p", logs_p.data(), logs_p.size());
    flow_model_->SetInputTensorData("y_mask", y_mask.data(), y_mask.size());
    flow_model_->SetInputTensorData("attn_squeezed", attn_squeezed.data(),
                                    attn_squeezed.size());
    flow_model_->SetInputTensorData("noise_scale", &noise_scale, 1);
    if (!flow_model_->Run()) {
      SHERPA_ONNX_LOGE("Failed to run Piper QNN flow");
      return {};
    }

    // z has shape [1, hidden_dim_, upsampled_max_seq_len_]
    std::vector<float> z = flow_model_->GetOutputTensorData("z");
    if (z.empty()) {
      SHERPA_ONNX_LOGE("Empty output from Piper QNN flow");
      return {};
    }

    // 5. Run Decoder in sliding windows (matches Qualcomm decode_chunks)
    // DEC_SEQ_OVERLAP = 12, MAX_DEC_SEQ_LEN = 40, DEC_SEQ_LEN = 64
    const int32_t dec_overlap = 12;
    const int32_t max_dec_seq_len = dec_seq_len_ - 2 * dec_overlap;

    std::vector<float> audio;
    audio.reserve(y_length * upsample_factor_);

    // First chunk: [0 : max_dec_seq_len + dec_overlap]
    std::vector<float> z_buf(hidden_dim_ * dec_seq_len_, 0.0f);
    int32_t first_copy_len =
        std::min(max_dec_seq_len + dec_overlap, upsampled_max_seq_len_);
    for (int32_t c = 0; c < hidden_dim_; ++c) {
      std::memcpy(&z_buf[c * dec_seq_len_],
                  &z[c * upsampled_max_seq_len_],
                  first_copy_len * sizeof(float));
    }

    decoder_model_->SetInputTensorData("z", z_buf.data(), z_buf.size());
    if (!decoder_model_->Run()) {
      SHERPA_ONNX_LOGE("Failed to run Piper QNN decoder on first chunk");
      return {};
    }
    std::vector<float> audio_chunk =
        decoder_model_->GetOutputTensorData("audio");
    int32_t first_samples = max_dec_seq_len * upsample_factor_;
    if (static_cast<int32_t>(audio_chunk.size()) >= first_samples) {
      audio.insert(audio.end(), audio_chunk.begin(),
                   audio_chunk.begin() + first_samples);
    }

    int32_t total_dec_seq_len = max_dec_seq_len;
    int32_t limit =
        std::min(y_length, upsampled_max_seq_len_ - max_dec_seq_len - dec_overlap);

    while (total_dec_seq_len < limit) {
      std::fill(z_buf.begin(), z_buf.end(), 0.0f);
      int32_t start_frame = total_dec_seq_len - dec_overlap;
      for (int32_t c = 0; c < hidden_dim_; ++c) {
        std::memcpy(&z_buf[c * dec_seq_len_],
                    &z[c * upsampled_max_seq_len_ + start_frame],
                    dec_seq_len_ * sizeof(float));
      }

      decoder_model_->SetInputTensorData("z", z_buf.data(), z_buf.size());
      if (!decoder_model_->Run()) {
        SHERPA_ONNX_LOGE("Failed to run Piper QNN decoder on chunk at %d",
                         total_dec_seq_len);
        break;
      }

      audio_chunk = decoder_model_->GetOutputTensorData("audio");
      int32_t slice_start = dec_overlap * upsample_factor_;
      int32_t slice_end = (max_dec_seq_len + dec_overlap) * upsample_factor_;
      if (static_cast<int32_t>(audio_chunk.size()) >= slice_end) {
        audio.insert(audio.end(), audio_chunk.begin() + slice_start,
                     audio_chunk.begin() + slice_end);
      }

      total_dec_seq_len += max_dec_seq_len;
    }

    int32_t target_samples = y_length * upsample_factor_;
    if (static_cast<int32_t>(audio.size()) > target_samples) {
      audio.resize(target_samples);
    }

    return audio;
  }

 private:
  std::mutex mutex_;
  OfflineTtsModelConfig config_;
  OfflineTtsVitsModelMetaData meta_data_;
  Ort::AllocatorWithDefaultOptions allocator_;

  int32_t max_seq_len_ = 512;
  int32_t hidden_dim_ = 192;
  int32_t upsampled_max_seq_len_ = 1536;
  int32_t dec_seq_len_ = 64;
  int32_t upsample_factor_ = 256;

  std::unique_ptr<QnnBackend> backend_;
  std::unique_ptr<QnnModel> encoder_model_;
  std::unique_ptr<QnnModel> sdp_model_;
  std::unique_ptr<QnnModel> flow_model_;
  std::unique_ptr<QnnModel> decoder_model_;
};

OfflineTtsVitsModelQnn::~OfflineTtsVitsModelQnn() = default;

OfflineTtsVitsModelQnn::OfflineTtsVitsModelQnn(
    const OfflineTtsModelConfig &config)
    : impl_(std::make_unique<Impl>(config)) {}

template <typename Manager>
OfflineTtsVitsModelQnn::OfflineTtsVitsModelQnn(
    Manager *mgr, const OfflineTtsModelConfig &config)
    : impl_(std::make_unique<Impl>(mgr, config)) {}

Ort::Value OfflineTtsVitsModelQnn::Run(Ort::Value x, int64_t sid /*= 0*/,
                                       float speed /*= 1.0*/,
                                       int64_t emotion_id /*= 0*/) {
  return impl_->Run(std::move(x), sid, speed, emotion_id);
}

Ort::Value OfflineTtsVitsModelQnn::Run(Ort::Value x, Ort::Value tones,
                                       int64_t sid /*= 0*/,
                                       float speed /*= 1.0*/) const {
  return impl_->Run(std::move(x), std::move(tones), sid, speed);
}

const OfflineTtsVitsModelMetaData &OfflineTtsVitsModelQnn::GetMetaData() const {
  return impl_->GetMetaData();
}

#if __ANDROID_API__ >= 9
template OfflineTtsVitsModelQnn::OfflineTtsVitsModelQnn(
    AAssetManager *mgr, const OfflineTtsModelConfig &config);
#endif

#if __OHOS__
template OfflineTtsVitsModelQnn::OfflineTtsVitsModelQnn(
    NativeResourceManager *mgr, const OfflineTtsModelConfig &config);
#endif

}  // namespace sherpa_onnx
