#!/usr/bin/env python3
"""
Train adaptive SMC factor weights from accumulated backtest CSV files.

The trainer is dependency-free on purpose: it can run inside the embedded
runtime before sklearn/xgboost are installed. It learns stable factor weights
from realised R outcomes and writes a shadow candidate model. Java promotes the
candidate only after validating the file and preserving the live model contract.
"""

import argparse
import csv
import json
import math
import os
import time
from pathlib import Path


FACTORS = ("displacement", "volume", "liquidity", "orderFlow", "regime")
MIN_SAMPLES = 50
VALIDATION_TOP_FRACTION = 0.40
MIN_EXPECTANCY_IMPROVEMENT = 0.01

# Walk-forward (rolling-origin) validation instead of one fixed 70/30 chronological split.
# A single split answers "did this one held-out slice happen to like the new weights" -- with
# financial R-multiples that's one noisy coin flip standing in for "does this generalize."
# Rolling-origin folds ask the question repeatedly across different stretches of history, each
# time training only on what came strictly before that fold's validation slice (never after --
# same walk-forward discipline the Java Backtester already uses for signal replay), and requires
# a MAJORITY of folds to agree before promoting, not just a favorable average.
WALK_FORWARD_FOLDS = 4
MIN_FOLD_TRAIN_SAMPLES = 20
MIN_FOLD_VALIDATION_SAMPLES = 8
MIN_USABLE_FOLDS = 3
REQUIRED_FOLD_WIN_FRACTION = 0.60


def clamp(value, lo=0.2, hi=2.2):
    return max(lo, min(hi, float(value)))


def safe_symbol(value):
    text = (value or "UNKNOWN").upper()
    return "".join(ch for ch in text if ch.isalnum() or ch in "_-") or "UNKNOWN"


def to_float(row, key, default=0.0):
    try:
        value = row.get(key, "")
        return default if value == "" else float(value)
    except (TypeError, ValueError):
        return default


def to_bool(row, key):
    return str(row.get(key, "")).strip().lower() == "true"


def feature_vector(row):
    regime_text = str(row.get("market_regime", "")).lower()
    zone_quality = str(row.get("zone_quality", "")).lower()
    session = str(row.get("session", "")).lower()

    displacement = (
        to_float(row, "displacement_score") / 100.0 * 0.62
        + (1.0 if to_bool(row, "htf_displacement_aligned") else 0.0) * 0.28
        + to_float(row, "htf_strength") / 100.0 * 0.10
    )
    volume = (
        to_float(row, "volume_score") / 100.0 * 0.70
        + to_float(row, "smc_score") / 100.0 * 0.15
        + (1.0 if "kill" in session else 0.0) * 0.15
    )
    liquidity = (
        to_float(row, "liquidity_score") / 100.0 * 0.62
        + (1.0 if to_bool(row, "sweep_confirmed") else 0.0) * 0.28
        + (1.0 if "strong" in zone_quality else 0.0) * 0.10
    )
    order_flow = (
        to_float(row, "order_flow_score") / 100.0 * 0.64
        + (1.0 if to_bool(row, "entry_confirmed") else 0.0) * 0.24
        + max(0.0, 1.0 - abs(to_float(row, "entry_impact_bps")) / 18.0) * 0.12
    )
    regime = (
        to_float(row, "ltf_strength") / 100.0 * 0.34
        + to_float(row, "htf_strength") / 100.0 * 0.26
        + (0.0 if "chop" in regime_text else 0.68 if "trend" in regime_text else 0.50) * 0.40
    )

    return {
        "displacement": clamp01(displacement),
        "volume": clamp01(volume),
        "liquidity": clamp01(liquidity),
        "orderFlow": clamp01(order_flow),
        "regime": clamp01(regime),
    }


def clamp01(value):
    return max(0.0, min(1.0, float(value)))


def iter_rows(backtests_dir, latest_csv=None):
    paths = []
    root = Path(backtests_dir)
    if root.exists():
        paths.extend(sorted(root.glob("*.csv")))
    if latest_csv:
        latest = Path(latest_csv)
        if latest.exists() and latest not in paths:
            paths.append(latest)

    for path in paths:
        try:
            with path.open("r", encoding="utf-8", newline="") as handle:
                for row in csv.DictReader(handle):
                    row["_source_csv"] = str(path)
                    yield row
        except Exception:
            continue


def prepare_samples(rows, symbol):
    wanted = safe_symbol(symbol)
    samples = []
    for row in rows:
        if safe_symbol(row.get("symbol")) != wanted:
            continue
        outcome = str(row.get("outcome", "")).upper()
        if outcome in {"MISSED", "OPEN_EOD"}:
            continue
        target = max(-1.5, min(3.0, to_float(row, "result_r")))
        features = feature_vector(row)
        samples.append((features, target, row))
    return samples


def sample_time(sample):
    _, _, row = sample
    return to_float(row, "signal_time", 0.0)


def walk_forward_folds(samples, folds=WALK_FORWARD_FOLDS):
    """
    Expanding-window walk-forward split. Samples are sorted chronologically and cut into
    `folds + 1` contiguous blocks; fold k trains on every block strictly before block k and
    validates on block k itself, so later folds have more training history (as a real deployment
    would) and no fold ever validates against data its training window could have seen.

    Returns a list of (train_samples, validation_samples) tuples, skipping any fold whose train
    or validation slice is too thin to say anything trustworthy about.
    """
    ordered = sorted(samples, key=sample_time)
    n = len(ordered)
    if n < MIN_SAMPLES:
        return []
    block = max(1, n // (folds + 1))
    result = []
    for k in range(1, folds + 1):
        split = min(n - 1, block * k)
        train = ordered[:split]
        validation = ordered[split:split + block] if k < folds else ordered[split:]
        if len(train) >= MIN_FOLD_TRAIN_SAMPLES and len(validation) >= MIN_FOLD_VALIDATION_SAMPLES:
            result.append((train, validation))
    return result


def pearson(xs, ys):
    n = len(xs)
    if n < 2:
        return 0.0
    mx = sum(xs) / n
    my = sum(ys) / n
    vx = sum((x - mx) ** 2 for x in xs)
    vy = sum((y - my) ** 2 for y in ys)
    if vx <= 1e-12 or vy <= 1e-12:
        return 0.0
    return sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / math.sqrt(vx * vy)


def learn_weights(samples):
    if not samples:
        return {name: 1.0 for name in FACTORS}, {"sampleCount": 0, "expectancy": 0.0}

    targets = [target for _, target, _ in samples]
    expectancy = sum(targets) / len(targets)
    weights = {}
    diagnostics = {
        "sampleCount": len(samples),
        "expectancy": expectancy,
        "factorCorrelation": {},
        "factorHighEdge": {},
        "factorLowEdge": {},
    }

    for factor in FACTORS:
        values = [features[factor] for features, _, _ in samples]
        corr = pearson(values, targets)
        ordered = sorted(zip(values, targets), key=lambda pair: pair[0])
        cut = max(2, min(len(ordered) // 3, len(ordered) - 1))
        low = ordered[:cut]
        high = ordered[-cut:]
        low_edge = sum(y for _, y in low) / len(low)
        high_edge = sum(y for _, y in high) / len(high)
        spread = high_edge - low_edge

        raw = 1.0 + corr * 0.42 + spread * 0.18 + expectancy * 0.08
        if len(samples) < MIN_SAMPLES:
            raw = 1.0 + (raw - 1.0) * (len(samples) / MIN_SAMPLES)
        weights[factor] = clamp(raw)
        diagnostics["factorCorrelation"][factor] = corr
        diagnostics["factorHighEdge"][factor] = high_edge
        diagnostics["factorLowEdge"][factor] = low_edge

    return weights, diagnostics


def weighted_score(features, weights):
    total = 0.0
    normalizer = 0.0
    for factor in FACTORS:
        weight = clamp(weights.get(factor, 1.0))
        total += features[factor] * weight
        normalizer += weight
    return total / max(normalizer, 1e-9)


def selected_expectancy(samples, weights):
    if not samples:
        return 0.0, 0
    ranked = sorted(samples, key=lambda sample: weighted_score(sample[0], weights), reverse=True)
    take = max(5, int(round(len(ranked) * VALIDATION_TOP_FRACTION)))
    selected = ranked[:min(len(ranked), take)]
    return sum(target for _, target, _ in selected) / len(selected), len(selected)


def load_model(weights_dir):
    model_path = Path(weights_dir) / "ml_model.json"
    model = {"version": 1, "symbols": {}, "updatedAt": 0}
    if model_path.exists():
        try:
            loaded = json.loads(model_path.read_text(encoding="utf-8"))
            if isinstance(loaded, dict):
                loaded.setdefault("symbols", {})
                return loaded
        except Exception:
            pass
    return model


def existing_weights(model, symbol):
    entry = model.get("symbols", {}).get(safe_symbol(symbol), {})
    weights = entry.get("weights", {}) if isinstance(entry, dict) else {}
    return {factor: clamp(weights.get(factor, 1.0)) for factor in FACTORS}


def write_candidate_model(weights_dir, symbol, weights, diagnostics):
    path = Path(weights_dir)
    path.mkdir(parents=True, exist_ok=True)
    model = load_model(weights_dir)
    model.setdefault("symbols", {})
    model["symbols"][safe_symbol(symbol)] = {
        "weights": weights,
        "diagnostics": diagnostics,
        "updatedAt": int(time.time() * 1000),
    }
    model["updatedAt"] = int(time.time() * 1000)
    candidate_path = path / "ml_model.candidate.json"
    candidate_path.write_text(json.dumps(model, ensure_ascii=True, indent=2), encoding="utf-8")
    return candidate_path


def remove_stale_candidate(weights_dir):
    try:
        (Path(weights_dir) / "ml_model.candidate.json").unlink()
    except FileNotFoundError:
        pass
    except Exception:
        pass


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--symbol", required=True)
    parser.add_argument("--backtests", required=True)
    parser.add_argument("--weights", required=True)
    parser.add_argument("--latest-csv", default="")
    args = parser.parse_args()

    samples = prepare_samples(iter_rows(args.backtests, args.latest_csv), args.symbol)
    if len(samples) < MIN_SAMPLES:
        remove_stale_candidate(args.weights)
        print(f"skipped: only {len(samples)} samples, need {MIN_SAMPLES}")
        print(json.dumps({
            "status": "skipped",
            "reason": "not_enough_samples",
            "symbol": safe_symbol(args.symbol),
            "samples": len(samples),
            "minSamples": MIN_SAMPLES,
        }))
        return

    live_model = load_model(args.weights)
    baseline_weights = existing_weights(live_model, args.symbol)

    folds = walk_forward_folds(samples)
    if len(folds) < MIN_USABLE_FOLDS:
        remove_stale_candidate(args.weights)
        print(f"skipped: only {len(folds)} usable walk-forward folds, need {MIN_USABLE_FOLDS}")
        print(json.dumps({
            "status": "skipped",
            "reason": "not_enough_folds",
            "symbol": safe_symbol(args.symbol),
            "samples": len(samples),
            "usableFolds": len(folds),
            "minUsableFolds": MIN_USABLE_FOLDS,
        }))
        return

    fold_reports = []
    for train_fold, validation_fold in folds:
        fold_weights, _ = learn_weights(train_fold)
        candidate_expectancy, candidate_selected = selected_expectancy(validation_fold, fold_weights)
        baseline_expectancy, baseline_selected = selected_expectancy(validation_fold, baseline_weights)
        fold_reports.append({
            "trainSamples": len(train_fold),
            "validationSamples": len(validation_fold),
            "candidateExpectancy": candidate_expectancy,
            "candidateSelectedCount": candidate_selected,
            "baselineExpectancy": baseline_expectancy,
            "baselineSelectedCount": baseline_selected,
            "improvement": candidate_expectancy - baseline_expectancy,
        })

    improvements = [f["improvement"] for f in fold_reports]
    mean_candidate_expectancy = sum(f["candidateExpectancy"] for f in fold_reports) / len(fold_reports)
    mean_baseline_expectancy = sum(f["baselineExpectancy"] for f in fold_reports) / len(fold_reports)
    mean_improvement = sum(improvements) / len(improvements)
    folds_improved = sum(1 for imp in improvements if imp > 0.0)
    win_fraction = folds_improved / len(fold_reports)

    # Final weights are refit on ALL available samples once walk-forward has established that
    # the *learning process* generalizes -- the folds above exist to validate the approach, not
    # to decide which slice of history the shipped weights get trained on. Using every sample
    # for the deployed model (rather than only the ~70% a single split would have used) is
    # standard practice once cross-validation has done its job.
    weights, diagnostics = learn_weights(samples)
    diagnostics.update({
        "sampleCount": len(samples),
        "walkForwardFolds": len(fold_reports),
        "foldReports": fold_reports,
        "foldsImproved": folds_improved,
        "foldWinFraction": win_fraction,
        "meanImprovement": mean_improvement,
        # Kept under their original names for the Java-side promotion gate (BacktestLearningTrainer
        # .promoteCandidate reads these two keys by name from diagnostics) -- now the walk-forward
        # MEAN across folds rather than one split's point estimate, which quietly strengthens that
        # existing gate without needing to touch the Java side at all.
        "validationCandidateExpectancy": mean_candidate_expectancy,
        "validationBaselineExpectancy": mean_baseline_expectancy,
        "minExpectancyImprovement": MIN_EXPECTANCY_IMPROVEMENT,
        "requiredFoldWinFraction": REQUIRED_FOLD_WIN_FRACTION,
    })

    passes = (
        win_fraction >= REQUIRED_FOLD_WIN_FRACTION
        and mean_improvement >= MIN_EXPECTANCY_IMPROVEMENT
    )
    if not passes:
        remove_stale_candidate(args.weights)
        print(
            "skipped: walk-forward candidate improved {won}/{total} folds (mean {mean:.3f}R), "
            "needed >= {frac:.0%} of folds and >= {bar:.3f}R mean improvement".format(
                won=folds_improved, total=len(fold_reports), mean=mean_improvement,
                frac=REQUIRED_FOLD_WIN_FRACTION, bar=MIN_EXPECTANCY_IMPROVEMENT,
            )
        )
        print(json.dumps({
            "status": "skipped",
            "reason": "walk_forward_not_better",
            "symbol": safe_symbol(args.symbol),
            "samples": len(samples),
            "foldsImproved": folds_improved,
            "walkForwardFolds": len(fold_reports),
            "meanImprovement": mean_improvement,
            "validationCandidateExpectancy": mean_candidate_expectancy,
            "validationBaselineExpectancy": mean_baseline_expectancy,
        }))
        return

    candidate_path = write_candidate_model(args.weights, args.symbol, weights, diagnostics)

    print(
        "candidate {samples} samples, {won}/{total} folds improved (mean {mean:.3f}R), "
        "validation {candidate:.3f}R vs baseline {baseline:.3f}R, "
        "weights D {d:.2f} / V {v:.2f} / L {l:.2f} / OF {of:.2f} / R {r:.2f}".format(
            samples=diagnostics["sampleCount"],
            won=folds_improved,
            total=len(fold_reports),
            mean=mean_improvement,
            candidate=mean_candidate_expectancy,
            baseline=mean_baseline_expectancy,
            d=weights["displacement"],
            v=weights["volume"],
            l=weights["liquidity"],
            of=weights["orderFlow"],
            r=weights["regime"],
        )
    )
    print(json.dumps({
        "status": "candidate",
        "symbol": safe_symbol(args.symbol),
        "samples": diagnostics["sampleCount"],
        "walkForwardFolds": len(fold_reports),
        "foldsImproved": folds_improved,
        "meanImprovement": mean_improvement,
        "validationCandidateExpectancy": mean_candidate_expectancy,
        "validationBaselineExpectancy": mean_baseline_expectancy,
        "weights": weights,
        "candidate": str(candidate_path),
    }))


if __name__ == "__main__":
    main()
