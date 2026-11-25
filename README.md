# Sherpa-ONNX Piper TTS — Samsung Galaxy S25 Ultra (Qualcomm Hexagon NPU V79)

Version épurée de `sherpa-onnx` dédiée **exclusivement à la synthèse vocale Piper / VITS sur le NPU Qualcomm Hexagon V79** du **Samsung Galaxy S25 Ultra** (Snapdragon 8 Elite / `SM8750-AC`, Android 16 / 17), avec **solution de secours automatique sur CPU (ONNX Runtime)** si un fichier `.onnx` est présent.

## Téléchargement direct de l'APK signé

- **APK Release signé (`arm64-v8a`, `targetSdk 36`, pages 16 Ko)** :
  https://github.com/kalorg/sherpa-onnx/raw/tts-s25-ultra-only/apks/sherpa-onnx-tts-qnn-s25-ultra-release-signed.apk

L'APK embarque à la fois :
1. **L'application de test** (*SherpaOnnxTts*) pour tester et partager l'audio généré.
2. **Le moteur TTS système Android (`TextToSpeechService`)** compatible Android 15 / 16 / 17 (API `Voice` moderne, codes langues ISO-2 `fr`/`en` et ISO-3 `fra`/`eng`, interruption instantanée `onStop()`), sélectionnable dans **Paramètres → Gestion globale → Synthèse vocale → Moteur préféré**.

---

## Architecture du dépôt épuré

Tout le code hors TTS (ASR, KWS, VAD, diarisation, débruitage, iOS, WASM, bindings Python/Go/Rust/C#/Dart/Java, autres architectures TTS et autres téléphones/ABI) a été supprimé :

- `sherpa-onnx/csrc/` : cœur C++ minimal (Piper/VITS + exécution 4 parties sur NPU QNN `encoder.bin`, `sdp.bin`, `flow.bin`, `decoder.bin` + fallback CPU ONNX Runtime).
- `sherpa-onnx/jni/` & `sherpa-onnx/kotlin-api/` : interface JNI/Kotlin minimale (`Tts.kt`, `QnnConfig.kt`).
- `android/SherpaOnnxTts/` : application principale + service TTS système Android (`TtsManager.kt`, `TtsService.kt`, `MainActivity.kt`).
- `android/SherpaOnnxTtsEngine/` : moteur TTS système Jetpack Compose.
- `scripts/qnn/export_piper_tts_qnn.py` : script officiel Qualcomm AI Hub (`qai_hub_models.models.piper_tts`) pour compiler n'importe quelle voix Piper française (`.ckpt` ou `.onnx` + `.onnx.json`) en 4 binaires NPU V79 pour le S25 Ultra.

---

## Ajouter une voix française (`fr_FR`) pour le NPU du S25 Ultra

```bash
pip install "qai-hub-models[piper-tts]"

python3 scripts/qnn/export_piper_tts_qnn.py \
  --onnx /chemin/vers/fr_FR-siwis-medium.onnx \
  --onnx-json /chemin/vers/fr_FR-siwis-medium.onnx.json \
  --device "Samsung Galaxy S25 Ultra" \
  --output-dir android/SherpaOnnxTts/app/src/main/assets/pipertts-fr-qnn-s25-ultra
```

Dès que le dossier `pipertts-fr-qnn-s25-ultra/` (contenant `encoder.bin`, `sdp.bin`, `flow.bin`, `decoder.bin`, `tokens.txt`, `config.json`, et optionnellement le `.onnx` en secours CPU) est présent dans `assets/` ou dans `/sdcard/Android/data/com.k2fsa.sherpa.onnx/files/`, `TtsManager` le détecte et bascule automatiquement en français (`fr-FR`).
