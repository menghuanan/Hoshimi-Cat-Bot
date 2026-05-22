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
  if (field.type === 'boolean') {
    return (
      <label htmlFor={id} className="flex min-w-0 items-center justify-between gap-4 rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
        <span className="min-w-0">
          <span className="block text-sm font-medium text-slate-800">{field.label}</span>
          {field.restartRequired ? <span className="mt-1 block text-xs text-amber-700">保存后需要重启</span> : null}
        </span>
        <input
          id={id}
          aria-label={field.label}
          type="checkbox"
          checked={value === true}
          onChange={(event) => onChange(field.key, event.target.checked)}
          className="h-5 w-5 rounded border-slate-300"
        />
      </label>
    )
  }

  return (
    <label htmlFor={id} className="block min-w-0 rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
      <span className="text-sm font-medium text-slate-800">{field.label}</span>
      {field.type === 'textarea' ? (
        <textarea
          id={id}
          aria-label={field.label}
          value={String(value)}
          onChange={(event) => onChange(field.key, event.target.value)}
          className={`${baseInputClass} min-h-28 resize-y`}
          placeholder={field.writeOnly ? '留空表示不修改现有值' : undefined}
        />
      ) : (
        <input
          id={id}
          aria-label={field.label}
          type={field.type === 'password' ? 'password' : field.type === 'number' ? 'number' : 'text'}
          value={String(value)}
          onChange={(event) => onChange(field.key, event.target.value)}
          className={baseInputClass}
          placeholder={field.writeOnly ? '留空表示不修改现有值' : undefined}
        />
      )}
      {field.writeOnly ? <p className="mt-2 text-xs text-slate-500">写入专用，不回显当前值。</p> : null}
      {field.restartRequired ? <p className="mt-2 text-xs text-amber-700">保存后需要重启。</p> : null}
    </label>
  )
}
