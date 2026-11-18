Change these on both InvokeHTTPs

Before you start: the processors show Running. Right-click each one → Stop, or NiFi won’t let you edit them.

Step 1: Add an error-handling processor first

If you untick “terminate” on a relationship that has no connection, NiFi marks the processor invalid. So build the error path first:

Drag a new UpdateAttribute onto the canvas and name it Set Error Info.
In its Properties, add (with the + button):
dlq.reason = ${invokehttp.status.code:replaceNull(${invokehttp.java.exception.class})}
dlq.message = ${invokehttp.java.exception.message}
dlq.ts = ${now():format("yyyy-MM-dd HH:mm:ss")}
Add a LogMessage after it: Log Level = error, Log message = Circuit Manager call failed: ${dlq.reason} ${dlq.message}. This is your operator notification for now.
Add a Funnel after LogMessage and label it DLQ. Failed messages will sit in that queue for you to inspect or replay.
Step 2: Connect the failures

Drag a connection from each InvokeHTTP to Set Error Info and tick Failure, No Retry and Retry. NiFi unticks “terminate” for those automatically.

Step 3: Relationships tab (on each InvokeHTTP)
Relationship	terminate	retry
Failure (FM down, connection refused)	☐	☑
Retry (5xx)	☐	☑
No Retry (4xx)	☐	☐ (a bad request won’t succeed on retry)
Original	☑ (leave it)	☐
Response	☐ (leave it)	☐

When you tick retry, extra fields appear:

Number of Retry Attempts: 3
Retry Back Off Policy: Penalize
Retry Maximum Back Off Period: 10 sec
Step 4: Settings tab (easy to miss)

Penalty Duration defaults to 30 sec, and the retry back-off is based on it. Left at 30s, the waits would be 30s, 60s, 120s. Set it to 1 sec so the waits are 1s, 2s, 4s.

Step 5: Start both processors and test

Stop FM, or scale it to 0, and send a PUT. In Data Provenance on the InvokeHTTP you should see 4 attempts (1 initial + 3 retries) about 1s, 2s and 4s apart, then the message lands in the DLQ queue and an ERROR appears in the NiFi log. Then repeat, but start FM again within those few seconds, and it should succeed.

Your timeout finding (for the Markdown note)

Here are the real worst-case numbers from your current settings, with 4 attempts:

Per attempt (connect + write + read)	FM down (connection refused, fast)	FM hanging (timeouts hit)
PUT	5 + 15 + 15 = 35s	~7s total	4 × 35 + 7 ≈ 2.5 min
POST	60 + 60 + 60 = 180s	~7s total	4 × 180 + 7 ≈ 12 min

Points to write up for Jakob and Asif:

If FM’s pod is down, connections are refused instantly, so retries finish within about 7s. A full FM restart probably takes longer, so the retries run out before FM is back. That’s the demo conflict the ticket mentions.
If FM is up but slow or hanging, POST alone could take 3 minutes per attempt, far longer than CC will wait.
Questions to answer: what is CC’s timeout, how long does an FM restart take, and why is POST set to 60s (large fragment uploads?).
Proposal: keep total retry time under CC’s timeout. That means lowering the timeouts, e.g. connect 2–3s, and read only as long as FM normally needs.
Things to watch
Version control: your flow seems to be tracked in NiFi Registry (the ✔ 2 in the status bar). After testing, commit the change, or the next deploy will overwrite it.
No error goes back to Red yet. Failed messages now go to the DLQ, but CC still waits until it times out. Sending an error response back through “From Circuit Manager Black” is the next improvement. Ask the team how Red builds its HTTP response status.
Jitter isn’t covered by the built-in retry. Get this version working and demoed first, then add jitter as a follow-up.

Once it’s built, send me a screenshot and I’ll check it. When you have CC’s timeout and FM’s restart time, I can also write the Markdown note.
