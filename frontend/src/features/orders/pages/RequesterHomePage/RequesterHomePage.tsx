import { useState } from 'react'
import { Button } from '@base-ui/react/button'
import { Dialog } from '@base-ui/react/dialog'
import {
  categories,
  favouriteLocations,
  recentErrands,
} from './requesterHome.data'
import styles from './RequesterHomePage.module.scss'
import { RoleSwitcher } from '#/features/orders/components/RoleSwitcher/RoleSwitcher'

export const RequesterHomePage = () => {
  const [preview, setPreview] = useState<{
    title: string
    description: string
  } | null>(null)
  return (
    <div className={styles.page}>
      <RoleSwitcher activeMode="requestor" />
      <h1>What do you need?</h1>
      <ul className={styles.categories} aria-label="Location categories">
        {categories.map((category) => (
          <li key={category}>
            <Button
              className={styles.category}
              onClick={() =>
                setPreview({
                  title: category,
                  description: `Browse ${category.toLowerCase()} here once the location listing is connected.`,
                })
              }
            >
              <span className={styles.tile} aria-hidden="true" />
              <span>{category}</span>
            </Button>
          </li>
        ))}
      </ul>

      <section className={styles.section} aria-labelledby="favourites-heading">
        <h2 id="favourites-heading">Favourite locations</h2>
        <ul className={styles.cards}>
          {favouriteLocations.map((location) => (
            <li key={location.id}>
              <Button
                className={styles.card}
                onClick={() =>
                  setPreview({
                    title: location.name,
                    description: `${location.hours}. This is a sample location; live hours and location details are not connected yet.`,
                  })
                }
              >
                <span>
                  <span className={styles.cardTitle}>{location.name}</span>{' '}
                  <span className={styles.status}>{location.hours}</span>
                </span>
                <span className={styles.arrow} aria-hidden="true">
                  ›
                </span>
              </Button>
            </li>
          ))}
        </ul>
      </section>

      <section className={styles.section} aria-labelledby="recent-heading">
        <h2 id="recent-heading">Recent errands</h2>
        <ul className={styles.cards}>
          {recentErrands.map((errand) => (
            <li key={errand.id}>
              <Button
                className={styles.card}
                onClick={() =>
                  setPreview({
                    title: `${errand.pickup} → ${errand.destination}`,
                    description: `${errand.label}: ${errand.description} This is a sample errand.`,
                  })
                }
              >
                <span>
                  <span className={styles.cardTitle}>
                    {errand.pickup} → {errand.destination}
                  </span>{' '}
                  <span className={styles.status}>{errand.label}</span>
                </span>
                <span className={styles.arrow} aria-hidden="true">
                  ›
                </span>
              </Button>
            </li>
          ))}
        </ul>
      </section>

      <Button
        className={styles.create}
        onClick={() =>
          setPreview({
            title: 'Create Errand',
            description:
              'The errand creation form is not connected yet. No errand has been created.',
          })
        }
      >
        Create Errand
      </Button>
      <p className={styles.sample}>Sample dashboard content</p>

      <Dialog.Root
        open={preview !== null}
        onOpenChange={(open) => {
          if (!open) setPreview(null)
        }}
      >
        <Dialog.Portal>
          <Dialog.Backdrop className={styles.backdrop} />
          <Dialog.Popup className={styles.dialog}>
            <Dialog.Title>{preview?.title}</Dialog.Title>
            <Dialog.Description>{preview?.description}</Dialog.Description>
            <Dialog.Close className={styles.create}>Close</Dialog.Close>
          </Dialog.Popup>
        </Dialog.Portal>
      </Dialog.Root>
    </div>
  )
}
