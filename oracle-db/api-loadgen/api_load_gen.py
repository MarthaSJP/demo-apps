#!/usr/bin/env python3
"""
ReliPeople API Load Generator

Generates direct backend API traffic without browser sessions. This keeps UI
journeys realistic while allowing database pressure to be tuned separately.
"""

from __future__ import annotations

import logging
import os
import random
import signal
import sys
import time
from dataclasses import dataclass
from threading import Event, Thread
from typing import List

import requests


logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
    handlers=[logging.StreamHandler(sys.stdout)],
)
logger = logging.getLogger("relipeople.api-loadgen")

BACKEND_URL = os.getenv("API_LOADGEN_BACKEND_URL", "http://backend:8080").rstrip("/")
WORKERS = int(os.getenv("API_LOADGEN_WORKERS", "4"))
INTERVAL = float(os.getenv("API_LOADGEN_INTERVAL", "2"))
TIMEOUT = float(os.getenv("API_LOADGEN_TIMEOUT", "120"))
NPLUSONE_LIMIT = int(os.getenv("API_LOADGEN_NPLUSONE_LIMIT", "200"))
PAYROLL_LIMIT = int(os.getenv("API_LOADGEN_PAYROLL_LIMIT", "300"))
JITTER = float(os.getenv("API_LOADGEN_JITTER", "0.75"))

NPLUSONE_WEIGHT = int(os.getenv("API_LOADGEN_NPLUSONE_WEIGHT", "50"))
PAYROLL_WEIGHT = int(os.getenv("API_LOADGEN_PAYROLL_WEIGHT", "15"))
LEAVE_BACKLOG_WEIGHT = int(os.getenv("API_LOADGEN_LEAVE_BACKLOG_WEIGHT", "15"))
SALARY_PROGRESSION_WEIGHT = int(os.getenv("API_LOADGEN_SALARY_PROGRESSION_WEIGHT", "10"))
DEPARTMENTS_WEIGHT = int(os.getenv("API_LOADGEN_DEPARTMENTS_WEIGHT", "5"))
PERFORMANCE_WEIGHT = int(os.getenv("API_LOADGEN_PERFORMANCE_WEIGHT", "5"))

stop_event = Event()


@dataclass(frozen=True)
class Endpoint:
    name: str
    path: str
    weight: int


def endpoints() -> List[Endpoint]:
    return [
        Endpoint(
            "employee_detail_audit_nplusone",
            f"/api/reports/employee-detail-audit?limit={NPLUSONE_LIMIT}",
            NPLUSONE_WEIGHT,
        ),
        Endpoint("payroll", f"/api/reports/payroll?limit={PAYROLL_LIMIT}", PAYROLL_WEIGHT),
        Endpoint("leave_backlog", "/api/reports/leave-backlog", LEAVE_BACKLOG_WEIGHT),
        Endpoint("salary_progression", "/api/reports/salary-progression", SALARY_PROGRESSION_WEIGHT),
        Endpoint("departments", "/api/reports/departments", DEPARTMENTS_WEIGHT),
        Endpoint("performance", "/api/reports/performance", PERFORMANCE_WEIGHT),
    ]


def pick_endpoint(weighted_endpoints: List[Endpoint]) -> Endpoint:
    total = sum(max(0, endpoint.weight) for endpoint in weighted_endpoints)
    if total <= 0:
        return weighted_endpoints[0]

    target = random.uniform(0, total)
    cumulative = 0
    for endpoint in weighted_endpoints:
        cumulative += max(0, endpoint.weight)
        if target <= cumulative:
            return endpoint
    return weighted_endpoints[0]


def wait_for_backend() -> bool:
    logger.info("Waiting for backend at %s ...", BACKEND_URL)
    for attempt in range(80):
        try:
            response = requests.get(f"{BACKEND_URL}/actuator/health", timeout=5)
            if response.status_code == 200:
                logger.info("Backend is ready (attempt %d)", attempt + 1)
                return True
        except requests.RequestException:
            pass
        time.sleep(5)
    logger.error("Backend did not become available after 80 attempts")
    return False


def run_worker(worker_id: int, weighted_endpoints: List[Endpoint]) -> None:
    session = requests.Session()
    logger.info("Worker %d starting", worker_id)

    while not stop_event.is_set():
        endpoint = pick_endpoint(weighted_endpoints)
        url = f"{BACKEND_URL}{endpoint.path}"
        start = time.time()

        try:
            response = session.get(url, timeout=TIMEOUT)
            elapsed = round(time.time() - start, 3)
            logger.info(
                "Worker %d: endpoint=%s status=%d elapsed_s=%s bytes=%d",
                worker_id,
                endpoint.name,
                response.status_code,
                elapsed,
                len(response.content),
            )
        except requests.RequestException as exc:
            elapsed = round(time.time() - start, 3)
            logger.warning(
                "Worker %d: endpoint=%s failed elapsed_s=%s error=%s",
                worker_id,
                endpoint.name,
                elapsed,
                exc,
            )

        sleep_s = INTERVAL + random.uniform(-JITTER, JITTER)
        stop_event.wait(max(0.1, sleep_s))


def handle_shutdown(signum: int, _frame) -> None:
    logger.info("Received signal %d, shutting down", signum)
    stop_event.set()


def main() -> None:
    weighted_endpoints = endpoints()
    signal.signal(signal.SIGTERM, handle_shutdown)
    signal.signal(signal.SIGINT, handle_shutdown)

    logger.info("=" * 60)
    logger.info("ReliPeople API Load Generator")
    logger.info("Backend  : %s", BACKEND_URL)
    logger.info("Workers  : %d", WORKERS)
    logger.info("Interval : %.1fs (+/- %.1fs jitter)", INTERVAL, JITTER)
    logger.info("Timeout  : %.1fs", TIMEOUT)
    logger.info("Endpoints: %s", [(endpoint.name, endpoint.weight) for endpoint in weighted_endpoints])
    logger.info("=" * 60)

    if not wait_for_backend():
        sys.exit(1)

    threads = []
    for worker_id in range(1, WORKERS + 1):
        thread = Thread(target=run_worker, args=(worker_id, weighted_endpoints), daemon=True)
        thread.start()
        threads.append(thread)
        time.sleep(0.5)

    logger.info("All %d API workers active", WORKERS)
    while not stop_event.wait(60):
        logger.info("API load generator heartbeat: %d workers active", WORKERS)


if __name__ == "__main__":
    main()
