#!/usr/bin/env python3
"""Generates deploy/otel/dashboards/latency-percentiles.json.

Written as a generator rather than hand-authored JSON because the dashboard has
20+ panels that must apply the same rules identically: percentiles from
_bucket via histogram_quantile (never _active_, never _sum/_count), volume and
outcome beside every latency panel, exemplars on latency panels, and bounded
labels only. Doing that by hand invites one panel quietly breaking a rule.
"""
import json

PROM = {"type": "prometheus", "uid": "prometheus"}
SEL = 'deployment=~"$deployment", service=~"$service"'


def sel(extra=""):
    """Builds a label selector including the dashboard variables.

    Exists because composing these inline in f-strings put the extra matcher
    outside the braces, producing `{a="b"}, outcome="x"}` — which Prometheus
    rejects. Every query is validated against the live API before commit.
    """
    return "{" + SEL + (", " + extra if extra else "") + "}"


FAILING = sel('outcome=~"error|timeout"')
NOT_SUCCESS = sel('outcome=~"error|timeout|rejected"')
SUCCEEDED = sel('outcome="success"')

panels = []
_id = [0]


def nid():
    _id[0] += 1
    return _id[0]


def row(title, y):
    panels.append({
        "type": "row", "title": title, "collapsed": False,
        "gridPos": {"h": 1, "w": 24, "x": 0, "y": y}, "panels": [],
    })


def target(expr, legend, exemplar=False, refid="A", instant=False):
    t = {"refId": refid, "expr": expr, "legendFormat": legend,
         "datasource": PROM, "exemplar": exemplar}
    if instant:
        t["instant"] = True
    return t


def pct_targets(metric, by, legend_prefix):
    """p50/p95/p99 from histogram buckets. Exemplars on, so a spike links to a trace."""
    out = []
    for refid, q, label in (("A", "0.5", "p50"), ("B", "0.95", "p95"), ("C", "0.99", "p99")):
        grouping = ", ".join(["le"] + by)
        expr = (f'histogram_quantile({q}, sum by ({grouping}) '
                f'(rate({metric}_seconds_bucket{sel()}[$__rate_interval])))')
        out.append(target(expr, f"{label} {legend_prefix}".strip(), exemplar=True, refid=refid))
    return out


def timeseries(title, y, x, w, targets, unit="s", desc="", h=8, thresholds=None, softmin=0):
    p = {
        "id": nid(), "type": "timeseries", "title": title, "datasource": PROM,
        "gridPos": {"h": h, "w": w, "x": x, "y": y},
        "targets": targets,
        "fieldConfig": {"defaults": {
            "unit": unit, "decimals": None,
            "custom": {"drawStyle": "line", "lineWidth": 2, "fillOpacity": 8,
                       "showPoints": "never", "axisSoftMin": softmin},
            "thresholds": {"mode": "absolute",
                           "steps": thresholds or [{"color": "green", "value": None}]},
        }, "overrides": []},
        "options": {
            "legend": {"displayMode": "table", "placement": "bottom",
                       "calcs": ["lastNotNull", "max"]},
            "tooltip": {"mode": "multi", "sort": "desc"},
        },
    }
    if desc:
        p["description"] = desc
    return p


def stat(title, y, x, w, expr, unit, steps, desc="", h=5, mappings=None):
    p = {
        "id": nid(), "type": "stat", "title": title, "datasource": PROM,
        "gridPos": {"h": h, "w": w, "x": x, "y": y},
        "targets": [target(expr, "", refid="A", instant=True)],
        "options": {"reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
                    "colorMode": "background", "graphMode": "area", "textMode": "auto"},
        "fieldConfig": {"defaults": {
            "unit": unit,
            "thresholds": {"mode": "absolute", "steps": steps},
        }, "overrides": []},
    }
    if mappings:
        p["fieldConfig"]["defaults"]["mappings"] = mappings
    if desc:
        p["description"] = desc
    return p


def rate_targets(metric, by, legend):
    grouping = ", ".join(by)
    return [target(f'sum by ({grouping}) (rate({metric}_seconds_count{sel()}[$__rate_interval]))',
                   legend, refid="A")]


# ---------------------------------------------------------------- overview
row("System overview", 0)
panels.append(stat(
    "Order submit p99", 1, 0, 6,
    f'histogram_quantile(0.99, sum by (le) (rate(emporia_order_submit_seconds_bucket{sel()}[$__rate_interval])))',
    "s", [{"color": "green", "value": None}, {"color": "yellow", "value": 0.1}, {"color": "red", "value": 1}],
    desc="Thresholds from the Phase 1_2 baseline: at or below the ~40 orders/sec knee p99 stayed under ~100ms. "
         "Laptop-derived context, not a production SLO."))
panels.append(stat(
    "Order submit rate", 1, 6, 6,
    f'sum(rate(emporia_order_submit_seconds_count{sel()}[$__rate_interval]))',
    "reqps", [{"color": "blue", "value": None}],
    desc="Always read the percentile panels next to this. A p99 computed from a handful of samples is noise."))
panels.append(stat(
    "Submit error + timeout ratio", 1, 12, 6,
    # `or vector(0)` on the numerator so a healthy system reads 0%, not "No data".
    # Without it the whole expression returns empty when no error series exists,
    # which is indistinguishable from a broken query at a glance.
    ('(sum(rate(emporia_order_submit_seconds_count' + FAILING + '[$__rate_interval])) or vector(0)) '
     '/ clamp_min(sum(rate(emporia_order_submit_seconds_count' + sel() + '[$__rate_interval])), 0.001)'),
    "percentunit", [{"color": "green", "value": None}, {"color": "yellow", "value": 0.01}, {"color": "red", "value": 0.05}],
    desc="A falling p99 during failures can mean the path stopped doing work rather than got faster. Read them together."))
panels.append(stat(
    "Kafka lag (stable groups)", 1, 18, 6,
    'sum(clamp_min(kafka_consumergroup_lag{consumergroup=~"order-data-service-v1|emporia-execution-service-v1|order-management-executions-v1"}, 0))',
    "short", [{"color": "green", "value": None}, {"color": "yellow", "value": 50}, {"color": "red", "value": 500}],
    desc="Broker-side exporter lag, clamped: the exporter reports -1 for partitions with no committed offset. "
         "Full detail on the Kafka Consumer Lag dashboard."))

panels.append(timeseries(
    "Order submit latency", 6, 0, 12,
    pct_targets("emporia_order_submit", ["service"], ""),
    desc="Click an exemplar dot to open the matching trace in Tempo."))
panels.append(timeseries(
    "Order submit rate by outcome", 6, 12, 12,
    rate_targets("emporia_order_submit", ["outcome"], "{{outcome}}"), unit="reqps",
    desc="Outcome vocabulary is open-ended: strategy paths also emit 'waiting', portfolio emits 'duplicate'. "
         "Do not assume only success/rejected/timeout/error."))

# ------------------------------------------------------------ order command
row("Order command path", 14)
panels.append(timeseries(
    "Submit latency by operation and destination", 15, 0, 12,
    pct_targets("emporia_order_submit", ["operation", "destination"], "{{operation}}/{{destination}}"),
    desc="Phase 1_2 traced ~1.2s of a 1.24s submit to waiting on the Kafka request/reply round trip, "
         "not to downstream processing."))
panels.append(timeseries(
    "Risk check latency by service", 15, 12, 12,
    pct_targets("emporia_risk_check", ["service", "decision"], "{{service}} {{decision}}"),
    desc="Grouped by service deliberately: emporia.risk.check is emitted by BOTH order-command-service "
         "(reason=permission) and order-management-service (reason=quantity|symbol). Without the service "
         "label two unrelated checks average into one line."))
panels.append(timeseries(
    "Submit rate by operation, destination, outcome", 23, 0, 12,
    rate_targets("emporia_order_submit", ["operation", "destination", "outcome"],
                 "{{operation}}/{{destination}} {{outcome}}"), unit="reqps"))
panels.append(timeseries(
    "Submit failures by outcome and error", 23, 12, 12,
    [target('sum by (outcome, error) (rate(emporia_order_submit_seconds_count' + NOT_SUCCESS + '[$__rate_interval]))',
            "{{outcome}} / {{error}}")], unit="reqps",
    desc="The error label (exception class name) is used here and only here. It is deliberately kept out of "
         "the percentile panels, where it would multiply series without adding meaning."))

# ---------------------------------------------------------------------- OMS
row("OMS command handling", 31)
panels.append(timeseries(
    "Command handle latency by type", 32, 0, 12,
    pct_targets("emporia_oms_command_handle", ["command_type"], "{{command_type}}")))
panels.append(timeseries(
    "Command handle rate by outcome", 32, 12, 12,
    rate_targets("emporia_oms_command_handle", ["command_type", "outcome"],
                 "{{command_type}} {{outcome}}"), unit="reqps",
    desc="outcome=duplicate means the idempotency cache answered, which is the retry path working as intended."))
panels.append(timeseries(
    "JDBC query latency by service", 40, 0, 12,
    pct_targets("jdbc_query", ["service"], "{{service}}"),
    desc="jdbc_query_seconds_bucket, not jdbc_query_active_seconds_bucket. The _active_ family is a long-task "
         "timer measuring work still in flight, and produces plausible-looking but wrong percentiles."))
panels.append(timeseries(
    "Kafka listener processing latency by consumer group", 40, 12, 12,
    pct_targets("spring_kafka_listener", ["service", "messaging_kafka_consumer_group"],
                "{{service}} {{messaging_kafka_consumer_group}}"),
    desc="spring_kafka_* metrics carry messaging_kafka_consumer_group and report each topic once. The dotted/"
         "underscored duplication and the topic=~\"emporia\\\\..+\" filter apply to kafka_consumer_* client "
         "metrics only; the label here is messaging_source_name, so that filter would match nothing."))

# ---------------------------------------------------------------- execution
row("Execution path", 48)
panels.append(timeseries(
    "Strategy decision latency", 49, 0, 8,
    pct_targets("emporia_strategy_decision", ["strategy"], "{{strategy}}"),
    desc="Absent until a SMART or VWAP order runs: Micrometer creates a meter on first observation, so an "
         "unexercised path shows No data rather than zero."))
panels.append(timeseries(
    "Venue operation latency", 49, 8, 8,
    pct_targets("emporia_execution_venue_operation", ["venue_mode", "operation"],
                "{{venue_mode}}/{{operation}}"),
    desc="exchange-core checkpoints synchronously to disk on every submit/replace/cancel, so this includes "
         "that durability cost."))
panels.append(timeseries(
    "Execution publish latency", 49, 16, 8,
    pct_targets("emporia_execution_publish", ["command_type"], "{{command_type}}")))
panels.append(timeseries(
    "Strategy decision rate by outcome", 57, 0, 12,
    rate_targets("emporia_strategy_decision", ["strategy", "outcome"], "{{strategy}} {{outcome}}"),
    unit="reqps",
    desc="outcome=waiting dominates in normal operation (a tick that decided to do nothing yet). A success "
         "rate computed against the four declared outcomes would read wrong."))
panels.append(timeseries(
    "Execution publish rate by outcome", 57, 12, 12,
    rate_targets("emporia_execution_publish", ["command_type", "outcome"],
                 "{{command_type}} {{outcome}}"), unit="reqps"))

# ---------------------------------------------------------------- portfolio
row("Portfolio path", 65)
panels.append(timeseries(
    "Snapshot apply latency", 66, 0, 12,
    pct_targets("emporia_portfolio_snapshot_apply", [], ""),
    desc="Requires an actual fill to appear. Every order in the local load scripts is a BUY, so with no "
         "resting SELL liquidity nothing fills and this panel stays empty. No data here does not imply a "
         "broken query."))
panels.append(timeseries(
    "Snapshot apply rate by outcome", 66, 12, 12,
    rate_targets("emporia_portfolio_snapshot_apply", ["outcome"], "{{outcome}}"), unit="reqps",
    desc="outcome=duplicate is a documented extension: a snapshot already applied."))

# ----------------------------------------------------------- infrastructure
row("Infrastructure latency", 74)
panels.append(timeseries(
    "HTTP server latency by route", 75, 0, 12,
    pct_targets("http_server_requests", ["service", "method", "uri"],
                "{{service}} {{method}} {{uri}}"),
    desc="uri is the templated route (/instruments/{id}), never a raw URL, so cardinality stays bounded."))
panels.append(timeseries(
    "HTTP client latency by target", 75, 12, 12,
    pct_targets("http_client_requests", ["service", "client_name", "uri"],
                "{{service}} -> {{client_name}} {{uri}}"),
    desc="Requires spring-boot-starter-restclient; without it there is no auto-configured RestClient.Builder, "
         "no observation, and this panel is silently empty. The uri tag comes from the URI *template*, so a "
         "pre-built URL string would unbound cardinality here."))
panels.append(timeseries(
    "JDBC connection and result-set latency", 83, 0, 12,
    pct_targets("jdbc_connection", ["service"], "conn {{service}}")
    + [target('histogram_quantile(0.95, sum by (le, service) (rate(jdbc_result_set_seconds_bucket' + sel() + '[$__rate_interval])))',
              "p95 result-set {{service}}", exemplar=True, refid="D")]))
panels.append(timeseries(
    "Kafka producer send latency by topic", 83, 12, 12,
    pct_targets("spring_kafka_template", ["service", "messaging_destination_name"],
                "{{service}} {{messaging_destination_name}}")))

# -------------------------------------------------------------- baseline
row("Baseline comparison (Phase 1_2, laptop-derived)", 91)
panels.append(timeseries(
    "Order submit p99 against the measured knee", 92, 0, 12,
    [target(f'histogram_quantile(0.99, sum by (le) (rate(emporia_order_submit_seconds_bucket{sel()}[$__rate_interval])))',
            "p99", exemplar=True)],
    thresholds=[{"color": "green", "value": None}, {"color": "yellow", "value": 0.1}, {"color": "red", "value": 1}],
    desc="Phase 1_2 measured: <=40 orders/sec p99 under ~100ms with lag ~0; 48/sec p50 3137ms with sustained "
         "lag 123-199; 80/sec 7.7% infra failures. These are one laptop's numbers and warning-only context."))
panels.append(timeseries(
    "Stable-group lag beside submit p99", 92, 12, 12,
    [target('sum by (consumergroup) (clamp_min(kafka_consumergroup_lag{consumergroup=~"order-data-service-v1|emporia-execution-service-v1|order-management-executions-v1"}, 0))',
            "{{consumergroup}}")], unit="short",
    desc="Lag climbing while p99 climbs indicates consumers falling behind rather than a slow single request."))
panels.append(timeseries(
    "Accepted rate vs failure rate", 100, 0, 24,
    [target('sum(rate(emporia_order_submit_seconds_count' + SUCCEEDED + '[$__rate_interval]))', "accepted/s"),
     target('sum(rate(emporia_order_submit_seconds_count' + FAILING + '[$__rate_interval]))',
            "failed/s", refid="B")], unit="reqps",
    desc="Throughput collapsing while latency looks fine is the signature of the system failing closed."))

dashboard = {
    "uid": "emporia-latency-percentiles",
    "title": "Emporia Latency Percentiles",
    "tags": ["emporia", "latency", "phase-1-3"],
    "timezone": "browser",
    "schemaVersion": 39,
    "version": 1,
    "refresh": "10s",
    "editable": True,
    "time": {"from": "now-1h", "to": "now"},
    "description": (
        "REWORK_NOTE Phase 1_3. All percentiles come from histogram buckets via histogram_quantile, never "
        "from averages or _sum/_count, and never from the _active_ long-task-timer families. Every latency "
        "panel has traffic volume and an outcome breakdown nearby, because a p99 over few samples is noise "
        "and a falling p99 during failures usually means the path stopped doing work. Metric names and "
        "labels were read from the running stack; see rework/PHASE_1_3_DISCOVERY.md."
    ),
    "templating": {"list": [
        {"name": "deployment", "label": "Deployment", "type": "query", "datasource": PROM,
         "query": {"query": "label_values(up{job=~\"emporia.*\"}, deployment)", "refId": "A"},
         "multi": True, "includeAll": True, "allValue": ".*", "refresh": 2, "sort": 1,
         "current": {"selected": True, "text": ["All"], "value": ["$__all"]},
         "description": "docker for the full-stack compose run, host for run-local.sh / run-infra-docker.sh."},
        {"name": "service", "label": "Service", "type": "query", "datasource": PROM,
         "query": {"query": "label_values(up{job=~\"emporia.*\"}, service)", "refId": "A"},
         "multi": True, "includeAll": True, "allValue": ".*", "refresh": 2, "sort": 1,
         "current": {"selected": True, "text": ["All"], "value": ["$__all"]}},
    ]},
    "panels": panels,
}

out = "deploy/otel/dashboards/latency-percentiles.json"
with open(out, "w") as fh:
    json.dump(dashboard, fh, indent=2)
    fh.write("\n")
print(f"wrote {out}: {len([p for p in panels if p['type'] != 'row'])} panels, "
      f"{len([p for p in panels if p['type'] == 'row'])} rows")
