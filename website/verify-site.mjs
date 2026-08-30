import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const root = dirname(fileURLToPath(import.meta.url));
const requiredFiles = ['index.html', 'styles.css', 'script.js', 'assets/twinotify-mark.svg'];

for (const file of requiredFiles) {
  if (!existsSync(join(root, file))) throw new Error(`Missing website/${file}`);
}

const html = readFileSync(join(root, 'index.html'), 'utf8');
const css = readFileSync(join(root, 'styles.css'), 'utf8');

const requiredHtml = [
  '<main',
  'id="features"',
  'id="how-it-works"',
  'id="download"',
  'Check Android releases',
  'Build from source',
  'End-to-end encrypted',
  'Direct on Wi-Fi',
  'aria-label="Twinotify home screen',
  'aria-label="Twinotify pairing screen',
  'aria-label="Twinotify app filter screen',
  'prefers-reduced-motion',
];

for (const fragment of requiredHtml) {
  const source = fragment === 'prefers-reduced-motion' ? css : html;
  if (!source.includes(fragment)) throw new Error(`Missing required fragment: ${fragment}`);
}

const localReferences = [...html.matchAll(/(?:src|href)="(?!https?:|#|mailto:)([^"?]+)(?:\?[^\"]*)?"/g)]
  .map((match) => match[1])
  .filter((path) => !path.startsWith('data:'));

for (const reference of localReferences) {
  if (!existsSync(join(root, reference))) throw new Error(`Broken local reference: ${reference}`);
}

console.log('Twinotify marketing site contract passed.');
