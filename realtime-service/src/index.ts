import {createServer, type IncomingMessage, type ServerResponse} from 'node:http';
import pino from 'pino';
import {readConfig} from './config.js';
import {createEventSubscriptions} from './event-subscriptions.js';
import {createRabbitMqMessageConsumer} from './rabbitmq.js';
import {createSocketIoServer} from './socket.js';

const config = readConfig();
const logger = pino({
    transport: config.isDevelopment ? {target: 'pino-pretty'} : undefined,
});
const socketServer = createSocketIoServer(config.jwksUrl);
const handleHttpRequest = (request: IncomingMessage, response: ServerResponse): void => {
    if (request.url === '/health') {
        response.writeHead(200, {'Content-Type': 'text/plain'});
        response.end('OK');
        return;
    }

    response.writeHead(404);
    response.end();
};
const httpServer = createServer(handleHttpRequest);

socketServer.attach(httpServer);
const logListening = (): void => {
    logger.info({port: config.port}, 'Realtime service listening');
};
httpServer.listen(config.port, logListening);

const consumer = createRabbitMqMessageConsumer(config.rabbitMqUrl, logger);
consumer.start(createEventSubscriptions(config, socketServer));

let isShuttingDown = false;

const shutdown = async (signal: NodeJS.Signals): Promise<void> => {
    if (isShuttingDown) return;
    isShuttingDown = true;
    logger.info({signal}, 'Shutting down realtime service');

    try {
        await consumer.close();
        await closeSocketServer();
        logger.info('Realtime service shut down');
    } catch (error: unknown) {
        logger.error({err: error}, 'Error while shutting down realtime service');
        process.exitCode = 1;
    }
};

process.once('SIGINT', () => void shutdown('SIGINT'));
process.once('SIGTERM', () => void shutdown('SIGTERM'));

function closeSocketServer(): Promise<void> {
    return new Promise((resolve, reject) => {
        socketServer.close((error) => {
            if (error) {
                reject(error);
                return;
            }
            resolve();
        });
    });
}
