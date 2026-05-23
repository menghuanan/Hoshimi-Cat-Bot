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
  // 标签组居中排列，给系统配置页留出更均衡的横向视觉重心。
  return (
    <div className="mx-auto flex min-w-0 max-w-5xl flex-wrap justify-center gap-2">
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
