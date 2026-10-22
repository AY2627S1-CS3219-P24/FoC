import {z} from 'zod';

const environmentSchema = z.object({
    NODE_ENV: z.string().optional(),
    PORT: z.coerce.number().int().positive().default(8084),
    JWKS_URL: z.url(),
    RABBITMQ_URL: z.url(),
    USER_UPDATES_DESTINATION: z.string().optional(),
    SUPPLIER_CHANGED_DESTINATION: z.string().optional(),
});

export type Config = {
    isDevelopment: boolean;
    port: number;
    jwksUrl: string;
    rabbitMqUrl: string;
    userUpdatesDestination: string;
    supplierChangedDestination: string;
};

export const readConfig = (env: NodeJS.ProcessEnv = process.env): Config => {
    const parsed = environmentSchema.parse(env);

    return {
        isDevelopment: parsed.NODE_ENV === 'development' || !parsed.NODE_ENV,
        port: parsed.PORT,
        jwksUrl: parsed.JWKS_URL,
        rabbitMqUrl: parsed.RABBITMQ_URL,
        userUpdatesDestination: parsed.USER_UPDATES_DESTINATION ?? 'user.updates',
        supplierChangedDestination: parsed.SUPPLIER_CHANGED_DESTINATION ?? 'foc.supplier-changed.v1',
    };
};
