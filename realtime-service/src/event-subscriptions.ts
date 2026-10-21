import type {Server} from 'socket.io';
import type {Config} from './config.js';
import type {MessageSubscription} from './rabbitmq.js';
import {broadcast, emitToUser} from './socket.js';
import {parseSupplierChangedEvent, parseUserUpdateEvent} from './events.js';

export const createEventSubscriptions = (
    config: Pick<Config, 'userUpdatesDestination' | 'supplierChangedDestination'>,
    socketServer: Server,
): MessageSubscription[] => [
    {
        destination: config.userUpdatesDestination,
        handle: (body) => {
            const event = parseUserUpdateEvent(body);
            emitToUser(socketServer, event.userId, event.event, event.data);
        },
    },
    {
        destination: config.supplierChangedDestination,
        handle: (body) => broadcast(socketServer, 'supplier.changed', parseSupplierChangedEvent(body).payload),
    },
];
