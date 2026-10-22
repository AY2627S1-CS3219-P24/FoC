export type ErrandStatus =
  | 'CREATING'
  | 'CREATE_FAILED'
  | 'OPEN'
  | 'ACCEPTED'
  | 'PICKED_UP'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'EXPIRED'

export type CreditState =
  'RESERVED' | 'SETTLEMENT_PENDING' | 'SETTLED' | 'RELEASE_PENDING' | 'RELEASED'

type Participant = { id: string; name: string | null }

export type Errand = {
  id: string
  requester: Participant
  courier: Participant | null
  supplierId: string
  pickup: { name: string; address: string }
  deliveryLocation: string
  description: string
  notes: string
  creditReward: number
  status: ErrandStatus
  creditState: CreditState | null
  creationFailureCode: string | null
  createdAt: string
  updatedAt: string
  expiresAt: string
  acceptedAt: string | null
  pickedUpAt: string | null
  endedAt: string | null
}

export type ErrandActionResponse = {
  errandId: string
  status: ErrandStatus
  creditState: CreditState | null
}
