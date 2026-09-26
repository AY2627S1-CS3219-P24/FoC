import { z } from 'zod'
import { SUPPLIER_CATEGORIES } from '../types/supplier.types'
import type { Supplier, SupplierRequest } from '../types/supplier.types'

const time = (label: string) =>
  z.string().regex(/^\d{2}:\d{2}$/, `Please enter the ${label}.`)

// Keep the coordinate in text form
const coordinate = (min: number, max: number) =>
  z
    .string()
    .trim()
    .refine(
      (value) =>
        value === '' ||
        (Number.isFinite(Number(value)) &&
          Number(value) >= min &&
          Number(value) <= max),
      { message: `Must be a number between ${min} and ${max}.` },
    )

export const supplierSchema = z
  .object({
    name: z.string().trim().min(1, 'Please enter a name.').max(255),
    category: z.enum(SUPPLIER_CATEGORIES),
    building: z.string().trim().min(1, 'Please enter a building.').max(255),
    floor: z.string().trim().max(32),
    locationDescription: z.string().trim().max(500),
    openingTime: time('opening time'),
    closingTime: time('closing time'),
    latitude: coordinate(-90, 90),
    longitude: coordinate(-180, 180),
    imageUrl: z
      .string()
      .trim()
      .max(2048)
      .refine((value) => value === '' || /^https?:\/\/\S+$/.test(value), {
        message: 'Please enter an http(s) URL.',
      }),
    // To check if the store indeed close at midnight
    closesAfterMidnight: z.boolean(),
  })
  .superRefine((values, ctx) => {
    const { openingTime, closingTime, closesAfterMidnight } = values
    // "HH:MM" strings comparison
    let message: string | null = null
    if (openingTime === closingTime) {
      message = 'Closing time must be different from opening time.'
    } else if (!closesAfterMidnight && closingTime < openingTime) {
      message =
        'Closing time must be after opening time. Tick "Closes after midnight" if it closes the next day.'
    } else if (closesAfterMidnight && closingTime > openingTime) {
      message =
        'For a supplier that closes after midnight, closing time must be earlier than opening time.'
    }
    if (message)
      ctx.addIssue({ code: 'custom', path: ['closingTime'], message })
  })

export type SupplierFormValues = z.infer<typeof supplierSchema>

export const emptySupplierForm: SupplierFormValues = {
  name: '',
  category: 'FOOD',
  building: '',
  floor: '',
  locationDescription: '',
  openingTime: '09:00',
  closingTime: '18:00',
  latitude: '',
  longitude: '',
  imageUrl: '',
  closesAfterMidnight: false,
}

export const toSupplierFormValues = (
  supplier: Supplier,
): SupplierFormValues => ({
  name: supplier.name,
  category: supplier.category,
  building: supplier.building,
  floor: supplier.floor ?? '',
  locationDescription: supplier.locationDescription ?? '',
  openingTime: supplier.openingTime.slice(0, 5),
  closingTime: supplier.closingTime.slice(0, 5),
  latitude: supplier.latitude?.toString() ?? '',
  longitude: supplier.longitude?.toString() ?? '',
  imageUrl: supplier.imageUrl ?? '',
  closesAfterMidnight: supplier.closingTime < supplier.openingTime,
})

const optional = (value: string) => (value === '' ? null : value)

const optionalNumber = (value: string) => (value === '' ? null : Number(value))

export const toSupplierRequest = ({
  closesAfterMidnight: _closesAfterMidnight,
  ...values
}: SupplierFormValues): SupplierRequest => ({
  ...values,
  floor: optional(values.floor),
  locationDescription: optional(values.locationDescription),
  latitude: optionalNumber(values.latitude),
  longitude: optionalNumber(values.longitude),
  imageUrl: optional(values.imageUrl),
})
