# Credit Service

Manages credit accounts, reservations, payments, and refunds. Consumes User and Order events through SQS and publishes settlement results through SNS.

Production requires `AWS_REGION`, `EVENTS_MESSAGING_TOPIC_ARN`, and `EVENTS_MESSAGING_QUEUE_NAME`. See [messaging infrastructure](../infra/terraform/README.md#messaging) for provisioning and permissions.
