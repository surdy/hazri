#!/usr/bin/env python3
"""espresense-sim entry point. See README.md; run `./sim.py --help`."""

from __future__ import annotations

import sys

from espresense_sim.cli import main

if __name__ == "__main__":
    sys.exit(main())
