import assert from 'node:assert/strict';
import {describe, it} from 'node:test';
import {readConfig} from '../src/config.js';

describe('realtime config', () => {
    it('uses defaults for the port and event destinations', () => {
        assert.deepEqual(readConfig({
            JWKS_URL: 'http://localhost:8080/api/.well-known/jwks.json',
            RABBITMQ_URL: 'amqp://localhost',
        }), {
            isDevelopment: true,
            port: 8084,
            jwksUrl: 'http://localhost:8080/api/.well-known/jwks.json',
            rabbitMqUrl: 'amqp://localhost',
            userUpdatesDestination: 'user.updates',
            supplierChangedDestination: 'foc.supplier-changed.v1',
        });
    });

    it('resolves destinations and runtime values from the environment', () => {
        const config = readConfig({
            NODE_ENV: 'production',
            PORT: '3200',
            JWKS_URL: 'https://users.example.test/jwks',
            RABBITMQ_URL: 'amqps://broker.example.test',
            USER_UPDATES_DESTINATION: 'user.events',
            SUPPLIER_CHANGED_DESTINATION: 'supplier.changed.v2',
        });

        assert.deepEqual(config, {
            isDevelopment: false,
            port: 3200,
            jwksUrl: 'https://users.example.test/jwks',
            rabbitMqUrl: 'amqps://broker.example.test',
            userUpdatesDestination: 'user.events',
            supplierChangedDestination: 'supplier.changed.v2',
        });
    });

    it('requires JWKS_URL and RABBITMQ_URL', () => {
        assert.throws(() => readConfig({}));
        assert.throws(() => readConfig({RABBITMQ_URL: 'amqp://localhost'}));
        assert.throws(() => readConfig({JWKS_URL: 'http://localhost:8080/jwks'}));
    });

    it('uses the default user updates destination when not configured', () => {
        const config = readConfig({
            JWKS_URL: 'http://localhost:8080/jwks',
            RABBITMQ_URL: 'amqp://localhost',
        });

        assert.equal(config.userUpdatesDestination, 'user.updates');
    });

    it('rejects invalid port and URL values at startup', () => {
        const validUrls = {
            JWKS_URL: 'http://localhost:8080/jwks',
            RABBITMQ_URL: 'amqp://localhost',
        };
        assert.throws(() => readConfig({...validUrls, PORT: 'not-a-port'}));
        assert.throws(() => readConfig({...validUrls, JWKS_URL: 'not-a-url'}));
    });
});
