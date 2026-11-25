include_directories(${PROJECT_SOURCE_DIR})

set(sources
  base64-decode.cc
  bbpe.cc
  character-lexicon.cc
  file-utils.cc
  fst-utils.cc
  lexicon.cc
  melo-tts-lexicon.cc
  offline-tts-character-frontend.cc
  offline-tts-frontend.cc
  offline-tts-impl.cc
  offline-tts-model-config.cc
  offline-tts-vits-model-config.cc
  offline-tts-vits-model.cc
  offline-tts.cc
  onnx-utils.cc
  parse-options.cc
  phrase-matcher.cc
  provider-config.cc
  provider.cc
  qnn-config.cc
  session.cc
  symbol-table.cc
  text-utils.cc
  tts-text-normalizer.cc
  version.cc
  wave-writer.cc
)

if(SHERPA_ONNX_ENABLE_QNN)
  list(APPEND sources
    ./qnn/offline-tts-vits-model-qnn.cc
    ./qnn/qnn-backend.cc
    ./qnn/qnn-model.cc
    ./qnn/utils.cc
  )
endif()

if(SHERPA_ONNX_ENABLE_CHECK)
  list(APPEND sources log.cc)
endif()

add_library(sherpa-onnx-core STATIC ${sources})

set_target_properties(
  sherpa-onnx-core
  PROPERTIES
    POSITION_INDEPENDENT_CODE ON
    C_VISIBILITY_PRESET hidden
    CXX_VISIBILITY_PRESET hidden
)

if(ANDROID)
  target_link_libraries(sherpa-onnx-core android log)
endif()

target_link_libraries(sherpa-onnx-core
  kaldifst_core
  utf8proc
  fstfar
  fst
)

if(SHERPA_ONNX_ENABLE_QNN)
  target_include_directories(sherpa-onnx-core PRIVATE ${QNN_SDK_ROOT}/include/QNN)
endif()

if(TARGET onnxruntime)
  target_link_libraries(sherpa-onnx-core onnxruntime)
else()
  target_link_libraries(sherpa-onnx-core ${onnxruntime_lib_files})
endif()

if(NOT WIN32)
  target_link_libraries(sherpa-onnx-core -lm)
endif()

if(NOT BUILD_SHARED_LIBS)
  install(TARGETS sherpa-onnx-core DESTINATION lib)
endif()
