#!/usr/bin/env python3
"""Génère repo/index.json à partir des extension.json de chaque module.

Usage :
    python3 tools/build-index.py [--base-url URL]

`--base-url` est la base de téléchargement des .esx (par défaut la release
« latest » du dépôt GitHub). Le fichier produit est l'index que l'application
Endless Sea consomme pour lister et installer les extensions.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import datetime

ROOT = pathlib.Path(__file__).resolve().parent.parent
REPO_URL = "https://github.com/j97970293-lang/endlesssea-plugins-fr"
DEFAULT_BASE = f"{REPO_URL}/releases/latest/download"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default=DEFAULT_BASE)
    args = ap.parse_args()

    extensions = []
    for manifest in sorted(ROOT.glob("extensions/*/src/main/assets/extension.json")):
        meta = json.loads(manifest.read_text(encoding="utf-8"))
        module = manifest.parents[3].name
        file_name = f"{module}-{meta['versionName']}.esx"
        extensions.append(
            {
                "id": meta["id"],
                "name": meta["name"],
                "version": meta["version"],
                "versionName": meta["versionName"],
                "apiVersion": meta.get("apiVersion", 1),
                "description": meta.get("description", {}),
                "author": meta.get("author", {}),
                "languages": meta.get("languages", []),
                "types": meta.get("types", []),
                "iconUrl": meta.get("iconUrl"),
                "entryClass": meta["entryClass"],
                "permissions": meta.get("permissions", []),
                "capabilities": meta.get("capabilities", {}),
                "nsfw": meta.get("nsfw", False),
                "sourceUrl": meta.get("sourceUrl", REPO_URL),
                "downloadUrl": f"{args.base_url}/{file_name}",
                "fileName": file_name,
            }
        )

    index = {
        "name": "Endless Sea · Extensions FR",
        "description": "Sources francophones (films, séries, animes, TV) pour Endless Sea.",
        "language": "fr",
        "apiVersion": 1,
        "updatedAt": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "repositoryUrl": REPO_URL,
        "extensions": extensions,
    }

    out = ROOT / "repo" / "index.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(index, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"→ {out.relative_to(ROOT)} ({len(extensions)} extensions)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
