import { useForm, Controller } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { Button } from '@base-ui/react/button'
import { PickupLocationPicker } from '#/features/suppliers/components/PickupLocationPicker/PickupLocationPicker'
import {
  createErrandSchema,
  EXPIRY_MINUTES,
} from '../../schemas/createErrand.schema'
import { expiryLabel } from '../../utils/orderFormat'
import type { CreateErrandValues } from '../../schemas/createErrand.schema'
import common from '../../styles/order.module.scss'
import styles from './ErrandForm.module.scss'

export const ErrandForm = ({
  values,
  onReview,
}: {
  values: CreateErrandValues
  onReview: (values: CreateErrandValues) => void
}) => {
  const {
    register,
    control,
    handleSubmit,
    formState: { errors },
  } = useForm<CreateErrandValues>({
    defaultValues: values,
    resolver: zodResolver(createErrandSchema),
  })
  return (
    <form
      className={`${common.panel} ${styles.form}`}
      noValidate
      onSubmit={handleSubmit(onReview)}
    >
      <Controller
        control={control}
        name="pickup"
        render={({ field }) => (
          <PickupLocationPicker
            value={field.value}
            onChange={field.onChange}
            error={errors.pickup?.message}
          />
        )}
      />
      <div className={styles.field}>
        <label htmlFor="delivery">Delivery location *</label>
        <input
          id="delivery"
          className={common.input}
          placeholder="Building, floor and meeting point"
          {...register('deliveryLocation')}
          aria-invalid={!!errors.deliveryLocation}
          aria-describedby={
            errors.deliveryLocation ? 'delivery-error' : undefined
          }
        />
        {errors.deliveryLocation && (
          <small id="delivery-error" className={common.error}>
            {errors.deliveryLocation.message}
          </small>
        )}
      </div>
      <div className={styles.field}>
        <label htmlFor="description">What should be collected? *</label>
        <textarea
          id="description"
          rows={3}
          className={common.input}
          {...register('description')}
          aria-invalid={!!errors.description}
          aria-describedby={
            errors.description ? 'description-error' : undefined
          }
        />
        {errors.description && (
          <small id="description-error" className={common.error}>
            {errors.description.message}
          </small>
        )}
      </div>
      <div className={styles.field}>
        <label htmlFor="notes">Notes (optional)</label>
        <textarea
          id="notes"
          rows={3}
          className={common.input}
          {...register('notes')}
          aria-invalid={!!errors.notes}
          aria-describedby={errors.notes ? 'notes-error' : undefined}
        />
        {errors.notes && (
          <small id="notes-error" className={common.error}>
            {errors.notes.message}
          </small>
        )}
      </div>
      <div className={styles.columns}>
        <div className={styles.field}>
          <label htmlFor="reward">Courier reward *</label>
          <input
            id="reward"
            type="number"
            min={1}
            step={1}
            className={common.input}
            {...register('creditReward', { valueAsNumber: true })}
            aria-invalid={!!errors.creditReward}
            aria-describedby="reward-help"
          />
          <small
            id="reward-help"
            className={errors.creditReward ? common.error : common.muted}
          >
            {errors.creditReward?.message ??
              'Enter a positive whole number of credits.'}
          </small>
        </div>
        <div className={styles.field}>
          <label htmlFor="expiry">Expiry duration *</label>
          <select
            id="expiry"
            className={common.select}
            {...register('expiryMinutes', { valueAsNumber: true })}
          >
            {EXPIRY_MINUTES.map((minutes) => (
              <option key={minutes} value={minutes}>
                {expiryLabel(minutes)}
              </option>
            ))}
          </select>
          <small className={common.muted}>
            Unaccepted requests expire after this duration.
          </small>
        </div>
      </div>
      <div className={common.actions}>
        <Button type="submit" className={common.button}>
          Continue
        </Button>
      </div>
    </form>
  )
}
