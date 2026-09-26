export const SUPPLIER_CATEGORIES = [
  'FOOD',
  'COFFEE',
  'SHOPPING',
  'PRINTING',
  'OTHER',
] as const

export type SupplierCategory = (typeof SUPPLIER_CATEGORIES)[number]

export type Supplier = {
  id: string
  name: string
  category: SupplierCategory
  building: string
  floor: string | null
  locationDescription: string | null
  latitude: number | null
  longitude: number | null
  /** Use ISO local time, eg."09:00:00". */
  openingTime: string
  closingTime: string
  imageUrl: string | null
  active: boolean
  createdAt: string
  updatedAt: string
}

export type SupplierRequest = Omit<
  Supplier,
  'id' | 'active' | 'createdAt' | 'updatedAt'
>
