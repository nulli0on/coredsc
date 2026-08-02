import {existsSync, readFileSync, readdirSync, statSync} from 'node:fs';
import {dirname, extname, join, relative, resolve, sep} from 'node:path';
import {fileURLToPath} from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const docsRoot = join(root, 'docs');
const staticRoot = join(root, 'static');
const failures = [];

function walk(directory) {
  const output = [];
  for (const name of readdirSync(directory)) {
    const path = join(directory, name);
    if (statSync(path).isDirectory()) output.push(...walk(path));
    else output.push(path);
  }
  return output;
}

function posix(path) {
  return path.split(sep).join('/');
}

function frontMatterSlug(source) {
  if (!source.startsWith('---\n')) return null;
  const end = source.indexOf('\n---\n', 4);
  if (end === -1) return null;
  const match = source.slice(4, end).match(/^slug:\s*['"]?([^'"\n]+)['"]?\s*$/m);
  return match?.[1]?.trim() || null;
}

function routeFor(relativeFile, source) {
  const slug = frontMatterSlug(source);
  if (slug) return slug === '/' ? '/' : `/${slug.replace(/^\/+|\/+$/g, '')}`;
  const withoutExtension = relativeFile.replace(/\.(md|mdx)$/i, '');
  if (withoutExtension === 'index') return '/';
  if (withoutExtension.endsWith('/index')) return `/${withoutExtension.slice(0, -6)}`;
  return `/${withoutExtension}`;
}

function normalizeRoute(pathname) {
  if (!pathname) return '/';
  const collapsed = pathname.replace(/\/{2,}/g, '/');
  return collapsed !== '/' && collapsed.endsWith('/') ? collapsed.slice(0, -1) : collapsed;
}

const docs = [];
const routes = new Map();
const docIds = new Set();
for (const file of walk(docsRoot).filter((path) => ['.md', '.mdx'].includes(extname(path)))) {
  const relativeFile = posix(relative(docsRoot, file));
  const source = readFileSync(file, 'utf8');
  const route = routeFor(relativeFile, source);
  if (routes.has(route)) failures.push(`Duplicate route ${route}: ${routes.get(route)} and ${relativeFile}`);
  routes.set(route, relativeFile);
  docIds.add(relativeFile.replace(/\.(md|mdx)$/i, ''));
  docs.push({file, relativeFile, source, route});
}

const staticFiles = new Set(
  walk(staticRoot).filter((path) => statSync(path).isFile()).map((path) => `/${posix(relative(staticRoot, path))}`),
);

function stripCode(source) {
  return source.replace(/```[\s\S]*?```/g, '').replace(/`[^`\n]*`/g, '');
}

function resolveDocFileLink(doc, targetPath) {
  const absolute = resolve(dirname(doc.file), targetPath);
  if (!absolute.startsWith(`${docsRoot}${sep}`) && absolute !== docsRoot) return false;
  return existsSync(absolute) && ['.md', '.mdx'].includes(extname(absolute));
}

function checkTarget(doc, target) {
  const value = target.trim();
  if (!value || /^(?:#|https?:\/\/|mailto:|tel:|data:|javascript:)/i.test(value)) return;
  if (value.includes('{') || value.includes('}')) return;

  const pathOnly = value.split('#', 1)[0].split('?', 1)[0];
  if (!pathOnly) return;

  if (/\.(?:md|mdx)$/i.test(pathOnly) && !pathOnly.startsWith('/')) {
    if (!resolveDocFileLink(doc, pathOnly)) failures.push(`${doc.relativeFile}: missing document file link ${value}`);
    return;
  }

  let pathname;
  try {
    pathname = new URL(pathOnly, `https://docs.invalid${doc.route}`).pathname;
  } catch {
    failures.push(`${doc.relativeFile}: invalid link ${value}`);
    return;
  }
  const normalized = normalizeRoute(pathname);
  if (!routes.has(normalized) && !staticFiles.has(pathname) && !staticFiles.has(normalized)) {
    failures.push(`${doc.relativeFile}: broken link ${value} resolves to ${pathname}`);
  }
}

const markdownLink = /!?\[[^\]]*\]\(([^)\s]+)(?:\s+['"][^'"]*['"])?\)/g;
const htmlLink = /\b(?:href|to|src)\s*=\s*['"]([^'"]+)['"]/g;
const baseUrlAsset = /useBaseUrl\(\s*['"]([^'"]+)['"]\s*\)/g;
for (const doc of docs) {
  const source = stripCode(doc.source);
  for (const pattern of [markdownLink, htmlLink, baseUrlAsset]) {
    pattern.lastIndex = 0;
    for (let match; (match = pattern.exec(source));) checkTarget(doc, match[1]);
  }
}

const sidebarSource = readFileSync(join(root, 'config', 'sidebars.js'), 'utf8');
const sidebarIds = [...sidebarSource.matchAll(/\bid:\s*['"]([^'"]+)['"]/g)].map((match) => match[1]);
for (const id of sidebarIds) {
  if (!docIds.has(id)) failures.push(`config/sidebars.js: unknown doc id ${id}`);
}
const duplicateSidebarIds = sidebarIds.filter((id, index) => sidebarIds.indexOf(id) !== index);
for (const id of new Set(duplicateSidebarIds)) failures.push(`config/sidebars.js: duplicate doc id ${id}`);

const configSource = readFileSync(join(root, 'docusaurus.config.js'), 'utf8');
for (const match of configSource.matchAll(/\b(?:to|from):\s*['"](\/[^'"]*)['"]/g)) {
  const value = match[1];
  if (value === '/install' || value === '/config' || value === '/doctor') continue; // redirect sources
  const normalized = normalizeRoute(value);
  if (!routes.has(normalized) && !staticFiles.has(value)) failures.push(`docusaurus.config.js: broken internal target ${value}`);
}

if (docs.length === 0) failures.push('No documentation files found.');
if (failures.length) {
  console.error(`Documentation validation failed with ${failures.length} problem(s):`);
  for (const failure of failures) console.error(`- ${failure}`);
  process.exit(1);
}

console.log(`Documentation validation passed: ${docs.length} pages, ${routes.size} routes, ${sidebarIds.length} sidebar entries, ${staticFiles.size} static files.`);
