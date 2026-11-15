import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { pathToFileURL } from 'node:url'

// Keep the security policy in package.json so npm and direct tool invocations enforce the same baseline.
const { engines } = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8'))

// Accept only stable releases in explicitly approved major branches; fail closed for malformed policy or version input.
export function isSupportedNodeVersion(version) {
  const match = /^(\d+)\.(\d+)\.(\d+)$/.exec(version)
  if (!match) return false
  const [major, minor, patch] = match.slice(1).map(Number)
  return engines.node.split(' || ').some((range) => {
    // Each policy arm describes one supported major and its first patched release.
    const minimum = /^>=(\d+)\.(\d+)\.(\d+) <(\d+)$/.exec(range)
    if (!minimum) throw new Error(`Unsupported Node security policy: ${range}`)
    const [minMajor, minMinor, minPatch, exclusiveMajor] = minimum.slice(1).map(Number)
    return major === minMajor && major < exclusiveMajor
      && (minor > minMinor || (minor === minMinor && patch >= minPatch))
  })
}

// Importing the predicate for tests must not run the CLI; real invocations always inspect this process, not a supplied version.
if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  console.log(`Node.js ${process.versions.node} (${process.execPath}); required: ${engines.node}`)
  if (!isSupportedNodeVersion(process.versions.node)) {
    console.error('Unsupported Node.js runtime. Install a patched release before running frontend tools (2026-10-21 security baseline).')
    process.exitCode = 1
  }
}
