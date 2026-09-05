"""The physical radio model: what a node's antenna actually sees.

This is the *truth* the simulator invents. The firmware model (see
``settings.py`` and ``node.py``) then tries to recover distance from it with
whatever calibration the node happens to be configured with, which is the
whole point of the exercise: the two disagree until you tune the node.
"""

from __future__ import annotations

import math
import random
from dataclasses import dataclass, field

# Log-distance path loss:  rssi = ref_rssi_1m - 10 * n * log10(d)
DEFAULT_TRUE_REF_RSSI = -59.0  # dBm the phone actually produces at 1 m
DEFAULT_TRUE_PATH_LOSS = 3.5  # exponent for a furnished house
DEFAULT_NOISE_SIGMA_DB = 3.0


@dataclass
class RadioModel:
    """Free-ish-space path loss, wall penalties, Gaussian noise, packet loss."""

    ref_rssi_1m: float = DEFAULT_TRUE_REF_RSSI
    path_loss_exponent: float = DEFAULT_TRUE_PATH_LOSS
    noise_sigma_db: float = DEFAULT_NOISE_SIGMA_DB
    # Packet loss: base + (d / loss_ref) ** loss_exp, clamped to loss_max.
    base_loss: float = 0.02
    loss_ref_m: float = 18.0
    loss_exponent: float = 2.0
    max_loss: float = 0.95
    rng: random.Random = field(default_factory=random.Random)

    def path_loss_db(self, distance_m: float) -> float:
        return 10.0 * self.path_loss_exponent * math.log10(max(distance_m, 0.01))

    def mean_rssi(
        self, distance_m: float, wall_db: float = 0.0, antenna_offset_db: float = 0.0
    ) -> float:
        """Noise-free RSSI a node would measure at this distance."""
        return (
            self.ref_rssi_1m
            - self.path_loss_db(distance_m)
            - wall_db
            + antenna_offset_db
        )

    def sample_rssi(
        self, distance_m: float, wall_db: float = 0.0, antenna_offset_db: float = 0.0
    ) -> float:
        """One measured advertisement: mean plus Gaussian shadowing noise."""
        mean = self.mean_rssi(distance_m, wall_db, antenna_offset_db)
        return mean + self.rng.gauss(0.0, self.noise_sigma_db)

    def loss_probability(self, distance_m: float) -> float:
        p = self.base_loss + (max(distance_m, 0.0) / self.loss_ref_m) ** self.loss_exponent
        return min(self.max_loss, max(0.0, p))

    def delivered(self, distance_m: float) -> bool:
        """True if this advertisement reaches the node at all."""
        return self.rng.random() >= self.loss_probability(distance_m)
