"""웹/앱과 동일 스펙 검증 (python)"""
import math
import sys
from pathlib import Path

# minimal port of calc
SIZES = {
    "wang": (70, 68),
    "teuk": (64, 60),
    "dae": (56, 52),
    "jung": (48, 44),
    "so": (40, 36),
}


def calc(size_id, count, price, mode="mid", custom=None, edible=0.89, exclude_shell=True):
    if mode == "custom":
        unit = custom
    elif mode == "min":
        unit = SIZES[size_id][1]
    else:
        unit = SIZES[size_id][0]
    ratio = edible if exclude_shell else 1.0
    total = unit * ratio * count
    per_g = price / total
    return {
        "unit": unit,
        "total": total,
        "per10": per_g * 10,
        "per_egg": price / count,
        "ratio": ratio,
    }


def approx(a, b, tol=0.5):
    return abs(a - b) <= tol


def main():
    r = calc("teuk", 30, 7800)
    assert r["unit"] == 64
    assert approx(r["total"], 1708.8, 0.01)
    assert approx(r["per10"], 45.65, 0.1), r["per10"]

    a = calc("teuk", 30, 7800)
    b = calc("dae", 30, 6900)
    print("특란 10g당 (껍질 제외):", round(a["per10"], 1), "원")
    print("대란 10g당 (껍질 제외):", round(b["per10"], 1), "원")
    winner = "대란" if b["per10"] < a["per10"] else "특란"
    print("이 예시 승자:", winner)

    whole = calc("teuk", 30, 7800, exclude_shell=False)
    assert approx(whole["total"], 30 * 64, 0.01)
    assert approx(whole["per10"], 7800 / (30 * 64) * 10, 0.1)
    print("특란 10g당 (껍질 포함):", round(whole["per10"], 1), "원")
    print("OK")


if __name__ == "__main__":
    main()
