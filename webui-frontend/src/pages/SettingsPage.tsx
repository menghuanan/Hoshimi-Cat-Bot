import { useState } from 'react'
import { PageSection } from '../components/PageSection'
import { StatusCard } from '../components/StatusCard'
import { useSettingsFiles } from '../hooks/useSettingsFiles'

/**
 * 配置页保留快照 token、代理写入和确认保存流程，代理值不从后端回填。
 */
export function SettingsPage() {
  const {loading, biliConfig, botConfig, saveBili, saveBot, reload} = useSettingsFiles()
  const [proxyText, setProxyText] = useState('')
  const [oneBotToken, setOneBotToken] = useState('')
  const [saving, setSaving] = useState(false)
  const [message, setMessage] = useState('')

  /**
   * 保存动作只提交显式输入的代理文本，空输入会由 API helper 解析为 preserve。
   */
  const saveSettings = async () => {
    setSaving(true)
    setMessage('')
    try {
      const biliToken = String(biliConfig?.snapshotToken || '')
      const botToken = String(botConfig?.snapshotToken || '')
      if (biliToken) {
        await saveBili({snapshotToken: biliToken, proxyText})
      }
      if (botToken && oneBotToken.trim()) {
        await saveBot({snapshotToken: botToken, token: oneBotToken.trim()})
      }
      await reload()
      setProxyText('')
      setOneBotToken('')
      setMessage('配置已提交')
    } catch (error) {
      setMessage(error instanceof Error ? error.message : '保存失败')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div data-page="settings" className="space-y-6">
      <PageSection title="系统配置" description="保存配置会携带 snapshotToken，并通过统一确认弹窗提交 WebUI 密码。">
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          <StatusCard label="BiliConfig 快照" value={loading ? '--' : shortToken(biliConfig?.snapshotToken)} tone="emerald" />
          <StatusCard label="bot.yml 快照" value={loading ? '--' : shortToken(botConfig?.snapshotToken)} tone="sky" />
          <StatusCard label="代理输入" value="写入专用" tone="amber" detail="空白保存将保留现有代理" />
        </div>
      </PageSection>

      <PageSection
        title="写入设置"
        description="仅填写需要替换的敏感值；后端不会把现有代理明文回显给前端。"
        actions={(
          <button
            type="button"
            className="rounded-lg bg-slate-950 px-4 py-2 text-sm font-semibold text-white disabled:cursor-not-allowed disabled:bg-slate-400"
            disabled={saving || loading}
            onClick={saveSettings}
          >
            {saving ? '保存中' : '保存'}
          </button>
        )}
      >
        <div className="grid gap-4 xl:grid-cols-2">
          <label className="block rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
            <span className="text-sm font-medium text-slate-800">代理列表</span>
            <textarea
              value={proxyText}
              onChange={(event) => setProxyText(event.target.value)}
              className="mt-3 min-h-32 w-full resize-y rounded-lg border border-slate-300 px-3 py-2 text-sm"
              placeholder="每行一个代理；留空表示保留现有代理"
            />
          </label>
          <label className="block rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
            <span className="text-sm font-medium text-slate-800">OneBot11 Token</span>
            <input
              value={oneBotToken}
              onChange={(event) => setOneBotToken(event.target.value)}
              className="mt-3 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm"
              placeholder="仅在需要替换时填写"
            />
            <p className="mt-3 break-words text-sm text-slate-500">该输入只作为写入值提交，不作为配置快照展示字段。</p>
          </label>
        </div>
        {message ? <p className="break-words text-sm font-medium text-slate-700">{message}</p> : null}
      </PageSection>
    </div>
  )
}

/**
 * token 只用于人工识别当前快照，不在界面展示完整值。
 */
function shortToken(value: unknown): string {
  const token = String(value || '--')
  return token.length > 12 ? `${token.slice(0, 12)}...` : token
}
