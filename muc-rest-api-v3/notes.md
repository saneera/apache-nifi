Meeting Notes – Splunk Properties Gateway Testing

Date: Not specified

Topic: Splunk Properties Gateway Integration Testing

Attendees: Development Team, Test Team

⸻

Meeting Summary

The meeting focused on explaining the purpose of the Splunk Properties Gateway and how it integrates with the HFC2 system. The team confirmed that Splunk is no longer the preferred monitoring solution, so only minimal effort should be spent completing the testing and closing the work item.

The objective is simply to verify that Splunk can send data to the Properties Gateway through a webhook and that the received data is successfully published as a property in the Property Service.

⸻

Background

* Splunk is a third-party log aggregation and monitoring platform.
* It collects system information such as:
    * CPU usage
    * RAM usage
    * Disk usage
    * System logs
    * Metrics
* The original goal was to integrate Splunk with HFC2 so monitoring data could be published as Properties.

However:

* Nemesis has not confirmed Splunk as the long-term monitoring solution.
* Further development has been paused.
* The current work only exists to prove the integration.

⸻

Current Status

* Splunk is available in the SIL environment.
* Development environment (Dev4) also contains a working instance.
* Configuration work stopped after proving the integration.
* Advanced functionality was intentionally not implemented.

⸻

Integration Flow

```jsunicoderegexp
Splunk
      │
      │ Alert (Webhook)
      ▼
Splunk Properties Gateway
      │
      ▼
Property Service
      │
      ▼
Property visible inside HFC2
```


What the Properties Gateway Does

The gateway simply:

1. Receives a POST request from Splunk.
2. Accepts the payload.
3. Passes the payload to the Property Service.
4. Creates or updates a property.

No additional processing or filtering is currently performed.

⸻

Testing Scope

The team agreed that only a simple integration test is required.

The objective is to verify:

* Splunk is running.
* A Splunk alert is configured.
* The alert sends a webhook.
* The Properties Gateway receives the webhook.
* Property Service contains the property.
* The property value is updated.

No further functionality needs to be tested.

⸻

Splunk Alert Configuration

To configure the alert:

1. Open Splunk.
2. Create or run an SPL search.
3. Save the search as an Alert.
4. Configure the alert as:
    * Real-time Alert
    * Trigger per Result
5. Add a Webhook action.
6. Configure the webhook URL to point to the Properties Gateway endpoint.
7. Add the endpoint to the Splunk Webhook Allow List.
8. Save the configuration.

⸻

What to Verify

During testing, verify:

* Alert is triggered.
* Webhook reaches the Properties Gateway.
* POST request is received.
* Property Service contains the configured property.
* Property value is populated.
* Updated timestamp changes when new data arrives.

⸻

What Does NOT Need Testing

The following items are out of scope:

* HTTPS configuration
* Multiple webhook routing
* Property mapping improvements
* Splunk agent installation
* Splunk metrics configuration
* Nemesis integration
* Production deployment
* Advanced monitoring

⸻

Discussion on Multiple Webhooks

A question was raised about supporting multiple webhooks.

Response:

* Current implementation only proves connectivity.
* All incoming data is currently stored as a single property.
* Property mapping was intentionally not completed because the project was paused.

⸻

Nemesis Update

The team discussed Nemesis briefly.

Current status:

* Nemesis is still researching monitoring solutions.
* Splunk has not been confirmed.
* Further integration work is on hold.
* No additional development should be completed until requirements are finalized.

⸻

Test Case Recommendation

Suggested Jira Test Case:

Title

Verify Splunk Properties Gateway Integration

Objective

Verify that Splunk alert data is successfully received by the Properties Gateway and published into the Property Service.

Test Steps

1. Ensure Splunk is running.
2. Configure a real-time alert.
3. Configure the webhook.
4. Trigger the alert.
5. Verify the webhook reaches the Properties Gateway.
6. Verify the Property Service contains the configured property.
7. Verify the property value is populated.
8. Verify the property timestamp updates when new events are received.

Expected Result

* Splunk sends the webhook successfully.
* Properties Gateway receives the POST request.
* Property Service creates or updates the property.
* Property value is visible and updated correctly.

⸻

Decisions

* Spend minimal effort on Splunk testing.
* Do not extend the implementation.
* Complete a simple integration verification only.
* Document the testing in Jira.
* Use the work primarily to close the story formally.


Action Items



Key Takeaways

* Splunk is no longer expected to be the final monitoring solution.
* The implementation exists only as a proof of integration.
* The only requirement is to demonstrate that Splunk can send data to the Properties Gateway and that the data appears in the Property Service.
* No additional feature development is planned until Nemesis finalizes its monitoring approach.
