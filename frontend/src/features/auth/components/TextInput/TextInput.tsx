import { Input } from '@base-ui/react/input'
import './TextInput.scss'

/**
 * Shared input styling with Base UI behavior and props.
 */
export const TextInput = ({ className, ...props }: Input.Props) => {
  return (
    <Input
      {...props}
      className={(state) => {
        const additionalClassName =
          typeof className === 'function' ? className(state) : className

        return additionalClassName
          ? `text-input ${additionalClassName}`
          : 'text-input'
      }}
    />
  )
}
