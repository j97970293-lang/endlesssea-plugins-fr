#!/usr/bin/env python3
"""Génère repo/index.json au format attendu par Endless Sea.

Le schéma est celui de `dev.endlesssea.extensions.api.manifest` :

    RepositoryIndex    { name, description, url, extensions[] }
    RepoExtensionEntry { id, name, version, versionName, apiVersion, author,
                         description, languages, types, permissions,
                         minAppVersion, size, iconUrl, apkUrl, sha256, kind,
                         nsfw }

`apkUrl` et `sha256` sont **obligatoires** : l'empreinte est calculée sur le
.esx réellement construit. Les fichiers sont cherchés dans --esx-dir ; si un
.esx manque, son empreinte est reprise de l'index existant (afin de ne pas
casser l'index quand on régénère sans tout reconstruire).

Usage :
    python3 tools/build-index.py [--esx-dir build/esx] [--base-url URL]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
REPO_URL = "https://github.com/j97970293-lang/endlesssea-plugins-fr"
DEFAULT_BASE = f"{REPO_URL}/releases/latest/download"
OUT = ROOT / "repo" / "index.json"


def previous_entries() -> dict[str, dict]:
    if not OUT.exists():
        return {}
    try:
        data = json.loads(OUT.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return {}
    return {e.get("id"): e for e in data.get("extensions", [])}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--esx-dir", default="build/esx")
    ap.add_argument("--base-url", default=DEFAULT_BASE)
    args = ap.parse_args()

    esx_dir = (ROOT / args.esx_dir) if not pathlib.Path(args.esx_dir).is_absolute() else pathlib.Path(args.esx_dir)
    old = previous_entries()

    extensions = []
    missing = []
    for manifest in sorted(ROOT.glob("extensions/*/src/main/assets/extension.json")):
        meta = json.loads(manifest.read_text(encoding="utf-8"))
        module = manifest.parents[3].name
        file_name = f"{module}-{meta['versionName']}.esx"
        esx = esx_dir / file_name

        if esx.is_file():
            blob = esx.read_bytes()
            sha256 = hashlib.sha256(blob).hexdigest()
            size = len(blob)
        else:
            prev = old.get(meta["id"], {})
            sha256 = prev.get("sha256", "")
            size = prev.get("size", 0)
            if not sha256:
                missing.append(file_name)

        extensions.append(
            {
                "id": meta["id"],
                "name": meta["name"],
                "version": meta["version"],
                "versionName": meta["versionName"],
                "apiVersion": meta.get("apiVersion", 1),
                "author": meta.get("author", {"name": "j97970293-lang", "url": REPO_URL}),
                "description": meta.get("description", {}),
                "languages": meta.get("languages", []),
                "types": meta.get("types", []),
                "permissions": meta.get("permissions", []),
                "minAppVersion": 1,
                "size": size,
                "iconUrl": meta.get("iconUrl"),
                "apkUrl": f"{args.base_url}/{file_name}",
                "sha256": sha256,
                "kind": "COMPILED",
                "nsfw": meta.get("nsfw", False),
            }
        )

    index = {
        "name": "Endless Sea · Extensions FR",
        "description": "Sources francophones (films, séries, animes, TV) pour Endless Sea.",
        "url": REPO_URL,
        "extensions": extensions,
    }

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(index, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"→ {OUT.relative_to(ROOT)} ({len(extensions)} extensions)")
    if missing:
        print("⚠ .esx introuvables, empreinte vide : " + ", ".join(missing))
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
