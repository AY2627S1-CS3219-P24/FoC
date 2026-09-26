import { useState } from 'react'
import { categoryLabels, isOpenAt } from '../../utils/supplierFormat'
import type { SupplierFormValues } from '../../schemas/supplier.schema'
import styles from './SupplierPreviewCard.module.scss'

type SupplierPreviewCardProps = {
  values: Partial<SupplierFormValues>
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
    openingTime = '',
    closingTime = '',
    closesAfterMidnight = false,
    latitude = '',
    longitude = '',
    imageUrl = '',
  } = values
  const [failedImage, setFailedImage] = useState<string | null>(null)

  const showImage = isHttpUrl(imageUrl) && failedImage !== imageUrl
  const hasHours =
    isTime(openingTime) && isTime(closingTime) && openingTime !== closingTime
  const open = hasHours && isOpenAt(openingTime, closingTime, new Date())
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
                {openingTime} – {closingTime}
                {closesAfterMidnight && (
                  <span className={styles.subtle}>Closes the next day</span>
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
