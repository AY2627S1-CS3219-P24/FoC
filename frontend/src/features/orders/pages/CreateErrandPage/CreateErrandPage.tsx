import { useRef, useState } from 'react'
import { Link, useNavigate, useSearch } from '@tanstack/react-router'
import { isCancel } from 'axios'
import { Button } from '@base-ui/react/button'
import { getApiErrorCode } from '#/lib/httpErrors'
import { useSession } from '#/features/auth/hooks/useSession'
import { getCurrentUserId } from '#/features/auth/lib/accessTokenStore'
import { ErrandForm } from '../../components/ErrandForm/ErrandForm'
import { ErrandReview } from '../../components/ErrandReview/ErrandReview'
import {
  OrderFeedback,
  OrderNotice,
} from '../../components/OrderFeedback/OrderFeedback'
import { useOrderActions } from '../../hooks/useOrderActions'
import { useMyErrands, useOrder } from '../../hooks/useOrders'
import { usePickupLocations } from '#/features/suppliers/hooks/usePickupLocations'
import { findSameActiveRequest, reorderValues } from '../../utils/reorder'
import { formatOrderTime } from '../../utils/orderFormat'
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
import type { Errand } from '../../types/errand.types'
import type { CreateErrandValues } from '../../schemas/createErrand.schema'
import { useCreditBalance } from '#/features/credits/hooks/useCreditBalance'
import styles from '../../styles/order.module.scss'
import pageStyles from './CreateErrandPage.module.scss'

type KnownOrder = { id: string; posted: boolean }

// repost errand info
type CopiedErrand = { from: Errand; values: CreateErrandValues }

export const CreateErrandPage = () => {
  const { userId } = useSession()
  const { from } = useSearch({ from: '/authenticated/user/errands/new' })
  if (!userId) return <OrderFeedback loading />
  return from ? (
    <CopiedCreationForm key={`${userId}:${from}`} userId={userId} id={from} />
  ) : (
    <CreationForm key={userId} userId={userId} />
  )
}
// load past errand, allow adjust the info for the form
const CopiedCreationForm = ({ userId, id }: { userId: string; id: string }) => {
  const past = useOrder(id)
  const locations = usePickupLocations(true)
  // unconfirmed submission need be resolve before request repost
  if (loadCreation(userId)) return <CreationForm userId={userId} />
  if (past.isPending || locations.isPending) return <OrderFeedback loading />
  if (!past.data || past.data.requester.id !== userId)
    return <CreationForm userId={userId} />
  return (
    <CreationForm
      userId={userId}
      copied={{
        from: past.data,
        values: reorderValues(past.data, locations.data ?? []),
      }}
    />
  )
}
const CreationForm = ({
  userId,
  copied,
}: {
  userId: string
  copied?: CopiedErrand
}) => {
  const [original, setOriginal] = useState<PendingCreation | null>(() =>
    loadCreation(userId),
  )
  const [values, setValues] = useState<CreateErrandValues>(
    () =>
      original?.values ??
      copied?.values ?? {
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
  const { create } = useOrderActions()
  const active = useMyErrands({
    as: 'requester',
    view: 'active',
    page: 0,
    size: 100,
  })
  const sameRequest =
    review && !knownOrder && !unknown && !failed
      ? findSameActiveRequest(values, active.data?.items ?? [])
      : undefined
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
    <div className={styles.page}>
      <Link to="/home" search={{ mode: 'requestor' }} className={styles.back}>
        ← Back to home
      </Link>
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
      {copied && !original && !knownOrder && (
        <OrderNotice
          tone="success"
          title={`Copied from your request on ${formatOrderTime(copied.from.createdAt)}`}
        >
          {copied.values.pickup
            ? 'Check the details, including any order number in the notes, then post it again.'
            : `${copied.from.pickup.name} is no longer available. Choose another pickup location.`}
        </OrderNotice>
      )}
      {sameRequest && (
        <OrderNotice
          tone="warning"
          title="You already have the same request in progress"
        >
          It was posted {formatOrderTime(sameRequest.createdAt)}. Post another
          one anyway?
        </OrderNotice>
      )}
      <h1 className={styles.pageTitle}>
        {knownOrder
          ? 'View your errand'
          : review
            ? 'Review request'
            : 'Create an Errand'}
      </h1>
      <div className={pageStyles.layout}>
        <div className={pageStyles.main}>
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
                    : sameRequest
                      ? 'Post another request'
                      : undefined
              }
            />
          ) : (
            <ErrandForm
              values={values}
              onReview={(data) => {
                setValues(data)
                setReview(true)
              }}
            />
          )}
        </div>
        <CreationGuide userId={userId} />
      </div>
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

/** Beside the form: the balance to spend from, and what happens after posting. */
const CreationGuide = ({ userId }: { userId: string }) => {
  const balance = useCreditBalance(userId)
  return (
    <aside className={pageStyles.guide} aria-label="About posting an errand">
      <section className={styles.panel}>
        <h2>Your credits</h2>
        <p className={pageStyles.balance}>
          {balance.data ? balance.data.availableBalance : '–'}
          <span> available</span>
        </p>
        <p className={styles.muted}>
          The reward you set is reserved when you post, and paid to your courier
          only after you confirm you received the item.
        </p>
      </section>
      <section className={styles.panel}>
        <h2>How it works</h2>
        <ol className={pageStyles.steps}>
          <li>
            <strong>Post your request</strong>
            <span>Couriers nearby can see it until it expires.</span>
          </li>
          <li>
            <strong>A courier accepts</strong>
            <span>You will see who is collecting it.</span>
          </li>
          <li>
            <strong>They pick it up</strong>
            <span>The errand moves to “Picked up”.</span>
          </li>
          <li>
            <strong>Confirm received</strong>
            <span>
              The courier is paid. Cancelling before acceptance returns your
              credits.
            </span>
          </li>
        </ol>
      </section>
    </aside>
  )
}
