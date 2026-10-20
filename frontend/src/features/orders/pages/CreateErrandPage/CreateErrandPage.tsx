import { useRef, useState } from 'react'
import { Link, useNavigate } from '@tanstack/react-router'
import { useUserProfile } from '#/features/auth/hooks/useUserProfile'
import { ErrandForm } from '../../components/ErrandForm/ErrandForm'
import { ErrandReview } from '../../components/ErrandReview/ErrandReview'
import { OrderPreviewNotice } from '../../components/OrderFeedback/OrderFeedback'
import { useOrderActions } from '../../hooks/useOrderActions'
import type { CreateErrandValues } from '../../schemas/createErrand.schema'
import styles from '../../styles/order.module.scss'

export const CreateErrandPage = () => {
  const [values, setValues] = useState<CreateErrandValues>({
    pickup: null,
    delivery: '',
    description: '',
    notes: '',
    reward: 5,
    expiryMinutes: 60,
  })
  const [review, setReview] = useState(false)
  const { data: user } = useUserProfile()
  const { create } = useOrderActions(user)
  const navigate = useNavigate()
  const lock = useRef(false)
  const submit = async () => {
    if (lock.current) return
    lock.current = true
    try {
      const order = await create.mutateAsync(values)
      await navigate({ to: '/errands/$orderId', params: { orderId: order.id } })
    } catch {
      lock.current = false
    }
  }
  return (
    <div className={`${styles.page} ${styles.narrow}`}>
      <Link to="/home" search={{ mode: 'requestor' }} className={styles.back}>
        ← Back to home
      </Link>
      <h1>{review ? 'Review request' : 'Create an Errand'}</h1>
      <OrderPreviewNotice />
      {review ? (
        <ErrandReview
          values={values}
          pending={create.isPending || create.isSuccess}
          error={create.error}
          onEdit={() => {
            create.reset()
            setReview(false)
          }}
          onSubmit={() => {
            void submit()
          }}
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
  )
}
