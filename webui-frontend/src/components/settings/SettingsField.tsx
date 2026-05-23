import { settingsFieldDescriptions } from '../../settings/settingsFieldDescriptions'
import type { SettingsFieldDefinition } from '../../settings/settingsSchema'

type SettingsFieldProps = {
  field: SettingsFieldDefinition
  value: string | boolean
  onChange: (key: string, value: string | boolean) => void
}

const baseInputClass = 'mt-2 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm text-slate-950'

/**
 * 单个设置字段按元数据渲染控件，写入专用字段始终由父级提供空值。
 */
export function SettingsField({field, value, onChange}: SettingsFieldProps) {
  const id = `settings-field-${field.key.replace(/[^a-zA-Z0-9_-]/g, '-')}`
  const description = settingsFieldDescriptions[field.key]
  const descriptionId = description ? `${id}-description` : undefined
  const numberAttributes = {
    min: field.min,
    max: field.max,
    step: field.step,
  }

  if (field.type === 'boolean') {
    return (
      <div className="min-w-0 rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
        <div className="flex min-w-0 items-center justify-between gap-4">
          <label htmlFor={id} className="min-w-0">
            <span className="block text-sm font-medium text-slate-800">{field.label}</span>
          </label>
          <input
            id={id}
            aria-label={field.label}
            aria-describedby={descriptionId}
            type="checkbox"
            checked={value === true}
            onChange={(event) => onChange(field.key, event.target.checked)}
            className="h-5 w-5 rounded border-slate-300"
          />
        </div>
        {description ? <p id={descriptionId} className="mt-2 break-words text-xs leading-5 text-slate-500">{description}</p> : null}
      </div>
    )
  }

  return (
    <div className="min-w-0 rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
      <label htmlFor={id} className="text-sm font-medium text-slate-800">{field.label}</label>
      {field.type === 'select' ? (
        <select
          id={id}
          aria-label={field.label}
          aria-describedby={descriptionId}
          value={String(value)}
          onChange={(event) => onChange(field.key, event.target.value)}
          className={baseInputClass}
        >
          {(field.options || []).map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
        </select>
      ) : field.type === 'textarea' ? (
        <textarea
          id={id}
          aria-label={field.label}
          aria-describedby={descriptionId}
          value={String(value)}
          onChange={(event) => onChange(field.key, event.target.value)}
          className={`${baseInputClass} min-h-28 resize-y`}
          placeholder={field.placeholder || (field.writeOnly ? '留空表示不修改现有值' : undefined)}
        />
      ) : (
        <input
          id={id}
          aria-label={field.label}
          aria-describedby={descriptionId}
          type={field.type === 'password' ? 'password' : field.type === 'number' ? 'number' : 'text'}
          value={String(value)}
          onChange={(event) => onChange(field.key, event.target.value)}
          className={baseInputClass}
          placeholder={field.placeholder || (field.writeOnly ? '留空表示不修改现有值' : undefined)}
          {...(field.type === 'number' ? numberAttributes : {})}
        />
      )}
      {description ? <p id={descriptionId} className="mt-2 break-words text-xs leading-5 text-slate-500">{description}</p> : null}
    </div>
  )
}
