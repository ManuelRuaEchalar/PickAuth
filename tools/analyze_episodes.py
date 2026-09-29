#!/usr/bin/env python3
"""
Análisis offline de los episodios capturados por PickupAuth (fases 1-3).

Uso:
    adb pull /sdcard/Android/data/edu.pickupauth/files ./datos
    python analyze_episodes.py ./datos            # resumen + validación
    python analyze_episodes.py ./datos --plot 5   # además grafica los 5 últimos episodios

Qué reporta:
  * cobertura: ¿el episodio tiene datos IMU desde el inicio de la ventana? (clave para elegir modo de captura)
  * huecos máximos por sensor
  * frecuencia efectiva de muestreo
  * matriz origen detectado vs. etiqueta del usuario (precisión del detector de "sacado del bolsillo")
  * falsos disparos por hora (desde stats.csv)
"""
import argparse
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np
import pandas as pd

LABEL_TO_ORIGIN = {
    "pocket_front": "POCKET_OR_BAG", "pocket_back": "POCKET_OR_BAG", "jacket": "POCKET_OR_BAG",
    "bag": "POCKET_OR_BAG", "table": "SURFACE", "hand": "HAND",
}


def load_episode(d: Path) -> dict:
    ep = {"dir": d, "meta": json.loads((d / "meta.json").read_text())}
    lab = d / "label.json"
    ep["label"] = json.loads(lab.read_text())["origin_label"] if lab.exists() else None
    for name in ("acc", "gyr", "mag", "prs", "lux", "prox"):
        f = d / f"{name}.csv"
        if f.exists():
            ep[name] = pd.read_csv(f)
    f = d / "events.csv"
    ep["events"] = pd.read_csv(f) if f.exists() else pd.DataFrame(columns=["t_ns", "name", "value"])
    return ep


def coverage_row(ep: dict) -> dict:
    m = ep["meta"]
    w0, t_unlock = m["window_from_ns"], m.get("t_user_present_ns", 0)
    row = {
        "episode": ep["dir"].name, "kind": m["kind"], "mode": m["config"]["mode"],
        "origin_detected": m.get("origin_detected"), "label": ep["label"],
        "screen_to_unlock_ms": m.get("screen_to_unlock_ms"),
    }
    for s in ("acc", "gyr", "mag"):
        df = ep.get(s)
        if df is None or df.empty:
            row[f"{s}_hz"] = 0; row[f"{s}_late_s"] = np.nan; row[f"{s}_maxgap_ms"] = np.nan
            continue
        t = df["t_ns"].to_numpy()
        dt = np.diff(t)
        row[f"{s}_hz"] = round(1e9 / np.median(dt), 1) if len(dt) else 0
        # Segundos de la ventana que faltan al inicio (0 = cobertura completa).
        row[f"{s}_late_s"] = round((t[0] - w0) / 1e9, 2)
        row[f"{s}_maxgap_ms"] = round(dt.max() / 1e6, 1) if len(dt) else np.nan
        if t_unlock:
            row[f"{s}_pre_unlock_s"] = round((t_unlock - max(t[0], w0)) / 1e9, 2)
    return row


def plot_episode(ep: dict, out: Path):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    m = ep["meta"]
    t0 = m.get("t_user_present_ns") or m["window_to_ns"]
    fig, axes = plt.subplots(4, 1, figsize=(11, 9), sharex=True)
    for ax, s, unit in zip(axes[:3], ("acc", "gyr", "mag"), ("m/s²", "rad/s", "µT")):
        df = ep.get(s)
        if df is not None and not df.empty:
            tt = (df["t_ns"] - t0) / 1e9
            for c in ("x", "y", "z"):
                ax.plot(tt, df[c], lw=0.8, label=c)
            if s == "acc":
                ax.plot(tt, np.linalg.norm(df[["x", "y", "z"]].to_numpy(), axis=1), "k", lw=1, label="|a|")
        ax.set_ylabel(f"{s} ({unit})"); ax.legend(loc="upper left", fontsize=7)
    ax = axes[3]
    for s, col in (("lux", "tab:orange"), ("prox", "tab:purple")):
        df = ep.get(s)
        if df is not None and not df.empty:
            tt = list((df["t_ns"] - t0) / 1e9) + [(m["window_to_ns"] - t0) / 1e9]
            ax.step(tt, list(df["x"]) + [df["x"].iloc[-1]], where="post", color=col, label=s)
    ax.set_yscale("symlog"); ax.legend(loc="upper left", fontsize=7); ax.set_xlabel("s respecto al desbloqueo")

    marks = {"t_transition_ns": ("inicio toma", "tab:red"), "t_settled_ns": ("estabilizado", "tab:green"),
             "t_screen_on_ns": ("pantalla on", "tab:blue"), "t_user_present_ns": ("desbloqueo", "k")}
    for key, (lab, col) in marks.items():
        v = m.get(key, 0)
        if v:
            for a in axes:
                a.axvline((v - t0) / 1e9, color=col, ls="--", lw=1)
            axes[0].text((v - t0) / 1e9, axes[0].get_ylim()[1], lab, color=col, fontsize=8, rotation=90, va="top")
    fig.suptitle(f"{ep['dir'].name} · detectado={m.get('origin_detected')} · etiqueta={ep['label']}")
    fig.tight_layout()
    fig.savefig(out, dpi=110)
    plt.close(fig)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("root", type=Path, help="carpeta extraída con adb pull (contiene episodes/ y stats.csv)")
    ap.add_argument("--plot", type=int, default=0, help="graficar los N episodios más recientes")
    a = ap.parse_args()

    ep_root = a.root / "episodes"
    dirs = sorted(p for p in ep_root.glob("*") if (p / "meta.json").exists())
    if not dirs:
        sys.exit(f"No hay episodios en {ep_root}")
    eps = [load_episode(d) for d in dirs]

    cov = pd.DataFrame([coverage_row(e) for e in eps])
    pd.set_option("display.width", 200); pd.set_option("display.max_columns", 30)
    print(f"\n== {len(eps)} episodios ==")
    print(cov.groupby(["kind", "mode"]).size().rename("n").to_string())

    unl = cov[cov.kind == "unlock"]
    if len(unl):
        print("\n== Cobertura IMU en episodios de desbloqueo (por modo) ==")
        cols = [c for c in unl.columns if c.endswith(("_hz", "_late_s", "_maxgap_ms", "_pre_unlock_s"))]
        print(unl.groupby("mode")[cols].median().round(2).to_string())
        full = (unl["acc_late_s"] <= 0.1).mean()
        print(f"\nEpisodios con ventana previa completa (acc llega a tiempo): {full:.0%}")

        lab = unl.dropna(subset=["label"])
        if len(lab):
            lab = lab.assign(label_origin=lab["label"].map(LABEL_TO_ORIGIN).fillna("OTHER"))
            print("\n== Origen detectado vs. etiqueta del usuario ==")
            print(pd.crosstab(lab["label_origin"], lab["origin_detected"], margins=True).to_string())
            pocket = lab[lab.label_origin == "POCKET_OR_BAG"]
            if len(pocket):
                rec = (pocket.origin_detected == "POCKET_OR_BAG").mean()
                print(f"\nRecall del detector 'sacado de bolsillo/bolsa': {rec:.0%} (n={len(pocket)})")

    stats = a.root / "stats.csv"
    if stats.exists():
        st = pd.read_csv(stats)
        if len(st) > 1:
            hours = (st["wall_ms"].iloc[-1] - st["wall_ms"].iloc[0]) / 3.6e6
            ft = st["false_triggers"].iloc[-1] - st["false_triggers"].iloc[0]
            batt = st["battery_pct"].iloc[0] - st["battery_pct"].iloc[-1]
            print(f"\n== stats.csv: {hours:.1f} h ==")
            print(f"Falsos disparos/hora: {ft / max(hours, 1e-9):.2f}")
            print(f"Batería consumida (bruta, incluye uso normal): {batt} pts → {batt / max(hours, 1e-9):.2f} %/h")
            print("Huecos IMU acumulados (s):", {c: round(float(st[f'{c}_gap_s'].sum()), 1) for c in ('acc', 'gyr', 'mag') if f'{c}_gap_s' in st})

    if a.plot:
        out = a.root / "plots"; out.mkdir(exist_ok=True)
        for e in [e for e in eps if e["meta"]["kind"] == "unlock"][-a.plot:]:
            plot_episode(e, out / f"{e['dir'].name}.png")
        print(f"\nGráficos en {out}")

    cov.to_csv(a.root / "episodes_summary.csv", index=False)
    print(f"Resumen por episodio: {a.root / 'episodes_summary.csv'}")


if __name__ == "__main__":
    main()
