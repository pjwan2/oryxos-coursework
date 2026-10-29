// Fails the build when the Vue output cannot be served correctly by Spring Boot from /admin/.
// Run automatically by `npm run build`; also usable on its own as `npm run check-build`.
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const out = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'resources', 'static', 'admin')
const problems = []

function walk(dir) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name)
    return statSync(path).isDirectory() ? walk(path) : [path]
  })
}

if (!existsSync(join(out, 'index.html'))) {
  console.error(`check-build: ${out}/index.html is missing. Run "npm run build" first.`)
  process.exit(1)
}

const index = readFileSync(join(out, 'index.html'), 'utf8')

// Every asset reference must live under the /admin/ prefix and exist on disk.
const refs = [...index.matchAll(/(?:src|href)="([^"]+)"/g)].map((m) => m[1])
if (refs.length === 0) problems.push('index.html references no assets')
for (const ref of refs) {
  if (/^https?:\/\//.test(ref)) {
    problems.push(`index.html loads an external resource: ${ref}`)
  } else if (!ref.startsWith('/admin/')) {
    problems.push(`asset URL is not under /admin/: ${ref}`)
  } else if (!existsSync(join(out, ref.slice('/admin/'.length)))) {
    problems.push(`referenced asset does not exist: ${ref}`)
  }
}

// The console must not talk to anything but its own origin, and must not carry credentials.
const files = walk(out)
if (files.some((f) => f.endsWith('.map'))) problems.push('source maps must not be published')
for (const file of files.filter((f) => /\.(js|css|html)$/.test(f))) {
  const text = readFileSync(file, 'utf8')
  for (const match of text.matchAll(/https?:\/\/[^\s"'`)]+/g)) {
    const url = match[0]
    // Namespace and documentation URLs inside libraries are strings, not requests.
    if (!/w3\.org|vuejs\.org|github\.com|reactjs\.org|developer\.mozilla\.org/.test(url)) {
      problems.push(`${file.slice(out.length + 1)} contains a URL: ${url}`)
    }
  }
  if (/sk-[A-Za-z0-9]{20,}|api[_-]?key\s*[:=]\s*["'][^"']{8,}/i.test(text)) {
    problems.push(`${file.slice(out.length + 1)} looks like it contains a credential`)
  }
}

if (problems.length > 0) {
  console.error('check-build failed:\n - ' + problems.join('\n - '))
  process.exit(1)
}
console.log(`check-build: ok (${files.length} files, ${refs.length} assets in index.html)`)
