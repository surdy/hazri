"""Port of ESPresense's RSSI smoothing.

Source: lib/filtering/AdaptivePercentileRSSI.cpp at tag v4.0.6
https://github.com/ESPresense/ESPresense/blob/v4.0.6/lib/filtering/AdaptivePercentileRSSI.cpp

The firmware keeps a time-windowed ring buffer of rx-adjusted RSSI samples and
reports ``getMedianIQR()``: the mean of the samples that survive a Tukey fence
(k = 1.5) built from the quartiles, falling back to the median if the fence
clips everything. ``getRSSIVariance()`` and ``getDistanceVariance()`` are plain
population variances over the same window.

Not ported: the dynamic ring-buffer resizing (``adjustBufferSize``). It only
bounds memory on the ESP32 and at realistic advertisement rates the buffer
never truncates the 15 s window, so it cannot change the output here.
"""

from __future__ import annotations

import math
from collections import deque
from dataclasses import dataclass, field

DEFAULT_TIME_WINDOW_MS = 15_000


def _percentile(sorted_values: list[float], p: float) -> float:
    """Linear interpolation between ranks, as the firmware's `interp` lambda does."""
    n = len(sorted_values)
    if n == 0:
        return 0.0
    pos = p * (n - 1)
    lo = int(pos)
    frac = pos - lo
    if lo + 1 < n:
        return sorted_values[lo] * (1 - frac) + sorted_values[lo + 1] * frac
    return sorted_values[lo]


@dataclass
class AdaptivePercentileRSSI:
    time_window_ms: int = DEFAULT_TIME_WINDOW_MS
    _readings: deque[tuple[float, float]] = field(
        default_factory=deque, repr=False
    )  # (rssi, timestamp_ms)

    def add_measurement(self, rssi: float, now_ms: float) -> None:
        self._readings.append((rssi, now_ms))
        self._expire(now_ms)

    def _expire(self, now_ms: float) -> None:
        while self._readings and now_ms - self._readings[0][1] > self.time_window_ms:
            self._readings.popleft()

    @property
    def count(self) -> int:
        return len(self._readings)

    def values(self) -> list[float]:
        return [r for r, _ in self._readings]

    def median_iqr(self, k: float = 1.5) -> float:
        """Tukey-fenced mean of the window; the value published as ``rssi``."""
        vals = sorted(self.values())
        if not vals:
            return 0.0
        q1 = _percentile(vals, 0.25)
        med = _percentile(vals, 0.50)
        q3 = _percentile(vals, 0.75)
        iqr = q3 - q1
        lower, upper = q1 - k * iqr, q3 + k * iqr
        survivors = [v for v in vals if lower <= v <= upper]
        return sum(survivors) / len(survivors) if survivors else med

    def rssi_variance(self) -> float:
        vals = self.values()
        if len(vals) < 2:
            return 0.0
        mean = sum(vals) / len(vals)
        var = sum(v * v for v in vals) / len(vals) - mean * mean
        return max(0.0, var)

    def distance_variance(self, ref_rssi: float, path_loss_exponent: float) -> float:
        vals = self.values()
        if len(vals) < 2:
            return 0.0
        dists = [
            math.pow(10.0, (ref_rssi - v) / (10.0 * path_loss_exponent)) for v in vals
        ]
        mean = sum(dists) / len(dists)
        var = sum(d * d for d in dists) / len(dists) - mean * mean
        return max(0.0, var)
