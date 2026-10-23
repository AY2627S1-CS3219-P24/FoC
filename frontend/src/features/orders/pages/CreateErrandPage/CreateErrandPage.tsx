import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useSearch } from '@tanstack/react-router'
import { isCancel } from 'axios'
import { Button } from '@base-ui/react/button'
import { getApiErrorCode } from '#/lib/httpErrors'
import { useSession } from '#/features/auth/hooks/useSession'
import { usePickupLocations } from '#/features/suppliers/hooks/usePickupLocations'
import { getCurrentUserId } from '#/features/auth/lib/accessTokenStore'
import { ErrandForm } from '../../components/ErrandForm/ErrandForm'
import { ErrandReview } from '../../components/ErrandReview/ErrandReview'
import {
  OrderFeedback,
  OrderNotice,
} from '../../components/OrderFeedback/OrderFeedback'
import { useOrderActions } from '../../hooks/useOrderActions'
import {
  clearCreation,
  loadCreation,
  newCreation,
  saveCreation,
} from '../../utils/creationRequest'
import {
  definiteCreationRejection,
  creationErrorOrderId,
  orderErrorMessage,
  orderErrorReason,
} from '../../utils/orderErrors'
import type { PendingCreation } from '../../utils/creationRequest'
import type { CreateErrandValues } from '../../schemas/createErrand.schema'
import styles from '../../styles/order.module.scss'

type KnownOrder = { id: string; posted: boolean }

export const CreateErrandPage = () => {
  const { userId } = useSession()
  if (!userId) return <OrderFeedback loading />
  return <CreationForm key={userId} userId={userId} />
}
const CreationForm = ({ userId }: { userId: string }) => {
  const { supplier: supplierId, from, category } = useSearch({
    from: '/authenticated/user/errands/new',
  })
  const pickups = usePickupLocations(Boolean(supplierId))
  const [original, setOriginal] = useState<PendingCreation | null>(() =>
    loadCreation(userId),
  )
  const preset =
    supplierId && !original
      ? (pickups.data?.find((location) => location.id === supplierId) ?? null)
      : null
  const [values, setValues] = useState<CreateErrandValues>(
    () =>
      original?.values ?? {
        pickup: null,
        deliveryLocation: '',
        description: '',
        notes: '',
        creditReward: 5,
        expiryMinutes: 60,
      },
  )
  const [review, setReview] = useState(!!original)
  const [unknown, setUnknown] = useState(!!original)
  const [failed, setFailed] = useState(false)
  const [localError, setLocalError] = useState<string | null>(null)
  const [knownOrder, setKnownOrder] = useState<KnownOrder | null>(null)
  useEffect(() => {
    if (!preset) return
    setValues((current) =>
      current.pickup?.id === preset.id
        ? current
        : { ...current, pickup: preset },
    )
  }, [preset])
  const formValues: CreateErrandValues =
    values.pickup || !preset ? values : { ...values, pickup: preset }
  const waitingForPickup =
    Boolean(supplierId) &&
    !original &&
    !formValues.pickup &&
    pickups.isPending
  const { create } = useOrderActions()
  const retryFailureReason = orderErrorReason(create.error)
  const navigate = useNavigate()
  const lock = useRef(false)
  const openOrder = async (destination: KnownOrder) => {
    if (userId !== getCurrentUserId()) return
    setLocalError(null)
    await navigate({
      to: '/errands/$orderId',
      params: { orderId: destination.id },
      search: {
        posted: destination.posted,
        tab: 'active',
        participation: 'requested',
        page: 0,
      },
    }).catch(() =>
      setLocalError(
        'The order page could not open. Find this errand in My Errands.',
      ),
    )
  }
  const submit = async () => {
    if (lock.current || knownOrder || userId !== getCurrentUserId()) return
    lock.current = true
    const pending = original ?? newCreation(userId, values)
    setLocalError(null)
    setFailed(false)
    if (!saveCreation(pending)) {
      setLocalError(
        'Unable to save this submission. Allow browser storage and try again.',
      )
      lock.current = false
      return
    }
    setOriginal(pending)
    let destination: KnownOrder | undefined
    try {
      const result = await create.mutateAsync(pending.input)
      if (userId !== getCurrentUserId()) return
      clearCreation(userId, pending.input.createRequestId)
      setOriginal(null)
      setUnknown(false)
      if (result.status === 'CREATE_FAILED') {
        setFailed(true)
      } else {
        destination = { id: result.errandId, posted: result.status === 'OPEN' }
      }
    } catch (error) {
      if (isCancel(error) || userId !== getCurrentUserId()) return
      const id = creationErrorOrderId(error)
      if (id) {
        clearCreation(userId, pending.input.createRequestId)
        setOriginal(null)
        setUnknown(false)
        destination = { id, posted: false }
      } else if (definiteCreationRejection(error) && !unknown) {
        clearCreation(userId, pending.input.createRequestId)
        setOriginal(null)
        setUnknown(false)
      } else setUnknown(true)
    } finally {
      lock.current = false
    }
    if (destination) {
      setKnownOrder(destination)
      await openOrder(destination)
    }
  }
  return (
    <div className={`${styles.page} ${styles.narrow}`}>
      {from === 'map' ? (
        <Link to="/map" className={styles.back}>
          ← Back to map
        </Link>
      ) : from === 'request' ? (
        <Link to="/request" search={{ category }} className={styles.back}>
          ← Back to Find a supplier
        </Link>
      ) : from === 'locations' && category ? (
        <Link to="/locations" search={{ category }} className={styles.back}>
          ← Back to locations
        </Link>
      ) : (
        <Link to="/home" className={styles.back}>
          ← Back to home
        </Link>
      )}
      {localError ? (
        <OrderNotice tone="error" title={localError} />
      ) : failed ? (
        <OrderNotice tone="error" title="Not enough available credits">
          This request was not posted. No credits were reserved for this
          attempt.
        </OrderNotice>
      ) : unknown ? (
        <OrderNotice tone="warning" title="Creation is not confirmed">
          Credits may already be reserved. Retry the original request to check
          and continue it.
          {retryFailureReason && (
            <>
              <br />
              Last attempt failed: {retryFailureReason}
            </>
          )}
        </OrderNotice>
      ) : create.error ? (
        <OrderNotice
          tone={
            getApiErrorCode(create.error) === 'CREDIT_ACCOUNT_PENDING'
              ? 'warning'
              : 'error'
          }
          title={orderErrorMessage(create.error)}
        />
      ) : null}
      <h1 className={styles.pageTitle}>
        {knownOrder
          ? 'View your errand'
          : review
            ? 'Review request'
            : 'Create an Errand'}
      </h1>
      {knownOrder ? (
        <div className={styles.actions}>
          <Button
            className={styles.button}
            onClick={() => void openOrder(knownOrder)}
          >
            Open order
          </Button>
          <Link
            to="/errands"
            search={{ tab: 'active', participation: 'requested', page: 0 }}
            className={styles.secondary}
          >
            Go to My Errands
          </Link>
        </div>
      ) : review ? (
        <ErrandReview
          values={values}
          pending={create.isPending}
          canEdit={!unknown}
          onEdit={() => {
            create.reset()
            setFailed(false)
            setLocalError(null)
            setReview(false)
            setOriginal(null)
            clearCreation(userId)
          }}
          onSubmit={() => void submit()}
          submitLabel={
            unknown
              ? 'Retry original request'
              : failed
                ? 'Try a new request'
                : undefined
          }
        />
      ) : waitingForPickup ? (
        <p className={styles.muted} role="status">
          Loading pickup location…
        </p>
      ) : (
        <ErrandForm
          values={formValues}
          onReview={(data) => {
            setValues(data)
            setReview(true)
          }}
        />
      )}
      {failed && (
        <Link
          to="/errands"
          search={{ tab: 'history', participation: 'requested', page: 0 }}
          className={styles.back}
        >
          View creation attempts in My Errands
        </Link>
      )}
    </div>
  )
}
