import {
  Children,
  cloneElement,
  forwardRef,
  isValidElement,
  useEffect,
  useImperativeHandle,
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type FocusEvent,
  type FormEvent,
  type FormHTMLAttributes,
  type ReactNode,
} from "react";
import "../../styles/form-validation.css";
import { FIELD_VALIDATION_EVENT, withValidationForm } from "../../lib/formValidation";
import type { ApiViolation } from "../../types/auth";

type NativeField = HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement;
type ValidationErrors = Record<string, string>;

type ValidatedFormProps = Omit<FormHTMLAttributes<HTMLFormElement>, "onSubmit"> & {
  onSubmit?: FormHTMLAttributes<HTMLFormElement>["onSubmit"];
};

function fieldKey(field: NativeField): string {
  return field.dataset.validationKey
    || field.name
    || field.id
    || field.getAttribute("aria-label")
    || (field instanceof HTMLSelectElement ? "" : field.placeholder)
    || "field";
}

function fieldLabel(field: NativeField): string {
  const suppliedName = field.getAttribute("aria-label")
    || field.name
    || (field instanceof HTMLSelectElement ? "" : field.placeholder)
    || field.labels?.[0]?.textContent
    || "this field";
  const readableName = suppliedName
    .replace(/([a-z])([A-Z])/g, "$1 $2")
    .replace(/[_-]+/g, " ")
    .replace(/\s+/g, " ")
    .trim()
    .toLowerCase();

  if (readableName === "email") return "email address";
  if (readableName === "confirm password") return "password confirmation";
  return readableName;
}

function validationMessage(field: NativeField): string | null {
  if (!field.willValidate || field.validity.valid) return null;

  const label = fieldLabel(field);
  if (field.validity.valueMissing) {
    if (field instanceof HTMLInputElement && field.type === "checkbox") {
      return field.dataset.requiredMessage || `Select ${label}.`;
    }
    return field.dataset.requiredMessage || `Please enter your ${label}.`;
  }
  if (field.validity.typeMismatch && field instanceof HTMLInputElement && field.type === "email") {
    return field.dataset.typeMessage || "Enter a valid email address.";
  }
  if (field.validity.patternMismatch) {
    return field.dataset.patternMessage || `Enter a valid ${label}.`;
  }
  if (field.validity.tooShort) {
    return field.dataset.minLengthMessage
      || `Enter at least ${field instanceof HTMLSelectElement ? 0 : field.minLength} characters for ${label}.`;
  }
  if (field.validity.tooLong) {
    return field.dataset.maxLengthMessage
      || `Use no more than ${field instanceof HTMLSelectElement ? 0 : field.maxLength} characters for ${label}.`;
  }
  if (field.validity.rangeUnderflow || field.validity.rangeOverflow) {
    return field.dataset.rangeMessage || `Enter a valid value for ${label}.`;
  }
  if (field.validity.customError) return field.validationMessage;
  return `Check the value for ${label}.`;
}

function normalizedFieldName(value: string): string {
  return value.replace(/[^a-z0-9]/gi, "").toLowerCase();
}

function isNativeField(value: unknown): value is React.ReactElement<{
  name?: string;
  id?: string;
  placeholder?: string;
  "aria-label"?: string;
  "aria-describedby"?: string;
  "aria-invalid"?: boolean | "true" | "false";
  "data-validation-key"?: string;
}> {
  return isValidElement(value)
    && typeof value.type === "string"
    && ["input", "select", "textarea"].includes(value.type);
}

function cloneFields(
  children: ReactNode,
  errors: ValidationErrors,
  errorPrefix: string,
  index: { value: number },
): ReactNode {
  return Children.map(children, (child) => {
    if (!isValidElement<{ children?: ReactNode }>(child)) return child;

    if (isNativeField(child)) {
      const props = child.props;
      const position = index.value++;
      const key = props.name || props.id || props["aria-label"] || props.placeholder || `field-${position}`;
      const error = errors[key];
      const errorId = `${errorPrefix}-${position}`;
      const describedBy = props["aria-describedby"]
        ?.split(/\s+/)
        .filter((value) => value && value !== errorId) ?? [];
      if (error) describedBy.push(errorId);

      return <>
        {cloneElement(child, {
          "data-validation-key": key,
          "aria-invalid": error ? true : props["aria-invalid"],
          "aria-describedby": describedBy.length ? [...new Set(describedBy)].join(" ") : undefined,
        })}
        {error && <small className="field-validation-error" id={errorId} role="status" aria-live="polite">
          {error}
        </small>}
      </>;
    }

    if ("children" in child.props && child.props.children) {
      return cloneElement(child, undefined, cloneFields(child.props.children, errors, errorPrefix, index));
    }
    return child;
  });
}

export const ValidatedForm = forwardRef<HTMLFormElement, ValidatedFormProps>(function ValidatedForm({
  children,
  onSubmit,
  onReset,
  onBlurCapture,
  onChangeCapture,
  ...props
}, forwardedRef) {
  const [errors, setErrors] = useState<ValidationErrors>({});
  const [serverErrors, setServerErrors] = useState<ValidationErrors>({});
  const [touched, setTouched] = useState<Set<string>>(() => new Set());
  const [submitted, setSubmitted] = useState(false);
  const formRef = useRef<HTMLFormElement>(null);
  const pendingFocus = useRef<string | null>(null);
  const formId = useId().replace(/:/g, "");
  const visibleErrors = { ...serverErrors, ...errors };

  useImperativeHandle(forwardedRef, () => formRef.current!, []);

  useEffect(() => {
    const form = formRef.current;
    if (!form) return;
    const validationForm = form;

    function handleFieldViolations(event: Event) {
      const violations = (event as CustomEvent<ApiViolation[]>).detail;
      if (!Array.isArray(violations)) return;
      const fields = Array.from(validationForm.elements)
        .filter((element): element is NativeField => element instanceof HTMLInputElement
          || element instanceof HTMLSelectElement || element instanceof HTMLTextAreaElement);
      const nextErrors: ValidationErrors = {};
      let firstInvalidKey: string | null = null;
      let hasUnmappedViolation = false;
      for (const violation of violations) {
        const candidate = normalizedFieldName(violation.field.split(".").at(-1) ?? "");
        if (!candidate || candidate === "request") {
          hasUnmappedViolation = true;
          continue;
        }
        const field = fields.find((item) => [
          item.name,
          item.id,
          item.dataset.validationKey ?? "",
        ].some((name) => normalizedFieldName(name) === candidate));
        if (!field) {
          hasUnmappedViolation = true;
          continue;
        }
        const key = fieldKey(field);
        nextErrors[key] = "Please check this field.";
        firstInvalidKey ??= key;
      }
      if (!firstInvalidKey) return;
      setServerErrors((previous) => ({ ...previous, ...nextErrors }));
      pendingFocus.current = firstInvalidKey;
      if (!hasUnmappedViolation) event.preventDefault();
    }

    validationForm.addEventListener(FIELD_VALIDATION_EVENT, handleFieldViolations);
    return () => validationForm.removeEventListener(FIELD_VALIDATION_EVENT, handleFieldViolations);
  }, []);

  useLayoutEffect(() => {
    const key = pendingFocus.current;
    if (!key) return;
    pendingFocus.current = null;
    const firstInvalid = Array.from(formRef.current?.elements ?? [])
      .find((element) => element instanceof HTMLElement
        && element.dataset.validationKey === key);
    if (firstInvalid instanceof HTMLElement) {
      firstInvalid.focus();
      firstInvalid.scrollIntoView({ block: "center", behavior: "smooth" });
    }
  }, [errors, serverErrors]);

  function setFieldError(field: NativeField) {
    const key = fieldKey(field);
    const message = validationMessage(field);
    setErrors((previous) => {
      if (!message && !(key in previous)) return previous;
      if (message && previous[key] === message) return previous;
      const next = { ...previous };
      if (message) next[key] = message;
      else delete next[key];
      return next;
    });
  }

  function handleBlurCapture(event: FocusEvent<HTMLFormElement>) {
    const field = event.target;
    if (!(field instanceof HTMLInputElement || field instanceof HTMLSelectElement
      || field instanceof HTMLTextAreaElement)) return;
    const key = fieldKey(field);
    setTouched((previous) => new Set(previous).add(key));
    setFieldError(field);
  }

  function handleChangeCapture(event: FormEvent<HTMLFormElement>) {
    const field = event.target;
    if (!(field instanceof HTMLInputElement || field instanceof HTMLSelectElement
      || field instanceof HTMLTextAreaElement)) return;
    const key = fieldKey(field);
    if (touched.has(key) || submitted) setFieldError(field);
    setServerErrors((previous) => {
      if (!(key in previous)) return previous;
      const next = { ...previous };
      delete next[key];
      return next;
    });
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    const form = event.currentTarget;
    const invalidFields = Array.from(form.elements)
      .filter((element): element is NativeField => element instanceof HTMLInputElement
        || element instanceof HTMLSelectElement || element instanceof HTMLTextAreaElement)
      .map((field) => ({ field, message: validationMessage(field) }))
      .filter((result): result is { field: NativeField; message: string } => Boolean(result.message));

    if (invalidFields.length) {
      event.preventDefault();
      setSubmitted(true);
      const nextErrors: ValidationErrors = {};
      invalidFields.forEach(({ field, message }) => {
        const key = fieldKey(field);
        nextErrors[key] = message;
      });
      setErrors(nextErrors);
      setTouched((previous) => {
        const next = new Set(previous);
        invalidFields.forEach(({ field }) => next.add(fieldKey(field)));
        return next;
      });
      pendingFocus.current = fieldKey(invalidFields[0].field);
      return;
    }

    setSubmitted(true);
    setServerErrors({});
    withValidationForm(form, () => onSubmit?.(event));
  }

  function handleReset(event: FormEvent<HTMLFormElement>) {
    onReset?.(event);
    if (event.defaultPrevented) return;
    setErrors({});
    setServerErrors({});
    setTouched(new Set());
    setSubmitted(false);
  }

  return <form
    {...props}
    ref={formRef}
    noValidate
    onBlurCapture={(event) => {
      onBlurCapture?.(event);
      handleBlurCapture(event);
    }}
    onChangeCapture={(event) => {
      onChangeCapture?.(event);
      handleChangeCapture(event);
    }}
    onSubmit={handleSubmit}
    onReset={handleReset}
  >
    {cloneFields(children, visibleErrors, `validation-${formId}`, { value: 0 })}
  </form>;
});
