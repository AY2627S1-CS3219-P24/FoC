import {z} from 'zod';

export const userUpdateEventSchema = z.object({
    userId: z.string(),
    event: z.string(),
    data: z.json(),
});

export type UserUpdateEvent = z.infer<typeof userUpdateEventSchema>;

export const parseUserUpdateEvent = (body: Buffer): UserUpdateEvent =>
    userUpdateEventSchema.parse(JSON.parse(body.toString('utf8')));



export const supplierChangedEventSchema = z.object({
    eventId: z.uuid(),
    occurredAt: z.iso.datetime({offset: true}),
    payload: z.object({
        supplierId: z.uuid(),
        change: z.enum(['CREATED', 'UPDATED', 'ACTIVATED', 'DEACTIVATED', 'IMAGE_CHANGED']),
    }),
});

export type SupplierChangedEvent = z.infer<typeof supplierChangedEventSchema>;

export const parseSupplierChangedEvent = (body: Buffer): SupplierChangedEvent =>
    supplierChangedEventSchema.parse(JSON.parse(body.toString('utf8')));
