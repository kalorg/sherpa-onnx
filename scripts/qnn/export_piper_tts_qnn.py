#!/usr/bin/env python3
"""
Export any Piper TTS voice (.ckpt or .onnx + .onnx.json) to the 4 Qualcomm QNN
Context Binaries (encoder.bin, sdp.bin, flow.bin, decoder.bin) + tokens.txt
ready for Sherpa-ONNX TTS Android on Samsung Galaxy S25 Ultra (Snapdragon 8 Elite / SM8750).

Usage examples:
1) From an existing Piper .onnx + .onnx.json voice:
   python3 scripts/qnn/export_piper_tts_qnn.py \
       --onnx /path/to/voice.onnx \
       --config /path/to/voice.onnx.json \
       --device "Samsung Galaxy S25 Ultra" \
       --output-dir ./pipertts-qnn-s25-ultra

2) From a Piper PyTorch Lightning .ckpt checkpoint:
   python3 scripts/qnn/export_piper_tts_qnn.py \
       --checkpoint /path/to/voice.ckpt \
       --config /path/to/voice.onnx.json \
       --device "Samsung Galaxy S25 Ultra" \
       --output-dir ./pipertts-qnn-s25-ultra
"""

import argparse
import json
import shutil
from pathlib import Path


def generate_tokens_txt(config_json_path: Path, output_tokens_path: Path) -> None:
    """Convert Piper .onnx.json phoneme_id_map to sherpa-onnx tokens.txt."""
    with open(config_json_path, "r", encoding="utf-8") as f:
        cfg = json.load(f)

    id_map = cfg.get("phoneme_id_map", {})
    with open(output_tokens_path, "w", encoding="utf-8") as out:
        for sym, ids in id_map.items():
            if sym in ("\n", "\r"):
                continue
            out.write(f"{sym} {ids[0]}\n")
    print(f"[+] Wrote {len(id_map)} tokens to {output_tokens_path}")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Export Piper TTS model to 4 Qualcomm QNN Context Binaries for Sherpa-ONNX"
    )
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument(
        "--checkpoint",
        type=Path,
        help="Path to Piper PyTorch Lightning checkpoint (.ckpt)",
    )
    group.add_argument(
        "--onnx",
        type=Path,
        help="Path to Piper ONNX model (.onnx)",
    )
    parser.add_argument(
        "--config",
        type=Path,
        required=True,
        help="Path to Piper voice config file (.onnx.json) used to generate tokens.txt",
    )
    parser.add_argument(
        "--device",
        type=str,
        default="Samsung Galaxy S25 Ultra",
        help="Target Qualcomm AI Hub device (default: 'Samsung Galaxy S25 Ultra')",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("./pipertts-qnn-s25-ultra"),
        help="Directory to save encoder.bin, sdp.bin, flow.bin, decoder.bin, and tokens.txt",
    )
    args = parser.parse_args()

    args.output_dir.mkdir(parents=True, exist_ok=True)
    generate_tokens_txt(args.config, args.output_dir / "tokens.txt")

    try:
        import qai_hub as hub
        import torch
        from piper_train.vits.lightning import VitsModel
        from qai_hub_models.models.templates.pipertts.model import (
            Decoder,
            Encoder,
            Flow,
            SDP,
        )
        from qai_hub_models.models.templates.pipertts.model_patch import (
            patch_rational_quadratic_spline,
        )
        from qai_hub_models.models.templates.pipertts.util import (
            build_model_from_onnx,
        )
    except ImportError as e:
        raise SystemExit(
            f"Missing dependency: {e}\n"
            "Please install qai-hub-models and piper:\n"
            "  pip install qai-hub-models\n"
            "  qai-hub-models install pipertts_en"
        ) from e

    patch_rational_quadratic_spline()

    if args.onnx is not None:
        print(f"[+] Reconstructing PyTorch SynthesizerTrn from ONNX: {args.onnx}")
        model_g = build_model_from_onnx(str(args.onnx), str(args.config))
    else:
        print(f"[+] Loading PyTorch SynthesizerTrn from checkpoint: {args.checkpoint}")
        vits_model = VitsModel.load_from_checkpoint(
            str(args.checkpoint),
            map_location="cpu",
            weights_only=False,
            dataset=None,
        )
        model_g = vits_model.model_g

    model_g.eval()

    components = {
        "encoder": (Encoder(model_g), "--target_runtime qnn_context_binary --truncate_64bit_tensors --truncate_64bit_io"),
        "sdp": (SDP(model_g), "--target_runtime qnn_context_binary"),
        "flow": (Flow(model_g), "--target_runtime qnn_context_binary"),
        "decoder": (Decoder(model_g), "--target_runtime qnn_context_binary --quantize_io"),
    }

    target_device = hub.Device(args.device)

    for name, (comp, compile_opts) in components.items():
        comp.eval()
        input_spec = comp.get_input_spec()
        sample_inputs = comp.sample_inputs()
        pt_inputs = tuple(
            torch.from_numpy(v[0]) for v in sample_inputs.values()
        )
        print(f"[+] Tracing {name} with input_spec={input_spec} ...")
        traced = torch.jit.trace(comp, pt_inputs)
        print(f"[+] Submitting compile job for {name} on '{args.device}' ...")
        job = hub.submit_compile_job(
            model=traced,
            device=target_device,
            input_specs=input_spec,
            options=compile_opts,
        )
        out_bin = args.output_dir / f"{name}.bin"
        print(f"[+] Waiting for {name} compile job ({job.job_id}) and downloading to {out_bin} ...")
        job.download_target_model(str(out_bin))

    print(f"\n[✓] Done! Model files for Sherpa-ONNX QNN TTS are in: {args.output_dir}")
    for p in sorted(args.output_dir.iterdir()):
        print(f"    - {p.name} ({p.stat().st_size / 1024:.1f} KB)")


if __name__ == "__main__":
    main()
