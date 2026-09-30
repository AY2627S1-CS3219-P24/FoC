import { useState } from 'react'
import { categoryLabels } from '../../utils/supplierFormat'
import {
  closesAfterMidnight,
  formatWeeklyHours,
  isOpenAt,
} from '../../utils/openingHours'
import { toOpeningHours } from '../../schemas/supplier.schema'
import type { DeepPartial } from 'react-hook-form'
import type { SupplierFormValues } from '../../schemas/supplier.schema'
import styles from './SupplierPreviewCard.module.scss'

type SupplierPreviewCardProps = {
  /** Live form, field may be empty while the admin types */
  values: DeepPartial<SupplierFormValues>
}

const isHttpUrl = (value: string) => /^https?:\/\/\S+$/.test(value)
const isTime = (value: string) => /^\d{2}:\d{2}$/.test(value)
const isCoordinate = (value: string, limit: number) =>
  value.trim() !== '' &&
  Number.isFinite(Number(value)) &&
  Math.abs(Number(value)) <= limit

/** Provide admin a live preview for the card display to student users */
export const SupplierPreviewCard = ({ values }: SupplierPreviewCardProps) => {
  const {
    name = '',
    category,
    building = '',
    floor = '',
    locationDescription = '',
    hours = [],
    latitude = '',
    longitude = '',
    imageUrl = '',
  } = values
  const [failedImage, setFailedImage] = useState<string | null>(null)

  const showImage = isHttpUrl(imageUrl) && failedImage !== imageUrl
  const openingHours = toOpeningHours(
    hours.filter(
      (day): day is SupplierFormValues['hours'][number] =>
        day !== undefined &&
        day.dayOfWeek !== undefined &&
        day.open === true &&
        isTime(day.opensAt ?? '') &&
        isTime(day.closesAt ?? '') &&
        day.opensAt !== day.closesAt,
    ),
  )
  const hasHours = openingHours.length > 0
  const open = hasHours && isOpenAt(openingHours, new Date())
  const overnight = openingHours.some(closesAfterMidnight)
  const hasCoordinates =
    isCoordinate(latitude, 90) && isCoordinate(longitude, 180)
  const location = [building.trim(), floor.trim() && `Level ${floor.trim()}`]
    .filter(Boolean)
    .join(', ')

  return (
    <article className={styles.card}>
      <div className={styles.media} data-category={category}>
        {showImage ? (
          <img
            src={imageUrl}
            alt=""
            className={styles.image}
            onError={() => setFailedImage(imageUrl)}
          />
        ) : (
          <span className={styles.initial} aria-hidden="true">
            {name.trim().charAt(0).toUpperCase() || '?'}
          </span>
        )}
        {hasHours && (
          <span className={styles.status} data-open={open || undefined}>
            {open ? 'Open now' : 'Closed now'}
          </span>
        )}
      </div>

      <div className={styles.body}>
        <div className={styles.titleRow}>
          <h3 className={styles.name} data-empty={!name.trim() || undefined}>
            {name.trim() || 'Supplier name'}
          </h3>
          {category && (
            <span className={styles.chip}>{categoryLabels[category]}</span>
          )}
        </div>

        <dl className={styles.details}>
          <dt>Location</dt>
          <dd>
            {location || <span className={styles.missing}>Not set</span>}
            {locationDescription.trim() && (
              <span className={styles.subtle}>
                {locationDescription.trim()}
              </span>
            )}
          </dd>

          <dt>Hours</dt>
          <dd>
            {hasHours ? (
              <>
                {formatWeeklyHours(openingHours).map((line) => (
                  <span key={line}>{line}</span>
                ))}
                {overnight && (
                  <span className={styles.overnight}>
                    Closes after midnight
                  </span>
                )}
              </>
            ) : (
              <span className={styles.missing}>Not set</span>
            )}
          </dd>

          {hasCoordinates && (
            <>
              <dt>Map</dt>
              <dd>
                <a
                  className={styles.link}
                  href={`https://www.google.com/maps?q=${Number(latitude)},${Number(longitude)}`}
                  target="_blank"
                  rel="noreferrer"
                >
                  View on Google Maps
                </a>
              </dd>
            </>
          )}
        </dl>
      </div>
    </article>
  )
}
