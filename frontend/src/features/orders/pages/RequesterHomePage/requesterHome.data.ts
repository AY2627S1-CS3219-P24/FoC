import type { SupplierCategory } from '#/features/suppliers/types/supplier.types'

export const categoryImages: Record<SupplierCategory, string> = {
  FOOD: 'https://images.unsplash.com/photo-1504674900247-0877df9cc836?auto=format&fit=crop&w=640&q=80',
  COFFEE:
    'https://images.unsplash.com/photo-1495474472287-4d71bcdd2085?auto=format&fit=crop&w=640&q=80',
  SHOPPING:
    'https://images.unsplash.com/photo-1441986300917-64674bd600d8?auto=format&fit=crop&w=640&q=80',
  PRINTING:
    'https://images.unsplash.com/photo-1562654501-a0ccc0fc3fb1?auto=format&fit=crop&w=640&q=80',
  OTHER:
    'https://images.unsplash.com/photo-1521737711867-e3b97375f902?auto=format&fit=crop&w=640&q=80',
}

// Sample dashboard content until the order API is integrated.
export const recentErrands = [
  {
    id: 'ongoing-coffee',
    pickup: 'CoffeeBean @ COM3',
    destination: 'COM2',
    label: 'Ongoing',
    description: 'Ice latte, less sugar.',
  },
  {
    id: 'previous-coffee',
    pickup: 'CoffeeBean @ COM3',
    destination: 'COM2',
    label: 'Yesterday',
    description: 'Collect a coffee from CoffeeBean.',
  },
]
