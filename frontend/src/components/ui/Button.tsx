import type { ButtonHTMLAttributes } from 'react';
import { cn, focusRing } from './cn';

// DESIGN.md component.button. Only one primary per screen area; danger for irreversible actions.
const VARIANT = {
  primary: 'border border-transparent bg-brand font-bold text-white hover:bg-brand-hover active:bg-brand-pressed',
  secondary: 'border border-brand bg-brand-subtle text-brand-text hover:bg-brand-border',
  tertiary: 'border border-input bg-white text-ink hover:bg-paper',
  danger: 'border border-danger bg-white text-danger hover:bg-danger-bg',
  text: 'border border-transparent bg-transparent text-brand-text hover:underline',
} as const;

// md is the default; sm (40px) only inside tables and toolbars.
const SIZE = { md: 'h-12 px-4 text-[17px]', sm: 'h-10 px-3 text-[15px]' } as const;

export type ButtonVariant = keyof typeof VARIANT;

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  size?: keyof typeof SIZE;
}

export function Button({ variant = 'primary', size = 'md', type = 'button', className, ...rest }: ButtonProps) {
  return (
    <button
      type={type}
      className={cn(
        'inline-flex shrink-0 items-center justify-center gap-2 whitespace-nowrap rounded transition-colors',
        VARIANT[variant], SIZE[size], focusRing,
        'disabled:cursor-not-allowed disabled:border-transparent disabled:bg-line disabled:text-disabled disabled:no-underline',
        className,
      )}
      {...rest}
    />
  );
}
