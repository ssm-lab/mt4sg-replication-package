#!/usr/bin/env python3
"""Replays the scene updates of a recorded run over ZeroMQ, 1 per period on average.

Each interval varies uniformly by up to 10 ms, so the scenes don't arrive in step
with the fixed period of the sampling profiler.

Typical usage example:

  python profile_replay.py a-10-3-incremental.jsonl.gz tcp://*:5556 0.1
"""

import ctypes
import gzip
import random
import sys
import time

import zmq

JITTER_S = 0.01


def main():
    # Python sleeps on the 15.6 ms Windows timer tick otherwise.
    ctypes.windll.winmm.timeBeginPeriod(1)
    path, endpoint, period = sys.argv[1], sys.argv[2], float(sys.argv[3])
    context = zmq.Context()
    socket = context.socket(zmq.PUB)
    socket.bind(endpoint)
    # PUB drops what it sends before the subscriber has connected.
    time.sleep(2.0)
    next_due = None
    with gzip.open(path, "rt", encoding="utf-8") as scenes:
        for line in scenes:
            if next_due is not None:
                time.sleep(max(0.0, next_due - time.perf_counter()))
            socket.send_string(line.rstrip("\n"))
            now = time.perf_counter()
            interval = period + random.uniform(-JITTER_S, JITTER_S)
            next_due = now + interval if next_due is None else max(next_due + interval, now)
    socket.close(linger=10000)
    context.term()


if __name__ == "__main__":
    main()
