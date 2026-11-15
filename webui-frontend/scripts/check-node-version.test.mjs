import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import test from 'node:test'
import { isSupportedNodeVersion } from './check-node-version.mjs'

// Exercise security boundary releases across all approved branches rather than just the developer's current runtime.
test('accepts patched stable releases and subsequent patches within approved branches', () => {
  for (const version of ['22.23.4', '22.23.5', '22.24.0', '24.22.1', '24.23.0', '26.12.1', '26.13.0']) {
    assert.equal(isSupportedNodeVersion(version), true, version)
  }
})

// Explicitly reject vulnerable predecessors, EOL/odd branches, unreviewed majors and prerelease aliases.
test('rejects vulnerable, unapproved and non-stable versions', () => {
  for (const version of ['22.23.3', '22.22.99', '24.19.0', '24.22.0', '26.12.0', '20.99.0', '23.99.0', '25.99.0', '27.0.0', '28.0.0', '24.22.1-rc.1', '24.22', '', 'v24.22.1']) {
    assert.equal(isSupportedNodeVersion(version), false, version)
  }
})

// The version consumed by setup-node must remain permitted by the same policy used by local and Gradle builds.
test('the pinned CI runtime meets the security policy', () => {
  const pinned = readFileSync(new URL('../.node-version', import.meta.url), 'utf8').trim()
  assert.equal(isSupportedNodeVersion(pinned), true, pinned)
})

// Execute the real CLI so an unsupported runtime cannot merely print a warning and still report success.
test('CLI reports and enforces the actual process runtime', () => {
  const script = fileURLToPath(new URL('./check-node-version.mjs', import.meta.url))
  const result = spawnSync(process.execPath, [script], { encoding: 'utf8' })
  assert.equal(result.error, undefined)
  assert.equal(result.status, isSupportedNodeVersion(process.versions.node) ? 0 : 1)
  assert.ok(result.stdout.includes(`Node.js ${process.versions.node}`))
})

// Keep npm's engine check, lock metadata, CI setup and Gradle preflight tied to one security policy.
test('package installation and project builds enforce the patched runtime', () => {
  const packageJson = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8'))
  const packageLock = JSON.parse(readFileSync(new URL('../package-lock.json', import.meta.url), 'utf8'))
  const npmConfig = readFileSync(new URL('../.npmrc', import.meta.url), 'utf8')
  const workflow = readFileSync(new URL('../../.github/workflows/ci.yml', import.meta.url), 'utf8')
  const releaseWorkflow = readFileSync(new URL('../../.github/workflows/release.yml', import.meta.url), 'utf8')
  const buildScript = readFileSync(new URL('../../build.gradle.kts', import.meta.url), 'utf8')

  assert.equal(packageLock.packages[''].engines.node, packageJson.engines.node)
  assert.equal(packageLock.packages[''].hasInstallScript, true)
  // Guard each public tool command directly so npm's ignore-scripts setting cannot skip its preflight.
  for (const command of ['dev', 'build', 'test', 'test:e2e', 'lint', 'preview']) {
    assert.ok(packageJson.scripts[command].startsWith('node scripts/check-node-version.mjs && '), command)
  }
  assert.match(npmConfig, /^engine-strict=true$/m)
  assert.match(workflow, /node-version-file:\s*['"]webui-frontend\/\.node-version['"]/)
  assert.equal((releaseWorkflow.match(/node-version-file:\s*['"]webui-frontend\/\.node-version['"]/g) ?? []).length, 3)
  assert.match(buildScript, /dependsOn\(checkWebUiNodeVersion\)/)
  assert.match(buildScript, /commandLine\(npmExecutableName, "ci"\)/)
})
