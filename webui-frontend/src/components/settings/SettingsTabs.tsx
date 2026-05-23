import type { SettingsCategoryDefinition, SettingsCategoryId } from '../../settings/settingsSchema'

type SettingsTabsProps = {
  categories: SettingsCategoryDefinition[]
  activeCategoryId: SettingsCategoryId
  onSelectCategory: (categoryId: SettingsCategoryId) => void
}

/**
 * 设置分区 tab 独立渲染 aria 状态，页面只负责保存当前分区。
 */
export function SettingsTabs({categories, activeCategoryId, onSelectCategory}: SettingsTabsProps) {
  // 标签组改为左对齐，让八个子配置页的入口与平台配置字段使用同一条左边线。
  return (
    <div className="mx-auto flex min-w-0 w-full max-w-7xl flex-wrap justify-start gap-2">
      {categories.map((category) => (
        <button
          key={category.id}
          type="button"
          aria-pressed={category.id === activeCategoryId}
          onClick={() => onSelectCategory(category.id)}
          className={category.id === activeCategoryId
            ? 'rounded-lg bg-slate-950 px-3 py-2 text-sm font-semibold text-white'
            : 'rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50'}
        >
          {category.label}
        </button>
      ))}
    </div>
  )
}
