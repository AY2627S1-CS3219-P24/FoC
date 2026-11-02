import assert from 'node:assert/strict';
import {describe, it} from 'node:test';
import {readConfig} from '../src/config.js';

const urls = {
    JWKS_URL: 'http://localhost:8080/api/.well-known/jwks.json',
    SQS_QUEUE_URL: 'http://localhost:4566/000000000000/foc-dev-realtime-events',
};

describe('realtime config', () => {
    it('uses defaults for the port and AWS region', () => {
        assert.deepEqual(readConfig(urls), {
            isDevelopment: true,
            port: 8084,
            jwksUrl: urls.JWKS_URL,
            queueUrl: urls.SQS_QUEUE_URL,
            region: 'ap-southeast-1',
            endpoint: undefined,
        });
    });

    it('resolves runtime and AWS endpoint overrides', () => {
        const config = readConfig({
            ...urls,
            NODE_ENV: 'production',
            PORT: '3200',
            AWS_REGION: 'eu-west-1',
            AWS_ENDPOINT_URL: 'http://floci:4566',
        });
        assert.equal(config.isDevelopment, false);
        assert.equal(config.port, 3200);
        assert.equal(config.region, 'eu-west-1');
        assert.equal(config.endpoint, 'http://floci:4566');
    });

    it('requires JWKS_URL and SQS_QUEUE_URL', () => {
        assert.throws(() => readConfig({}));
        assert.throws(() => readConfig({SQS_QUEUE_URL: urls.SQS_QUEUE_URL}));
        assert.throws(() => readConfig({JWKS_URL: urls.JWKS_URL}));
    });

    it('rejects invalid ports and URLs', () => {
        assert.throws(() => readConfig({...urls, PORT: 'not-a-port'}));
        assert.throws(() => readConfig({...urls, JWKS_URL: 'not-a-url'}));
        assert.throws(() => readConfig({...urls, SQS_QUEUE_URL: 'not-a-url'}));
        assert.throws(() => readConfig({...urls, AWS_ENDPOINT_URL: 'not-a-url'}));
    });
});
