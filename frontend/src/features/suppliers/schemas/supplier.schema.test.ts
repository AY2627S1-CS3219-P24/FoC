import { describe, expect, it } from 'vitest'
import {
  emptySupplierForm,
  supplierSchema,
  toSupplierFormValues,
  toSupplierRequest,
} from './supplier.schema'
import type { Supplier } from '../types/supplier.types'

const validForm = {
  ...emptySupplierForm,
  name: 'Cool Spot',
  building: 'COM2',
}

describe('supplierSchema', () => {
  it('accepts a minimal supplier', () => {
    expect(supplierSchema.safeParse(validForm).success).toBe(true)
  })

  it('requires name and building', () => {
    const result = supplierSchema.safeParse(emptySupplierForm)
    const fields = result.error?.issues.map((issue) => issue.path[0])
    expect(fields).toEqual(expect.arrayContaining(['name', 'building']))
  })

  it('rejects non-http image URLs and out-of-range coordinates', () => {
    const result = supplierSchema.safeParse({
      ...validForm,
      imageUrl: 'javascript:alert(1)',
      latitude: '91',
    })
    const fields = result.error?.issues.map((issue) => issue.path[0])
    expect(fields).toEqual(expect.arrayContaining(['imageUrl', 'latitude']))
  })
})

describe('opening hours', () => {
  const hours = (
    openingTime: string,
    closingTime: string,
    closesAfterMidnight = false,
  ) =>
    supplierSchema.safeParse({
      ...validForm,
      openingTime,
      closingTime,
      closesAfterMidnight,
    })

  it('accepts a same-day schedule', () => {
    expect(hours('09:00', '18:00').success).toBe(true)
  })

  it('rejects early morning closing time as default unless it indeed closes after midnight', () => {
    const result = hours('09:00', '02:00')
    expect(result.error?.issues[0].path).toEqual(['closingTime'])
    expect(result.error?.issues[0].message).toMatch(/Closes after midnight/)
    expect(hours('09:00', '02:00', true).success).toBe(true)
  })

  it('rejects "closes after midnight" with a later closing time', () => {
    expect(hours('09:00', '18:00', true).success).toBe(false)
  })

  it('rejects identical opening and closing times', () => {
    expect(hours('09:00', '09:00').success).toBe(false)
    expect(hours('09:00', '09:00', true).success).toBe(false)
  })
})

describe('supplier form conversion', () => {
  it('sends blank optional fields as null', () => {
    expect(toSupplierRequest(validForm)).toMatchObject({
      floor: null,
      locationDescription: null,
      latitude: null,
      longitude: null,
      imageUrl: null,
    })
  })

  it('converts coordinates to numbers', () => {
    expect(
      toSupplierRequest({
        ...validForm,
        latitude: '1.2948',
        longitude: '103.77',
      }),
    ).toMatchObject({ latitude: 1.2948, longitude: 103.77 })
  })

  it('rejects coordinates that are not numbers', () => {
    const result = supplierSchema.safeParse({ ...validForm, longitude: 'abc' })
    expect(result.error?.issues[0].path).toEqual(['longitude'])
  })

  it('does not send the form-only overnight flag', () => {
    expect(
      toSupplierRequest({ ...validForm, closesAfterMidnight: true }),
    ).not.toHaveProperty('closesAfterMidnight')
  })

  it('trims seconds from API times for the form', () => {
    const supplier: Supplier = {
      id: '1',
      ...toSupplierRequest(validForm),
      openingTime: '09:00:00',
      closingTime: '02:00:00',
      active: true,
      createdAt: '',
      updatedAt: '',
    }
    expect(toSupplierFormValues(supplier)).toMatchObject({
      openingTime: '09:00',
      closingTime: '02:00',
      floor: '',
      closesAfterMidnight: true,
    })
  })
})
