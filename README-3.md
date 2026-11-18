# HFC2 – Circuit Manager (Black NiFi): Retry, Dead Letter & Operator Notification

**Sprint:** HFC2-Cycle2-Sprint 9 · **PI:** PI9
**Component:** Apache NiFi 2.7.2 – BLACK · Process Group: `Circuit Manager`
**Reviewers:** Jakob, Asif
**Status:** Draft – for review

---

## 1. Story

> As an HFC2 operator, I want automatic retries with jitter and bounds for transient TCP and downstream errors, so that I can improve success rates without manual intervention.

**Scope:** retry policies, dead-letter routing, and operator notifications.

**Acceptance criteria**
- NiFi processor has relationships for success, retry and failure
   - Identify the processors that need handling (end-service call)
   - Implement recovery and failure handling
- Demo: restart FM and show that NiFi retries
- Review whether caller timeout (e.g. CC) and NiFi retry can be synced; otherwise record the finding here

---

## 2. Current State (before change)

### Flow

```
Input from Red ─► RouteOnAttribute
                   ├─ is_put_fragments  ─► InvokeHTTP (PUT)  ──Response──┐
                   └─ is_post_fragments ─► UpdateAttribute ─► InvokeHTTP (POST) ──Response──┤
                                                                          ▼
                                                                   UpdateAttribute
                                                                    ├─► Output Port "From Circuit Manager Black" (→ Red)
                                                                    └─► ExecuteScript ─► InvokeHTTP
```

### Processors calling the end service (Fragment Manager)

| Processor | Method | URL | Connect timeout | Read timeout | Write timeout |
|---|---|---|---|---|---|
| InvokeHTTP (PUT) | PUT | `#{fragment-manager-url}${http.servl...}` | 5 s | 15 s | 15 s |
| InvokeHTTP (POST) | POST | `#{fragment-manager-url}${http.servl...}` | 60 s | 60 s | 60 s |

### Relationship configuration (both processors)

| Relationship | Meaning | Current |
|---|---|---|
| Response | 2xx response | Connected |
| Original | Request FlowFile on 2xx | Auto-terminated |
| Retry | 5xx response | **Auto-terminated** |
| No Retry | 4xx response | **Auto-terminated** |
| Failure | Socket / connection errors | **Auto-terminated** |

### Problem

Any transient error (FM down, connection refused, timeout, 5xx) causes the FlowFile to be **silently dropped**:
- no retry
- no record of the failed message
- no operator notification
- Red / CC receives no response and waits until its own timeout

---

## 3. Design

### 3.1 Error classification

| InvokeHTTP relationship | Cause | Classification | Action |
|---|---|---|---|
| Failure | Connection refused, connect/read timeout, socket error | Transient (TCP) | Retry, then DLQ |
| Retry | HTTP 5xx | Transient (downstream) | Retry, then DLQ |
| No Retry | HTTP 4xx | Permanent | DLQ immediately |
| Response | HTTP 2xx | Success | Continue as today |

### 3.2 Retry policy (NiFi built-in relationship retry)

Configured on each InvokeHTTP → **Relationships** tab for `Failure` and `Retry`:

| Setting | Value |
|---|---|
| Number of Retry Attempts | 3 (1 initial + 3 retries = 4 attempts) |
| Retry Back Off Policy | Penalize |
| Retry Maximum Back Off Period | 10 sec |
| Penalty Duration (Settings tab) | 1 sec (default is 30 sec – must change) |

Resulting back-off: **1 s → 2 s → 4 s** (exponential, capped at 10 s).

> **Jitter:** NiFi's built-in relationship retry does not support jitter. See §6 (Future work).

### 3.3 Dead-letter routing

```
InvokeHTTP (PUT/POST)
   ├─ Failure  (after retries) ─┐
   ├─ Retry    (after retries) ─┼─► UpdateAttribute "Set Error Info" ─► LogMessage (ERROR) ─► Funnel "DLQ"
   └─ No Retry (immediately)  ──┘
```

**Set Error Info** (UpdateAttribute):

| Attribute | Value |
|---|---|
| `dlq.reason` | `${invokehttp.status.code:replaceNull(${invokehttp.java.exception.class})}` |
| `dlq.message` | `${invokehttp.java.exception.message}` |
| `dlq.ts` | `${now():format("yyyy-MM-dd HH:mm:ss")}` |

Failed FlowFiles queue in front of the **DLQ** funnel, where they can be inspected (List Queue) and replayed manually.

### 3.4 Operator notification

- **LogMessage** at level `error`:
  `Circuit Manager call failed: ${dlq.reason} ${dlq.message}`
- Recommended follow-up: alert on DLQ queue count > 0 using NiFi's Prometheus metrics endpoint (or PutEmail if SMTP is available in the environment).

---

## 4. Implementation Steps

1. **Stop** both InvokeHTTP processors (PUT and POST).
2. Add **UpdateAttribute** `Set Error Info` with the attributes in §3.3.
3. Add **LogMessage** (Log Level = `error`) and connect `Set Error Info` → LogMessage.
4. Add a **Funnel** labelled `DLQ` and connect LogMessage → Funnel.
5. From **each** InvokeHTTP, connect `Failure`, `Retry`, `No Retry` → `Set Error Info`.
6. On each InvokeHTTP → **Relationships** tab:

   | Relationship | terminate | retry |
      |---|---|---|
   | Failure | ☐ | ☑ |
   | Retry | ☐ | ☑ |
   | No Retry | ☐ | ☐ |
   | Original | ☑ | ☐ |
   | Response | ☐ | ☐ |

   Retry settings: Attempts = 3, Back Off Policy = Penalize, Max Back Off = 10 sec.
7. On each InvokeHTTP → **Settings** tab: **Penalty Duration = 1 sec**.
8. **Start** processors and run the test plan (§5).
9. **Commit** the change to NiFi Registry so the next deployment does not overwrite it.

---

## 5. Test / Demo Plan

| # | Scenario | Steps | Expected result |
|---|---|---|---|
| 1 | Happy path | FM running, send PUT and POST | Response → back to Red, nothing in DLQ |
| 2 | FM restart – recovers | Scale FM to 0, send request, scale FM back up within ~7 s | Provenance shows retries (1 s, 2 s, 4 s), then success |
| 3 | FM down – exhausted | Scale FM to 0, send request, leave it down | 4 attempts, FlowFile in DLQ, ERROR in NiFi log |
| 4 | Permanent error | Send request that returns 4xx | No retry, straight to DLQ |

Commands:
```bash
kubectl scale deploy/<fm-deployment> --replicas=0 -n <namespace>
kubectl scale deploy/<fm-deployment> --replicas=1 -n <namespace>
```

Evidence: Data Provenance on each InvokeHTTP (event times show back-off), DLQ queue listing, NiFi app log.

---

## 6. Finding: Caller Timeout vs NiFi Retry

### The conflict

The call is **request/response**: CC → Red NiFi → Black NiFi → FM, with the response returning the same way. CC waits on its own timeout. If NiFi is still retrying after CC gives up:
- the retry work is wasted (no one is waiting for the result)
- if CC re-sends, FM may receive **duplicate** requests
- Red's `HandleHttpRequest` / `StandardHttpContextMap` **Request Expiration** is a third timeout that also needs to line up

### Worst-case NiFi time with current settings (4 attempts)

| Processor | Per attempt (connect + write + read) | FM down (connection refused – fast fail) | FM hanging (timeouts hit) |
|---|---|---|---|
| PUT | 5 + 15 + 15 = 35 s | ≈ 7 s (back-off only) | 4 × 35 + 7 ≈ **147 s** |
| POST | 60 + 60 + 60 = 180 s | ≈ 7 s (back-off only) | 4 × 180 + 7 ≈ **727 s (~12 min)** |

### Budget rule

```
Σ(attempt timeouts) + Σ(back-offs) + safety margin  <  CC timeout
```

### Values to confirm

| Item | Value |
|---|---|
| CC caller timeout | **TBC** |
| Red `StandardHttpContextMap` Request Expiration | **TBC** (default 1 min) |
| FM pod restart time (down → ready) | **TBC** |
| FM normal response time (PUT / POST) | **TBC** |
| Reason POST timeouts are 60 s (large fragments?) | **TBC** |

### Conclusions

1. **Short outages (seconds)** are recovered by the retry policy within the caller's timeout.
2. **A full FM restart likely takes longer than the retry window and the CC timeout.** Synchronous requests cannot survive a full FM restart without exceeding CC's timeout; NiFi's role in that case is to fail cleanly and record the failure (DLQ + log).
3. **POST timeouts (60 s) already exceed a typical caller timeout on a single attempt.** These need to be reduced to fit the budget once CC's timeout is known.
4. Because the microservices cannot be changed, NiFi cannot read a deadline from the caller. Syncing would need NiFi itself to track the request age (see §7).

### Options for discussion

| Option | Description | Pros | Cons |
|---|---|---|---|
| A. Bound retries to CC timeout | Tune timeouts/attempts so worst case < CC timeout | Simple, config only | Cannot survive full FM restart |
| B. Deadline-aware retry | Stamp `ingress.ts` on entry to Red; stop retrying when `now + next attempt > ingress.ts + CC timeout − margin` | Retries use the full budget, never exceed it | More flow logic; CC timeout held as a NiFi parameter |
| C. Split by message type | Long durable retries for async/fire-and-forget; bounded retries for request/response | Async traffic survives FM restarts | Need to classify traffic |

**Recommendation:** A now (this ticket), B/C as follow-up stories after review.

---

## 7. Future Work

- **Jitter:** replace built-in retry with a retry loop (UpdateAttribute computes `retry.next.at = now + backoff + random()`, RouteOnAttribute releases when due) to avoid all queued requests retrying at the same instant after FM recovers.
- **Error response to Red:** route DLQ FlowFiles (with e.g. `http.status = 503`) to the `From Circuit Manager Black` output port so CC receives a fast, clean error instead of timing out. Needs confirmation of how Red builds its HTTP response.
- **Persistent DLQ:** PutFile / dedicated store so failed messages survive pod restarts, with a replay path.
- **Alerting:** Prometheus/Grafana alert on DLQ queue depth.
- **Parameterise** retry and timeout values in a Parameter Context per environment.
- **Idempotency:** confirm whether FM PUT/POST are safe to repeat after a read timeout (request may already have been processed).
- Review the `ExecuteScript → InvokeHTTP` branch to confirm whether it also calls FM and needs the same handling.
