# Credit Service

Manages credit accounts, reservations, payments, and refunds. Consumes User and Order events through SQS and publishes settlement results through SNS.

Production requires `AWS_REGION`, `EVENTS_MESSAGING_TOPIC_ARN`, and `EVENTS_MESSAGING_QUEUE_NAME`. Production SNS/SQS provisioning, permissions, and runtime wiring are added separately in [deployment PR #42](https://github.com/AY2627S1-CS3219-P24/FoC/pull/42).
