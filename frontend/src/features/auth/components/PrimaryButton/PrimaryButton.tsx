import { Button } from '@base-ui/react/button'
import './PrimaryButton.scss'

/**
 * Shared primary button styling with Base UI behavior and props.
 */
export const PrimaryButton = ({
  className,
  type = 'button',
  ...props
}: Button.Props) => {
  return (
    <Button
      {...props}
      type={type}
      className={(state) => {
        const additionalClassName =
          typeof className === 'function' ? className(state) : className

        return additionalClassName
          ? `primary-button ${additionalClassName}`
          : 'primary-button'
      }}
    />
  )
}
