import { Controller, useForm, useWatch } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { Button } from '@base-ui/react/button'
import { Checkbox } from '@base-ui/react/checkbox'
import { Input } from '@base-ui/react/input'
import { Select } from '@base-ui/react/select'
import { supplierSchema } from '../../schemas/supplier.schema'
import { SUPPLIER_CATEGORIES } from '../../types/supplier.types'
import { categoryLabels } from '../../utils/supplierFormat'
import { SupplierPreviewCard } from '../SupplierPreviewCard/SupplierPreviewCard'
import type { Control, FieldErrors } from 'react-hook-form'
import type { SupplierFormValues } from '../../schemas/supplier.schema'
import ui from '../../styles/supplier.module.scss'
import styles from './SupplierForm.module.scss'

type SupplierFormProps = {
  defaultValues: SupplierFormValues
  submitLabel: string
  submitting?: boolean
  onSubmit: (values: SupplierFormValues) => void
  onCancel: () => void
}

type TextFieldName = Exclude<
  keyof SupplierFormValues,
  'category' | 'closesAfterMidnight'
>

type TextFieldProps = {
  name: TextFieldName
  label: string
  control: Control<SupplierFormValues>
  errors: FieldErrors<SupplierFormValues>
  required?: boolean
  type?: 'text' | 'time' | 'url'
  placeholder?: string
  hint?: string
  /** check both timing when one of it changes, see if still valid */
  deps?: Array<TextFieldName>
}

const TextField = ({
  name,
  label,
  control,
  errors,
  required = false,
  type = 'text',
  placeholder,
  hint,
  deps,
}: TextFieldProps) => {
  const id = `supplier-${name}`
  const error = errors[name]?.message
  const describedBy =
    [error && `${id}-error`, hint && `${id}-hint`].filter(Boolean).join(' ') ||
    undefined

  return (
    <div className={styles.field}>
      <label htmlFor={id} className={styles.label}>
        {label}
        {required && <span className={styles.required}> *</span>}
      </label>
      <Controller
        name={name}
        control={control}
        rules={{ deps }}
        render={({ field: { onChange, ...field } }) => (
          <Input
            {...field}
            id={id}
            type={type}
            placeholder={placeholder}
            className={ui.input}
            onValueChange={onChange}
            aria-invalid={Boolean(error)}
            aria-describedby={describedBy} //
          />
        )}
      />
      {hint && (
        <p id={`${id}-hint`} className={styles.hint}>
          {hint}
        </p>
      )}
      {error && (
        <p id={`${id}-error`} className={styles.error} role="alert">
          {error}
        </p>
      )}
    </div>
  )
}

const categoryItems = SUPPLIER_CATEGORIES.map((value) => ({
  value,
  label: categoryLabels[value],
}))

export const SupplierForm = ({
  defaultValues,
  submitLabel,
  submitting = false,
  onSubmit,
  onCancel,
}: SupplierFormProps) => {
  const {
    control,
    handleSubmit,
    formState: { errors },
  } = useForm<SupplierFormValues>({
    defaultValues,
    resolver: zodResolver(supplierSchema),
  })
  const fieldProps = { control, errors }
  const values = useWatch({ control })

  return (
    <div className={styles.layout}>
      <form
        className={`${ui.card} ${styles.form}`}
        noValidate
        onSubmit={handleSubmit(onSubmit)}
      >
        <section className={styles.section}>
          <h2 className={styles.sectionTitle}>Basic information</h2>
          <TextField
            {...fieldProps}
            name="name"
            label="Name"
            placeholder="eg. CoffeeBean @ COM3"
            required
          />

          <div className={styles.field}>
            <Controller
              name="category"
              control={control}
              render={({ field }) => (
                <Select.Root
                  items={categoryItems}
                  value={field.value}
                  onValueChange={(value) => value && field.onChange(value)}
                >
                  <Select.Label className={styles.label}>
                    Category<span className={styles.required}> *</span>
                  </Select.Label>
                  <Select.Trigger
                    className={`${ui.input} ${styles.selectTrigger}`}
                  >
                    <Select.Value />
                    <Select.Icon className={styles.selectIcon}>▾</Select.Icon>
                  </Select.Trigger>
                  <Select.Portal>
                    <Select.Positioner
                      sideOffset={4}
                      className={styles.positioner}
                    >
                      <Select.Popup className={styles.selectPopup}>
                        <Select.List>
                          {categoryItems.map(({ value, label }) => (
                            <Select.Item
                              key={value}
                              value={value}
                              className={styles.selectItem}
                            >
                              <Select.ItemText>{label}</Select.ItemText>
                              <Select.ItemIndicator>✓</Select.ItemIndicator>
                            </Select.Item>
                          ))}
                        </Select.List>
                      </Select.Popup>
                    </Select.Positioner>
                  </Select.Portal>
                </Select.Root>
              )}
            />
          </div>
        </section>

        <section className={styles.section}>
          <h2 className={styles.sectionTitle}>Location</h2>
          <div className={styles.row}>
            <TextField
              {...fieldProps}
              name="building"
              label="Building"
              placeholder="eg. COM3"
              required
            />
            <TextField
              {...fieldProps}
              name="floor"
              label="Floor"
              placeholder="eg. 1"
            />
          </div>

          <TextField
            {...fieldProps}
            name="locationDescription"
            label="Location description"
            placeholder="eg. Next to LT19"
          />

          <div className={styles.row}>
            <TextField
              {...fieldProps}
              name="latitude"
              label="Latitude"
              placeholder="eg. 1.2948"
            />
            <TextField
              {...fieldProps}
              name="longitude"
              label="Longitude"
              placeholder="eg. 103.7736"
            />
          </div>
        </section>

        <section className={styles.section}>
          <h2 className={styles.sectionTitle}>Opening hours</h2>
          <div className={styles.row}>
            <TextField
              {...fieldProps}
              name="openingTime"
              label="Opens at"
              type="time"
              deps={['closingTime']}
              required
            />
            <TextField
              {...fieldProps}
              name="closingTime"
              label="Closes at"
              type="time"
              required
            />
          </div>

          <Controller
            name="closesAfterMidnight"
            control={control}
            rules={{ deps: ['closingTime'] }}
            render={({ field }) => (
              <label className={styles.checkboxLabel}>
                <Checkbox.Root
                  className={styles.checkbox}
                  checked={field.value}
                  onCheckedChange={(checked) => field.onChange(checked)}
                  onBlur={field.onBlur}
                  aria-labelledby="supplier-closesAfterMidnight-label"
                >
                  <Checkbox.Indicator className={styles.checkboxIndicator}>
                    ✓
                  </Checkbox.Indicator>
                </Checkbox.Root>
                <span id="supplier-closesAfterMidnight-label">
                  Closes after midnight (eg. 11:00 AM – 2:00 AM)
                </span>
              </label>
            )}
          />
        </section>

        <section className={styles.section}>
          <h2 className={styles.sectionTitle}>Image</h2>
          <TextField
            {...fieldProps}
            name="imageUrl"
            label="Image URL"
            type="url"
            placeholder="https://…"
            hint="Shown on the supplier card. Leave blank to use a placeholder."
          />
        </section>

        <div className={styles.actions}>
          <Button
            type="button"
            className={ui.button}
            onClick={onCancel}
            disabled={submitting}
          >
            Cancel
          </Button>
          <Button
            type="submit"
            className={`${ui.button} ${ui.primary}`}
            disabled={submitting}
          >
            {submitting ? 'Saving…' : submitLabel}
          </Button>
        </div>
      </form>

      <aside className={styles.preview} aria-label="Preview">
        <p className={styles.previewLabel}>Preview</p>
        <SupplierPreviewCard values={values} />
        <p className={styles.previewHint}>
          This is how students will see the supplier.
        </p>
      </aside>
    </div>
  )
}
