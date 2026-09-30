#!/usr/bin/env python3
"""
Junta los zips subidos a Drive en una carpeta por participante, lista para analyze_episodes.py.

Entrada: la carpeta de Drive descargada (por ejemplo con `rclone copy drive:PickupAuth ./drive`
o "Descargar" desde la web), con la forma  <participante>/<participante>_<fecha>.zip
Salida:  <salida>/<participante>/episodes/...  + stats.csv, lifecycle.csv, sensor_survey.json
         (de la subida más reciente) y manifests/ con el manifest de cada subida.

Uso:
    python tools/collect_drive.py ./drive ./datos_central
    python tools/analyze_episodes.py ./datos_central/S01 --plot 10

Es idempotente: se puede correr cada día sobre la misma salida; los zips se aplican en orden de fecha,
así una etiqueta que llegó tarde reemplaza a la ausencia de etiqueta.
"""
import argparse
import json
import zipfile
from pathlib import Path

LATEST_WINS = {"stats.csv", "lifecycle.csv", "sensor_survey.json"}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("drive", type=Path, help="carpeta con una subcarpeta por participante")
    ap.add_argument("out", type=Path, help="carpeta de salida")
    a = ap.parse_args()

    for subj_dir in sorted(p for p in a.drive.iterdir() if p.is_dir()):
        zips = sorted(subj_dir.glob("*.zip"))
        if not zips:
            continue
        dest = a.out / subj_dir.name
        (dest / "manifests").mkdir(parents=True, exist_ok=True)
        new_eps = 0
        for zp in zips:
            with zipfile.ZipFile(zp) as z:
                for info in z.infolist():
                    if info.is_dir():
                        continue
                    if info.filename == "manifest.json":
                        (dest / "manifests" / f"{zp.stem}.json").write_bytes(z.read(info))
                        continue
                    target = dest / info.filename
                    if info.filename.startswith("episodes/") and target.name == "meta.json" and not target.exists():
                        new_eps += 1
                    if info.filename in LATEST_WINS or info.filename.startswith("episodes/"):
                        target.parent.mkdir(parents=True, exist_ok=True)
                        target.write_bytes(z.read(info))
        n = len([d for d in (dest / "episodes").glob("*") if d.is_dir()]) if (dest / "episodes").exists() else 0
        last = json.loads((dest / "manifests" / f"{zips[-1].stem}.json").read_text())
        print(f"{subj_dir.name}: {len(zips)} subidas, {n} episodios ({new_eps} nuevos), "
              f"última {zips[-1].name}, app {last.get('app_version')}")


if __name__ == "__main__":
    main()
