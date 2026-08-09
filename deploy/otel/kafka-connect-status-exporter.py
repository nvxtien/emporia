#!/usr/bin/env python3
"""Polls the order-outbox Debezium connector's REST status and exposes it as
a Prometheus gauge. Kafka Connect has no built-in Prometheus endpoint (JMX
only), and a JMX-to-Prometheus bridge needs a bundled javaagent jar this repo
doesn't otherwise carry - this is the minimal thing that closes the "nobody
finds out the connector task died" gap without that dependency.
"""
import http.server
import json
import os
import threading
import time
import urllib.error
import urllib.request

CONNECT_URL = os.environ.get("CONNECT_URL", "http://kafka-connect:8083")
CONNECTOR_NAME = os.environ.get("CONNECTOR_NAME", "order-outbox-connector")
POLL_INTERVAL_SECONDS = int(os.environ.get("POLL_INTERVAL_SECONDS", "10"))
LISTEN_PORT = int(os.environ.get("LISTEN_PORT", "9405"))

state_lock = threading.Lock()
state = {"connector_up": 0, "task_up": 0, "last_poll_ok": 0}


def poll_forever():
    status_url = f"{CONNECT_URL}/connectors/{CONNECTOR_NAME}/status"
    while True:
        try:
            with urllib.request.urlopen(status_url, timeout=5) as response:
                payload = json.load(response)
            connector_up = 1 if payload.get("connector", {}).get("state") == "RUNNING" else 0
            tasks = payload.get("tasks", [])
            task_up = 1 if tasks and all(t.get("state") == "RUNNING" for t in tasks) else 0
            with state_lock:
                state["connector_up"] = connector_up
                state["task_up"] = task_up
                state["last_poll_ok"] = 1
        except (urllib.error.URLError, TimeoutError, ValueError):
            # Connect worker itself unreachable, or the connector isn't
            # registered yet - report down rather than crash the exporter.
            with state_lock:
                state["connector_up"] = 0
                state["task_up"] = 0
                state["last_poll_ok"] = 0
        time.sleep(POLL_INTERVAL_SECONDS)


class MetricsHandler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != "/metrics":
            self.send_response(404)
            self.end_headers()
            return
        with state_lock:
            snapshot = dict(state)
        body = (
            "# HELP emporia_outbox_connector_up Whether the order-outbox Debezium connector reports state RUNNING\n"
            "# TYPE emporia_outbox_connector_up gauge\n"
            f"emporia_outbox_connector_up {snapshot['connector_up']}\n"
            "# HELP emporia_outbox_connector_task_up Whether every order-outbox connector task reports state RUNNING\n"
            "# TYPE emporia_outbox_connector_task_up gauge\n"
            f"emporia_outbox_connector_task_up {snapshot['task_up']}\n"
            "# HELP emporia_outbox_connector_scrape_ok Whether the last poll of the Connect REST API succeeded\n"
            "# TYPE emporia_outbox_connector_scrape_ok gauge\n"
            f"emporia_outbox_connector_scrape_ok {snapshot['last_poll_ok']}\n"
        ).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; version=0.0.4")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass  # Prometheus scrapes every 10s; the container's own log is noise, not signal.


if __name__ == "__main__":
    threading.Thread(target=poll_forever, daemon=True).start()
    http.server.HTTPServer(("0.0.0.0", LISTEN_PORT), MetricsHandler).serve_forever()
