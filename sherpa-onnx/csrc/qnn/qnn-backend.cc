// sherpa-onnx/csrc/qnn/qnn-backend.cc
//
// Copyright (c)  2025  Xiaomi Corporation

#include "sherpa-onnx/csrc/qnn/qnn-backend.h"

#include <dlfcn.h>
#include <stdio.h>

#include <cstdint>
#include <memory>
#include <sstream>
#include <string>
#include <vector>

#include "HTP/QnnHtpDevice.h"
#include "HTP/QnnHtpPerfInfrastructure.h"
#include "QnnInterface.h"
#include "System/QnnSystemInterface.h"
#include "sherpa-onnx/csrc/qnn/macros.h"
#include "sherpa-onnx/csrc/qnn/utils.h"

namespace sherpa_onnx {

class QnnBackend::Impl {
 public:
  Impl(const std::string &backend_lib, const QnnBackendOptions &opts)
      : opts_(opts) {
    bool ok = InitQnnInterface(backend_lib);
    if (!ok) {
      SHERPA_ONNX_LOGE("Failed to init qnn interface from '%s'",
                       backend_lib.c_str());
      return;
    }

    InitLog();
    InitBackend();
    InitDevice();

    if (opts_.htp_perf_mode) {
      InitHtpPerfMode();
    }

    is_initialized_ = true;
  }

  ~Impl() {
    for (auto it = context_handles_.rbegin(); it != context_handles_.rend(); ++it) {
      if (*it) {
        auto ret = qnn_interface_.contextFree(*it, nullptr);
        if (ret != QNN_SUCCESS) {
          SHERPA_ONNX_LOGE("Failed to call contextFree: %d", static_cast<int>(ret));
        }
      }
    }
    context_handles_.clear();
    context_handle_ = nullptr;

    if (htp_perf_infra_ && htp_power_config_id_ != 0) {
      auto ret = htp_perf_infra_->destroyPowerConfigId(htp_power_config_id_);
      if (ret != QNN_SUCCESS) {
        SHERPA_ONNX_LOGE("destroyPowerConfigId failed: %d",
                         static_cast<int>(ret));
      }
    }

    if (device_handle_) {
      auto ret = qnn_interface_.deviceFree(device_handle_);
      if (ret != QNN_SUCCESS) {
        SHERPA_ONNX_LOGE("Failed to call deviceFree: %d", static_cast<int>(ret));
      }
    }

    if (backend_handle_) {
      auto ret = qnn_interface_.backendFree(backend_handle_);
      if (ret != QNN_SUCCESS) {
        SHERPA_ONNX_LOGE("Failed to call backendFree: %d", static_cast<int>(ret));
      }
    }

    if (log_handle_) {
      auto ret = qnn_interface_.logFree(log_handle_);
      if (ret != QNN_SUCCESS) {
        SHERPA_ONNX_LOGE("Failed to call logFree: %d", static_cast<int>(ret));
      }
    }
  }

  void InitContext() {
    if (context_handle_) {
      SHERPA_ONNX_LOGE("context handle is already initialized");
      return;
    }

    auto ret = qnn_interface_.contextCreate(backend_handle_, device_handle_,
                                            context_config_, &context_handle_);
    SHERPA_ONNX_QNN_CHECK(ret, "Failed to call contextCreate");
    if (context_handle_) {
      context_handles_.push_back(context_handle_);
    }
  }

  void InitContext(Qnn_ContextHandle_t t) {
    context_handle_ = t;
    if (t) {
      context_handles_.push_back(t);
    }
  }

  Qnn_LogHandle_t LogHandle() const { return log_handle_; }

  Qnn_BackendHandle_t BackendHandle() const { return backend_handle_; }

  Qnn_DeviceHandle_t DeviceHandle() const { return device_handle_; }

  Qnn_ContextHandle_t ContextHandle() const { return context_handle_; }

  QNN_INTERFACE_VER_TYPE QnnInterface() const { return qnn_interface_; }

  QnnLog_Level_t LogLevel() const { return log_level_; }

  bool IsInitialized() const { return is_initialized_; }

 private:
  bool InitQnnInterface(const std::string &backend_lib) {
    backend_lib_handle_ = std::unique_ptr<void, decltype(&dlclose)>(
        dlopen(backend_lib.c_str(), RTLD_NOW | RTLD_LOCAL), &dlclose);
    if (!backend_lib_handle_) {
      SHERPA_ONNX_LOGE("Failed to dlopen '%s'. Error is: '%s'",
                       backend_lib.c_str(), dlerror());
      return false;
    }

    if (opts_.debug) {
      SHERPA_ONNX_LOGE("loaded %s", backend_lib.c_str());
    }

    const char *symbol = "QnnInterface_getProviders";
    auto get_interface_providers =
        reinterpret_cast<QnnInterfaceGetProvidersFnType>(
            dlsym(backend_lib_handle_.get(), symbol));
    if (!get_interface_providers) {
      SHERPA_ONNX_LOGE("Failed to dlsym for '%s'. Error is: '%s'", symbol,
                       dlerror());
      return false;
    }

    if (opts_.debug) {
      SHERPA_ONNX_LOGE("Got %s", symbol);
    }

    const QnnInterface_t **interface_providers = nullptr;
    uint32_t num_providers = 0;

    auto ret = get_interface_providers(&interface_providers, &num_providers);
    SHERPA_ONNX_QNN_CHECK(ret, "Failed to call get_interface_providers");

    if (!interface_providers) {
      SHERPA_ONNX_LOGE("interface_providers is nullptr");
      return false;
    }

    if (num_providers == 0) {
      SHERPA_ONNX_LOGE("Number of providers is 0");
      return false;
    }

    bool found_valid_interface = false;

    if (opts_.debug) {
      SHERPA_ONNX_LOGE("QNN_API_VERSION_MAJOR: %d", QNN_API_VERSION_MAJOR);
      SHERPA_ONNX_LOGE("QNN_API_VERSION_MINOR: %d", QNN_API_VERSION_MINOR);
      SHERPA_ONNX_LOGE("QNN_API_VERSION_PATCH: %d", QNN_API_VERSION_PATCH);
    }

    for (size_t idx = 0; idx < num_providers; ++idx) {
      auto p = interface_providers[idx];

      if (opts_.debug) {
        std::ostringstream os;
        os << "---" << idx << "----\n";
        os << "backendId: " << p->backendId << "\n";
        os << "coreApiVersion.major: " << p->apiVersion.coreApiVersion.major
           << "\n";
        os << "coreApiVersion.minor: " << p->apiVersion.coreApiVersion.minor
           << "\n";
        os << "coreApiVersion.patch: " << p->apiVersion.coreApiVersion.patch
           << "\n";

        os << "backendApiVersion.major: "
           << p->apiVersion.backendApiVersion.major << "\n";
        os << "backendApiVersion.minor: "
           << p->apiVersion.backendApiVersion.minor << "\n";
        os << "backendApiVersion.patch: "
           << p->apiVersion.backendApiVersion.patch << "\n";
        SHERPA_ONNX_LOGE("%s", os.str().c_str());
      }

      qnn_interface_ = p->QNN_INTERFACE_VER_NAME;
      found_valid_interface = true;
      break;
    }

    if (!found_valid_interface) {
      SHERPA_ONNX_LOGE("Failed to find valid interface");
      return false;
    }

    if (opts_.debug) {
      const char *build_id = nullptr;
      ret = qnn_interface_.backendGetBuildId(&build_id);
      SHERPA_ONNX_QNN_CHECK(ret, "Failed to call backendGetBuildId()");

      SHERPA_ONNX_LOGE("backend build ID: %s", build_id);
    }

    return true;
  }

  void InitLog() {
    auto ret = qnn_interface_.logCreate(LogCallback, log_level_, &log_handle_);
    SHERPA_ONNX_QNN_CHECK(ret, "Failed to call logCreate");
  }

  void InitBackend() {
    auto ret = qnn_interface_.backendCreate(log_handle_, backend_config_,
                                            &backend_handle_);
    SHERPA_ONNX_QNN_CHECK(ret, "Failed to call backendCreate");
  }

  void InitDevice() {
    // Attempt 1: HTP config with unsigned process domain and Hexagon V79 arch
    QnnHtpDevice_CustomConfig_t custom_signed_pd{};
    custom_signed_pd.option = QNN_HTP_DEVICE_CONFIG_OPTION_SIGNEDPD;
    custom_signed_pd.useSignedProcessDomain.deviceId = 0;
    custom_signed_pd.useSignedProcessDomain.useSignedProcessDomain = false;

    QnnHtpDevice_CustomConfig_t custom_arch{};
    custom_arch.option = QNN_HTP_DEVICE_CONFIG_OPTION_ARCH;
    custom_arch.arch.deviceId = 0;
    custom_arch.arch.arch = QNN_HTP_DEVICE_ARCH_V79;

    QnnDevice_Config_t dev_cfg_signed_pd{};
    dev_cfg_signed_pd.option = QNN_DEVICE_CONFIG_OPTION_CUSTOM;
    dev_cfg_signed_pd.customConfig = &custom_signed_pd;

    QnnDevice_Config_t dev_cfg_arch{};
    dev_cfg_arch.option = QNN_DEVICE_CONFIG_OPTION_CUSTOM;
    dev_cfg_arch.customConfig = &custom_arch;

    const QnnDevice_Config_t *configs_v79[] = {
        &dev_cfg_signed_pd,
        &dev_cfg_arch,
        nullptr
    };

    auto ret = qnn_interface_.deviceCreate(log_handle_, configs_v79, &device_handle_);
    if (ret == QNN_SUCCESS && device_handle_ != nullptr) {
      SHERPA_ONNX_LOGE("deviceCreate succeeded with V79 + unsigned PD config");
      return;
    }
    SHERPA_ONNX_LOGE("deviceCreate with V79+unsigned PD failed: %d", static_cast<int>(ret));

    // Attempt 2: Explicit SoC configuration (SM8750 = 69) with unsigned PD
    QnnHtpDevice_CustomConfig_t custom_soc{};
    custom_soc.option = QNN_HTP_DEVICE_CONFIG_OPTION_SOC;
    custom_soc.socModel = 69;

    QnnDevice_Config_t dev_cfg_soc{};
    dev_cfg_soc.option = QNN_DEVICE_CONFIG_OPTION_CUSTOM;
    dev_cfg_soc.customConfig = &custom_soc;

    const QnnDevice_Config_t *configs_soc[] = {
        &dev_cfg_signed_pd,
        &dev_cfg_soc,
        nullptr
    };
    ret = qnn_interface_.deviceCreate(log_handle_, configs_soc, &device_handle_);
    if (ret == QNN_SUCCESS && device_handle_ != nullptr) {
      SHERPA_ONNX_LOGE("deviceCreate succeeded with SM8750 + unsigned PD config");
      return;
    }
    SHERPA_ONNX_LOGE("deviceCreate with SM8750+unsigned PD failed: %d", static_cast<int>(ret));

    // Attempt 3: Try unsigned PD alone
    const QnnDevice_Config_t *configs_pd_only[] = {
        &dev_cfg_signed_pd,
        nullptr
    };
    ret = qnn_interface_.deviceCreate(log_handle_, configs_pd_only, &device_handle_);
    if (ret == QNN_SUCCESS && device_handle_ != nullptr) {
      SHERPA_ONNX_LOGE("deviceCreate succeeded with unsigned PD config");
      return;
    }
    SHERPA_ONNX_LOGE("deviceCreate with unsigned PD failed: %d", static_cast<int>(ret));

    // Attempt 4: Nullptr config (default)
    ret = qnn_interface_.deviceCreate(log_handle_, nullptr, &device_handle_);
    if (ret == QNN_SUCCESS && device_handle_ != nullptr) {
      SHERPA_ONNX_LOGE("deviceCreate succeeded with null config");
      return;
    }
    SHERPA_ONNX_LOGE("deviceCreate with null config returned %d", static_cast<int>(ret));

    // If deviceCreate fails on this commercial Android firmware, device_handle_
    // remains nullptr. In QNN, contextCreateFromBinary accepts nullptr as device
    // handle (hardware affinity is extracted directly from context binary).
    device_handle_ = nullptr;
    SHERPA_ONNX_LOGE("Proceeding with device_handle_ = nullptr (hardware affinity handled by context binary).");
  }

  // Pin the HTP (NPU DSP) to max clocks for the lifetime of this backend.
  //
  // - DCVS v3 with DCVS disabled, PERFORMANCE_MODE, all voltage corners
  //   pinned to MAX_VOLTAGE_CORNER.
  //
  // - RPC polling kept hot so autoregressive decoders don't pay the
  //   wake-up cost on every step.
  //
  // Pattern referenced from:
  // https://docs.qualcomm.com/nav/home/htp_backend.html?product=924033590759186372
  void InitHtpPerfMode() {
    QnnDevice_Infrastructure_t device_infra = nullptr;
    auto ret = qnn_interface_.deviceGetInfrastructure(&device_infra);
    if (ret != QNN_SUCCESS || device_infra == nullptr) {
      SHERPA_ONNX_LOGE(
          "deviceGetInfrastructure failed (%d): skipping HTP perf config. "
          "HTP will run at default clocks.",
          static_cast<int>(ret));
      return;
    }

    auto *htp_infra =
        static_cast<QnnHtpDevice_Infrastructure_t *>(device_infra);
    htp_perf_infra_ = &htp_infra->perfInfra;

    ret = htp_perf_infra_->createPowerConfigId(/*deviceId=*/0, /*coreId=*/0,
                                               &htp_power_config_id_);
    if (ret != QNN_SUCCESS) {
      SHERPA_ONNX_LOGE("createPowerConfigId failed: %d",
                       static_cast<int>(ret));
      htp_perf_infra_ = nullptr;
      return;
    }

    QnnHtpPerfInfrastructure_PowerConfig_t dcvs{};
    dcvs.option = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_DCVS_V3;
    dcvs.dcvsV3Config.contextId = htp_power_config_id_;
    dcvs.dcvsV3Config.setDcvsEnable = 1;
    dcvs.dcvsV3Config.dcvsEnable = 0;
    dcvs.dcvsV3Config.powerMode =
        QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_PERFORMANCE_MODE;
    dcvs.dcvsV3Config.setSleepLatency = 1;
    dcvs.dcvsV3Config.sleepLatency = 40;
    dcvs.dcvsV3Config.setBusParams = 1;
    dcvs.dcvsV3Config.setCoreParams = 1;
    dcvs.dcvsV3Config.busVoltageCornerMin =
        DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
    dcvs.dcvsV3Config.busVoltageCornerTarget =
        DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
    dcvs.dcvsV3Config.busVoltageCornerMax =
        DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
    dcvs.dcvsV3Config.coreVoltageCornerMin =
        DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
    dcvs.dcvsV3Config.coreVoltageCornerTarget =
        DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;
    dcvs.dcvsV3Config.coreVoltageCornerMax =
        DCVS_VOLTAGE_VCORNER_MAX_VOLTAGE_CORNER;

    QnnHtpPerfInfrastructure_PowerConfig_t rpc_polling_time{};
    rpc_polling_time.option =
        QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_RPC_POLLING_TIME;
    rpc_polling_time.rpcPollingTimeConfig = 9999;  // microseconds

    QnnHtpPerfInfrastructure_PowerConfig_t rpc_control_latency{};
    rpc_control_latency.option =
        QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_RPC_CONTROL_LATENCY;
    rpc_control_latency.rpcControlLatencyConfig = 100;  // microseconds

    const QnnHtpPerfInfrastructure_PowerConfig_t *configs[] = {
        &dcvs, &rpc_polling_time, &rpc_control_latency, nullptr};
    ret = htp_perf_infra_->setPowerConfig(htp_power_config_id_, configs);
    if (ret != QNN_SUCCESS) {
      SHERPA_ONNX_LOGE("setPowerConfig failed: %d", static_cast<int>(ret));
    } else if (opts_.debug) {
      SHERPA_ONNX_LOGE(
          "HTP perf: PERFORMANCE_MODE, DCVS disabled, max voltage corner");
    }
  }

 private:
  QnnBackendOptions opts_;
  std::unique_ptr<void, decltype(&dlclose)> backend_lib_handle_{nullptr,
                                                                &dlclose};

  QNN_INTERFACE_VER_TYPE qnn_interface_;

  QnnLog_Level_t log_level_ = QNN_LOG_LEVEL_WARN;
  // QnnLog_Level_t log_level_ = QNN_LOG_LEVEL_INFO;
  // QnnLog_Level_t log_level_ = QNN_LOG_LEVEL_VERBOSE;

  Qnn_LogHandle_t log_handle_ = nullptr;

  const QnnBackend_Config_t **backend_config_ = nullptr;
  Qnn_BackendHandle_t backend_handle_ = nullptr;

  Qnn_DeviceHandle_t device_handle_ = nullptr;

  Qnn_ContextHandle_t context_handle_ = nullptr;
  std::vector<Qnn_ContextHandle_t> context_handles_;
  const QnnContext_Config_t **context_config_ = nullptr;
  bool is_initialized_ = false;

  QnnHtpDevice_PerfInfrastructure_t *htp_perf_infra_ = nullptr;
  uint32_t htp_power_config_id_ = 0;
};

QnnBackend::~QnnBackend() = default;

QnnBackend::QnnBackend(const std::string &backend_lib,
                       const QnnBackendOptions &opts)
    : impl_(std::make_unique<Impl>(backend_lib, opts)) {}

void QnnBackend::InitContext() const { impl_->InitContext(); }

void QnnBackend::InitContext(Qnn_ContextHandle_t context_handle) const {
  impl_->InitContext(context_handle);
}

Qnn_LogHandle_t QnnBackend::LogHandle() const { return impl_->LogHandle(); }

Qnn_BackendHandle_t QnnBackend::BackendHandle() const {
  return impl_->BackendHandle();
}

Qnn_DeviceHandle_t QnnBackend::DeviceHandle() const {
  return impl_->DeviceHandle();
}

Qnn_ContextHandle_t QnnBackend::ContextHandle() const {
  return impl_->ContextHandle();
}

QNN_INTERFACE_VER_TYPE QnnBackend::QnnInterface() const {
  return impl_->QnnInterface();
}

QnnLog_Level_t QnnBackend::LogLevel() const { return impl_->LogLevel(); }

bool QnnBackend::IsInitialized() const { return impl_->IsInitialized(); }

}  // namespace sherpa_onnx
