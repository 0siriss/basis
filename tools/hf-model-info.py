#!/usr/bin/env python3
"""Prints GGUF/ONNX files with sizes and SHA-256 for candidate models (run in CI; HF is not reachable from the dev sandbox)."""
import json, sys, urllib.request, urllib.parse

def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "basis-ci"}), timeout=60) as r:
        return json.load(r)

SEARCHES = [
    "Qwen3.5-4B GGUF", "Qwen3.5-2B GGUF", "Qwen3.5 GGUF", "gemma-4 E4B GGUF", "gemma-4 GGUF",
    "Qwen3-4B-Instruct-2507 GGUF", "multilingual-e5-small gguf", "multilingual-e5-small onnx",
]
PREFERRED_AUTHORS = ("Qwen", "ggml-org", "unsloth", "bartowski", "google", "intfloat", "lmstudio-community")
seen = set()
for q in SEARCHES:
    try:
        models = get("https://huggingface.co/api/models?" + urllib.parse.urlencode({"search": q, "limit": 25, "sort": "downloads"}))
    except Exception as e:
        print(f"## search '{q}' failed: {e}"); continue
    ids = [m["id"] for m in models]
    print(f"## search '{q}': {ids}")
    for mid in ids:
        if mid in seen or not mid.startswith(PREFERRED_AUTHORS) and "e5" not in mid:
            continue
        seen.add(mid)
        try:
            tree = get(f"https://huggingface.co/api/models/{mid}/tree/main?recursive=1")
        except Exception as e:
            print(f"   {mid}: tree failed {e}"); continue
        for f in tree:
            p = f.get("path", "")
            if not (p.endswith(".gguf") or p.endswith(".onnx") or p.endswith("tokenizer.json")):
                continue
            if p.endswith(".gguf") and not any(k in p for k in ("Q4_K_M", "Q8_0", "q4_k_m", "q8_0", "f16", "F16")):
                continue
            lfs = f.get("lfs") or {}
            print(f"   {mid} | {p} | {f.get('size')} | {lfs.get('oid', '-')}")
    sys.stdout.flush()
