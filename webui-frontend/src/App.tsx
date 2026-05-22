// 最小预览页用于验证 React + Tailwind 构建链路，业务页面迁移在后续任务中接入。
function App() {
  const buildChecks = [
    'React 19 runtime',
    'Tailwind 4 styles',
    'Vite bundled assets',
  ]

  return (
    <main className="min-h-screen bg-zinc-50 text-zinc-950">
      <section className="mx-auto flex min-h-screen max-w-5xl flex-col justify-center px-6 py-14">
        <p className="mb-5 w-fit border-l-4 border-emerald-500 bg-white px-4 py-2 text-sm font-medium text-zinc-700 shadow-sm">
          dynamic-bot WebUI migration preview
        </p>
        <h1 className="max-w-3xl text-4xl font-semibold tracking-normal text-zinc-950 sm:text-5xl">
          React frontend workspace is ready for the WebUI migration.
        </h1>
        <p className="mt-5 max-w-2xl text-base leading-7 text-zinc-600">
          This preview confirms the standalone frontend can compile into bundled
          WebUI assets while the existing management shell remains active.
        </p>
        <div className="mt-9 grid gap-3 sm:grid-cols-3">
          {buildChecks.map((label) => (
            <div key={label} className="rounded-lg border border-zinc-200 bg-white p-4 shadow-sm">
              <div className="text-sm font-medium text-zinc-900">{label}</div>
              <div className="mt-2 h-1.5 rounded-full bg-gradient-to-r from-emerald-500 via-sky-500 to-amber-400" />
            </div>
          ))}
        </div>
      </section>
    </main>
  )
}

export default App
