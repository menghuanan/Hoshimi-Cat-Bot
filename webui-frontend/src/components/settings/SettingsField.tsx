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
        <label data-toggle-shell className="flex min-w-0 items-center justify-between gap-4">
          <span className="block min-w-0 text-sm font-medium text-slate-800">{field.label}</span>
          <input
            id={id}
            aria-label={field.label}
            aria-describedby={descriptionId}
            type="checkbox"
            checked={value === true}
            onChange={(event) => onChange(field.key, event.target.checked)}
            className="toggle-input"
          />
          <span className="toggle-track" aria-hidden="true">
            <span className="toggle-thumb" />
          </span>
        </label>
        {description ? <FieldHelper id={descriptionId} fieldKey={field.key} description={description} /> : null}
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
      {description ? <FieldHelper id={descriptionId} fieldKey={field.key} description={description} /> : null}
    </div>
  )
}

/**
 * 字段说明按语义拆成普通辅助和警告提示，避免高风险说明混在灰色小字里。
 */
function FieldHelper({id, fieldKey, description}: {id: string | undefined, fieldKey: string, description: string}) {
  const warning = isWarningDescription(fieldKey)
  if (warning) {
    return (
      <p id={id} data-field-helper-tone="warning" className="settings-warning-helper">
        <span className="settings-helper-icon" aria-hidden="true">!</span>
        <span>{description}</span>
      </p>
    )
  }
  return <p id={id} data-field-helper-tone="muted" className="settings-muted-helper">{description}</p>
}

/**
 * 当前只有平台类型说明具备明确“不推荐”语义，先按 key 白名单升级为警示条。
 */
function isWarningDescription(fieldKey: string): boolean {
  return fieldKey === 'platform.type'
}
