# HFC2 – Circuit Manager: Retry, Dead Letter & Operator Notification Design

**Sprint:** HFC2-Cycle2-Sprint 9 · **PI:** PI9
**Component:** Apache NiFi – BLACK · Circuit Manager
**Reviewers:** Jakob, Asif

---

## 1. Goal

Automatically retry transient TCP and downstream errors on the Fragment Manager (FM) calls, with bounded back-off, and route permanent failures to a dead-letter queue with operator notification – so success rates improve without manual intervention.

---

## 2. Design Overview

```
                 ┌──────────── success ───────────► Continue (response to Red)
                 │
Fragment Manager │──── transient error ──► Retry with back-off (1s → 2s → 4s)
   call (PUT/    │                              │
   POST)         │                              ├─ succeeds ──► Continue
                 │                              └─ retries exhausted ─┐
                 │                                                    ▼
                 └──── permanent error ─────────────────────► Dead Letter Queue
                                                                      │
                                                     ┌────────────────┴───────────────┐
                                                     ▼                                ▼
                                              NiFi error log                 OTel Collector
                                                                           (ERROR log record)
```

---

## 3. Error Classification

| Outcome | Example | Type | Action |
|---|---|---|---|
| Success | HTTP 2xx | – | Continue |
| Connection failure | FM down, connection refused, timeout | Transient (TCP) | Retry → DLQ |
| Server error | HTTP 5xx | Transient (downstream) | Retry → DLQ |
| Client error | HTTP 4xx | Permanent | DLQ immediately |

---

## 4. Retry Policy

| Setting | Value |
|---|---|
| Retry attempts | 3 (4 calls in total) |
| Back-off | Exponential: 1s → 2s → 4s |
| Maximum back-off | 10s |
| Back-off scope | Per message (other messages keep flowing) |

When FM is down, retries complete in ~7 seconds before the message is dead-lettered.

---

## 5. Dead Letter Queue

- Failed messages are held in a DLQ, keeping the original request for inspection and manual replay.
- Each message is tagged with: failure reason, error message, and timestamp.

---

## 6. Operator Notification

| Channel | Purpose |
|---|---|
| NiFi error log | Immediate visibility in NiFi logs |
| OpenTelemetry Collector | ERROR log record (reason, status code, source) via the existing OTel logging path – searchable and alertable |

Safeguards:
- Failures when sending to OTel are not fed back into the error path (prevents a logging loop if the collector is down).
- Sensitive headers are excluded and log bodies are size-limited.

---

## 7. Finding: Caller Timeout vs NiFi Retry

The call is request/response – the caller (CC) waits on its own timeout. If NiFi is still retrying after CC gives up, the work is wasted and CC may re-send, causing duplicates.

**Worst-case NiFi time with current timeouts:**

| Call | FM down (fast fail) | FM hanging (timeouts hit) |
|---|---|---|
| PUT | ~7s | ~2.5 min |
| POST | ~7s | ~12 min |

**Budget rule:**
```
(attempt timeouts + back-offs + margin)  <  caller timeout
```

**Conclusions**
1. Short FM outages are recovered within the caller's timeout.
2. A full FM restart likely takes longer than the retry window and the caller timeout – NiFi fails cleanly and records the failure instead.
3. POST timeouts (60s) already exceed a typical caller timeout on a single attempt and need reducing.
4. Microservices cannot be changed, so NiFi cannot receive the caller's deadline; any syncing must be done within NiFi.

**To confirm**

| Item | Value |
|---|---|
| CC caller timeout | TBC |
| Red request expiration | TBC |
| FM restart time | TBC |
| FM normal response time | TBC |

**Options**

| Option | Description |
|---|---|
| A. Bound retries to caller timeout | Tune timeouts/attempts to fit the budget (this ticket) |
| B. Deadline-aware retry | NiFi records request arrival time and stops retrying when the caller's time is nearly up |
| C. Split by message type | Long durable retries for async traffic; bounded retries for request/response |

**Recommendation:** Option A now; B/C as follow-up stories.

---

## 8. Demo

| Scenario | Expected |
|---|---|
| FM running | Success, nothing in DLQ |
| FM restarted within retry window | Retries visible, then success |
| FM down | Retries exhausted → DLQ, NiFi error log, OTel ERROR record |
| 4xx response | No retry → DLQ |
| OTel Collector down | DLQ and NiFi log still work, no logging loop |

---

## 9. Future Work

- Add jitter to retries to avoid all messages retrying at once when FM recovers.
- Return a fast error response (e.g. 503) to Red/CC instead of letting the caller time out.
- Persistent DLQ with a replay path.
- Alerting on OTel ERROR records and DLQ depth.
- Confirm FM calls are safe to repeat (idempotency).
