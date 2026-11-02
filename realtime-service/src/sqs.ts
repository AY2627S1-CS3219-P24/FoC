import {DeleteMessageCommand, ReceiveMessageCommand, SQSClient} from '@aws-sdk/client-sqs';
import {setTimeout} from 'node:timers/promises';
import type {Logger} from 'pino';

export type EventSubscription = {
    eventType: string;
    handle: (body: Buffer) => void;
};

export const createSqsEventConsumer = (queueUrl: string, client: SQSClient, logger: Logger) => {
    const controller = new AbortController();
    let polling: Promise<void> | undefined;

    const poll = async (subscriptions: EventSubscription[]): Promise<void> => {
        const {signal} = controller;
        while (!signal.aborted) {
            try {
                const response = await client.send(new ReceiveMessageCommand({
                    QueueUrl: queueUrl,
                    WaitTimeSeconds: 20,
                    MaxNumberOfMessages: 10,
                    MessageAttributeNames: ['eventType'],
                }), {abortSignal: signal});
                for (const message of response.Messages ?? []) {
                    if (signal.aborted) break;
                    try {
                        const eventType = message.MessageAttributes?.eventType?.StringValue;
                        const subscription = subscriptions.find((item) => item.eventType === eventType);
                        if (!subscription || !message.Body || !message.ReceiptHandle) {
                            throw new Error('Missing or unsupported SQS event type, body, or receipt handle');
                        }
                        subscription.handle(Buffer.from(message.Body));
                        await client.send(new DeleteMessageCommand({
                            QueueUrl: queueUrl,
                            ReceiptHandle: message.ReceiptHandle,
                        }), {abortSignal: signal});
                    } catch (error: unknown) {
                        if (signal.aborted) break;
                        // Leave failed messages for retry and the queue's configured DLQ redrive policy.
                        logger.warn({err: error, messageId: message.MessageId}, 'SQS event handling failed');
                    }
                }
            } catch (error: unknown) {
                if (signal.aborted) break;
                logger.error({err: error}, 'SQS receive failed');
                await setTimeout(1000, undefined, {signal}).catch(() => {});
            }
        }
    };

    return {
        start(subscriptions: EventSubscription[]): void {
            if (polling) throw new Error('SQS consumer already started');
            polling = poll(subscriptions);
        },
        async close(): Promise<void> {
            controller.abort();
            await polling;
            client.destroy();
        },
    };
};
