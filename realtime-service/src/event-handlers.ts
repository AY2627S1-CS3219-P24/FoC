import type {Server} from 'socket.io';
import type {EventHandler} from './sqs.js';
import {broadcast, emitToUser} from './socket.js';
import {parseSupplierChangedEvent, parseUserUpdateEvent} from './events.js';

export const createEventHandlers = (
    socketServer: Server,
): EventHandler[] => [
    {
        eventType: 'UserUpdatedEvent',
        handle: (body) => {
            const event = parseUserUpdateEvent(body);
            emitToUser(socketServer, event.userId, event.event, event.data);
        },
    },
    {
        eventType: 'SupplierChangedEvent',
        handle: (body) => broadcast(socketServer, 'supplier.changed', parseSupplierChangedEvent(body).payload),
    },
];
