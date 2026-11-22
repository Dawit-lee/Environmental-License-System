import { useId, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes, type TextareaHTMLAttributes } from 'react';
import { cn, focusRing } from './cn';

// DESIGN.md 입력: label above (15px 700), "(필수)" in words, hint below, error replaces the hint.
interface FieldProps {
  label: ReactNode;
  hint?: ReactNode;
  error?: ReactNode;
}

const control = (error: unknown) => cn(
  'w-full rounded border bg-white px-4 text-base text-ink placeholder:text-disabled',
  error ? 'border-danger' : 'border-input', 'focus:border-brand', focusRing,
  'disabled:cursor-not-allowed disabled:bg-line disabled:text-disabled',
);

/** Wraps one control: works out its id and aria wiring, then renders label → control → hint or error. */
function Field({ label, hint, error, id, required, className, children }: FieldProps & {
  id?: string; required?: boolean; className?: string;
  children: (aria: { id: string; 'aria-invalid'?: true; 'aria-describedby'?: string }) => ReactNode;
}) {
  const auto = useId();
  const cid = id ?? auto;
  const noteId = `${cid}-note`;
  const note = error ?? hint;
  return (
    <div className={cn('flex flex-col gap-2', className)}>
      <label htmlFor={cid} className="text-[15px] font-bold text-ink">
        {label}{required && <span className="ml-1 font-normal text-body">(필수)</span>}
      </label>
      {children({ id: cid, 'aria-invalid': error ? true : undefined, 'aria-describedby': note ? noteId : undefined })}
      {error ? (
        <p id={noteId} className="flex items-start gap-1 text-[15px] text-danger">
          <span aria-hidden className="font-bold">!</span>{error}
        </p>
      ) : hint ? (
        <p id={noteId} className="text-[15px] text-body">{hint}</p>
      ) : null}
    </div>
  );
}

type Own = 'className' | 'id';

export function Input({ label, hint, error, className, id, required, ...rest }: FieldProps & InputHTMLAttributes<HTMLInputElement>) {
  return (
    <Field {...{ label, hint, error, className, id, required }}>
      {(aria) => <input {...rest} {...aria} required={required} className={cn('h-12', control(error))} />}
    </Field>
  );
}

export function Select({ label, hint, error, className, id, required, children, ...rest }: FieldProps & Omit<SelectHTMLAttributes<HTMLSelectElement>, Own> & { className?: string; id?: string }) {
  return (
    <Field {...{ label, hint, error, className, id, required }}>
      {(aria) => <select {...rest} {...aria} required={required} className={cn('h-12', control(error))}>{children}</select>}
    </Field>
  );
}

export function Textarea({ label, hint, error, className, id, required, ...rest }: FieldProps & TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return (
    <Field {...{ label, hint, error, className, id, required }}>
      {(aria) => <textarea rows={4} {...rest} {...aria} required={required} className={cn('py-3 leading-normal', control(error))} />}
    </Field>
  );
}
