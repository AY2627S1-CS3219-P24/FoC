import { usePickupLocations } from '../../hooks/usePickupLocations'
import styles from './PickupLocationPicker.module.scss'

export type PickupSelection = { id: string; name: string; address: string }
export const PickupLocationPicker = ({
  value,
  onChange,
  error,
  sampleLocations,
  disabled,
}: {
  value: PickupSelection | null
  onChange: (value: PickupSelection | null) => void
  error?: string
  sampleLocations?: Array<PickupSelection>
  disabled?: boolean
}) => {
  const query = usePickupLocations(!sampleLocations)
  const locations = sampleLocations ?? query.data ?? []
  return (
    <div className={styles.field}>
      <label htmlFor="pickup">Pickup location *</label>
      <select
        id="pickup"
        value={value?.id ?? ''}
        onChange={(event) =>
          onChange(
            locations.find((location) => location.id === event.target.value) ??
              null,
          )
        }
        disabled={disabled || (!sampleLocations && query.isPending)}
        aria-invalid={!!error}
        aria-describedby="pickup-help"
      >
        <option value="">Choose a pickup location</option>
        {locations.map((location) => (
          <option key={location.id} value={location.id}>
            {location.name} — {location.address}
          </option>
        ))}
      </select>
      <small id="pickup-help">
        {error ??
          (sampleLocations
            ? 'Sample locations for the order demo.'
            : query.isPending
              ? 'Loading locations…'
              : (value?.address ?? 'Choose an active campus pickup location.'))}
      </small>
      {query.error && (
        <div role="alert">
          Unable to load pickup locations.{' '}
          <button
            type="button"
            onClick={() => {
              void query.refetch()
            }}
          >
            Retry
          </button>
        </div>
      )}
      {!sampleLocations && query.isSuccess && !locations.length && (
        <p>No pickup locations are available.</p>
      )}
    </div>
  )
}
