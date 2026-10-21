import assert from 'node:assert/strict';
import {describe, it} from 'node:test';
import type {Server} from 'socket.io';
import {createEventSubscriptions} from '../src/event-subscriptions.js';

const supplierId = '11111111-1111-4111-8111-111111111111';
const validSupplierEvent = {
    eventId: '22222222-2222-4222-8222-222222222222',
    occurredAt: '2026-01-01T00:00:00Z',
    payload: {supplierId, change: 'DEACTIVATED'},
};

const createSubscriptions = () => {
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
    const subscriptions = createEventSubscriptions(
        {userUpdatesDestination: 'user.updates', supplierChangedDestination: 'supplier.updates'},
        socketServerMock,
    );
    return {emissions, subscriptions};
};

const body = (value: unknown) => Buffer.from(JSON.stringify(value));

describe('realtime event subscriptions', () => {
    it('routes user updates from their configured exchange', () => {
        const {emissions, subscriptions} = createSubscriptions();

        assert.equal(subscriptions[0].destination, 'user.updates');
        subscriptions[0].handle(body({userId: 'user-1', event: 'order.updated', data: {orderId: 'order-1'}}));

        assert.deepEqual(emissions, [
            {room: 'user:user-1', event: 'order.updated', data: {orderId: 'order-1'}},
        ]);
    });

    it('routes supplier changes from their configured exchange', () => {
        const {emissions, subscriptions} = createSubscriptions();

        assert.equal(subscriptions[1].destination, 'supplier.updates');
        subscriptions[1].handle(body(validSupplierEvent));

        assert.deepEqual(emissions, [{event: 'supplier.changed', data: {supplierId, change: 'DEACTIVATED'}}]);
    });

    it('throws for malformed event bodies so the consumer can reject them', () => {
        const {subscriptions} = createSubscriptions();

        assert.throws(() => subscriptions[0].handle(body({event: 'missing user id'})));
        assert.throws(() => subscriptions[1].handle(body({payload: {supplierId, change: 'UNKNOWN'}})));
    });
});
