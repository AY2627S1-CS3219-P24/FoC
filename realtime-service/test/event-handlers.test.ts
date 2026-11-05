import assert from 'node:assert/strict';
import {describe, it} from 'node:test';
import type {Server} from 'socket.io';
import {createEventHandlers} from '../src/event-handlers.js';

const supplierId = '11111111-1111-4111-8111-111111111111';
const validSupplierEvent = {
    eventId: '22222222-2222-4222-8222-222222222222',
    occurredAt: '2026-01-01T00:00:00Z',
    payload: {supplierId, change: 'DEACTIVATED'},
};

const createHandlers = () => {
    const emissions: {room?: string; event: string; data: unknown}[] = [];
    const socketServer = {
        to(room: string) {
            return {
                emit(event: string, data: unknown) {
                    emissions.push({room, event, data});
                },
            };
        },
        emit(event: string, data: unknown) {
            emissions.push({event, data});
        },
        sockets: {sockets: new Map()},
    };
    const socketServerMock = socketServer as unknown as Server;
    const handlers = createEventHandlers(
        socketServerMock,
    );
    return {emissions, handlers};
};

const body = (value: unknown) => Buffer.from(JSON.stringify(value));

describe('realtime event handlers', () => {
    it('routes user updates from their event type', () => {
        const {emissions, handlers} = createHandlers();

        assert.equal(handlers[0].eventType, 'UserUpdatedEvent');
        handlers[0].handle(body({userId: 'user-1', event: 'order.updated', data: {orderId: 'order-1'}}));

        assert.deepEqual(emissions, [
            {room: 'user:user-1', event: 'order.updated', data: {orderId: 'order-1'}},
        ]);
    });

    it('routes supplier changes from their event type', () => {
        const {emissions, handlers} = createHandlers();

        assert.equal(handlers[1].eventType, 'SupplierChangedEvent');
        handlers[1].handle(body(validSupplierEvent));

        assert.deepEqual(emissions, [{event: 'supplier.changed', data: {supplierId, change: 'DEACTIVATED'}}]);
    });

    it('throws for malformed event bodies so the consumer can reject them', () => {
        const {handlers} = createHandlers();

        assert.throws(() => handlers[0].handle(body({event: 'missing user id'})));
        assert.throws(() => handlers[1].handle(body({payload: {supplierId, change: 'UNKNOWN'}})));
    });
});
