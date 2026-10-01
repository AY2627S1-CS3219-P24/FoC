// Temporary frontend examples; replace with order data when the API is connected.
export const sampleErrands = [
  {
    id: 'coffee',
    pickup: 'CoffeeBean @ COM3',
    destination: 'COM2',
    description: 'Ice latte, less sugar. “Call when arriving”',
    credits: 5,
    expiresInMinutes: 4,
    isOwnRequest: false,
  },
  {
    id: 'printing',
    pickup: 'PGP Foyer',
    destination: 'UTown',
    description: 'Collect 20 printed pages',
    credits: 4,
    expiresInMinutes: 5,
    isOwnRequest: false,
  },
  {
    id: 'own-request',
    pickup: 'PC Commons',
    destination: 'AS8',
    description: 'You cannot accept your created errand',
    credits: 6,
    expiresInMinutes: null,
    isOwnRequest: true,
  },
]
