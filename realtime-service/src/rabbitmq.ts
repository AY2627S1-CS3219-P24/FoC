import {connect} from 'amqp-connection-manager';
import type {ConfirmChannel, ConsumeMessage} from 'amqplib';
import type {Logger} from 'pino';

export type MessageSubscription = {
    destination: string;
    handle: (body: Buffer) => void;
};

export const createRabbitMqMessageConsumer = (url: string, logger: Logger) => {
    let connection: ReturnType<typeof connect> | undefined;
    const start = (subscriptions: MessageSubscription[]): void => {
        connection = connect([url]);
        for (const subscription of subscriptions) {
            const channel = connection.createChannel({
                setup: async (brokerChannel: ConfirmChannel) => {
                    await brokerChannel.assertExchange(subscription.destination, 'fanout', {durable: true});
                    const {queue} = await brokerChannel.assertQueue('', {exclusive: true});
                    await brokerChannel.bindQueue(queue, subscription.destination, '');
                    await brokerChannel.consume(queue, (delivery: ConsumeMessage | null) => {
                        if (!delivery) return;
                        try {
                            subscription.handle(delivery.content);
                            brokerChannel.ack(delivery);
                        } catch (error: unknown) {
                            logger.warn({err: error, destination: subscription.destination}, 'Rejecting RabbitMQ message');
                            brokerChannel.reject(delivery, false);
                        }
                    });
                },
            });
            void channel.waitForConnect().catch((error: unknown) => {
                logger.error({err: error, destination: subscription.destination}, 'RabbitMQ subscription failed');
            });
        }
    };

    const close = async (): Promise<void> => {
        await connection?.close();
    };

    return {start, close};
};
