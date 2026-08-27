import fs from 'node:fs';

function fail(message) {
  process.stderr.write(`${message}\n`);
  process.exit(1);
}

function parseArgs(argv) {
  const densityIndex = argv.indexOf('--density');
  const countIndex = argv.indexOf('--expect-files');
  if (densityIndex < 0 || countIndex < 0) fail('UI_XML_CHECK FAIL missing required arguments');
  const density = Number(argv[densityIndex + 1]);
  const expectedFiles = Number(argv[countIndex + 1]);
  const files = argv.filter((value, index) => index !== densityIndex && index !== densityIndex + 1 && index !== countIndex && index !== countIndex + 1);
  if (!Number.isFinite(density) || density <= 0 || files.length !== expectedFiles) fail('UI_XML_CHECK FAIL bad file arguments');
  return { density, files };
}

function attributes(source) {
  const values = {};
  for (const match of source.matchAll(/([\w-]+)="([^"]*)"/g)) values[match[1]] = match[2];
  return values;
}

function bounds(value) {
  const match = /^\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]$/.exec(value ?? '');
  return match ? { left: Number(match[1]), top: Number(match[2]), right: Number(match[3]), bottom: Number(match[4]) } : null;
}

function parseXml(xml) {
  const trimmed = xml.trim();
  const declaration = /^<\?xml\s+version=(?:"1\.0"|'1\.0')(?:\s+encoding=(?:"UTF-8"|'UTF-8'))?(?:\s+standalone=(?:"(?:yes|no)"|'(?:yes|no)'))?\s*\?>/.exec(trimmed);
  const document = declaration ? trimmed.slice(declaration[0].length) : trimmed;
  const documentMatch = /^<hierarchy\b([^>]*)>([\s\S]*)<\/hierarchy>$/.exec(document);
  if (!documentMatch || /<\/?hierarchy\b/.test(documentMatch[2])) throw new Error('malformed_xml hierarchy document');
  const rootMatch = /<hierarchy\b([^>]*)>/.exec(document);
  const hierarchyViewport = bounds(attributes(rootMatch?.[1] ?? '').bounds);
  const nodes = [];
  const stack = [];
  for (const match of document.matchAll(/<node\b([^>]*?)(\/?)>|<\/node>/g)) {
    if (match[0] === '</node>') {
      if (!stack.length) throw new Error('malformed_xml unmatched node close');
      stack.pop();
      continue;
    }
    const node = { attrs: attributes(match[1]), children: [], parent: stack.at(-1) ?? null };
    node.parent?.children.push(node);
    nodes.push(node);
    if (match[2] !== '/') stack.push(node);
  }
  if (stack.length) throw new Error('malformed_xml unclosed node');
  const viewport = hierarchyViewport ?? bounds(nodes[0]?.attrs.bounds);
  if (!viewport) throw new Error('missing root viewport bounds');
  return { viewport, nodes };
}

const interactive = (node) => ['clickable', 'checkable', 'focusable'].some((key) => node.attrs[key] === 'true');
const textNode = (node) => Boolean(node.attrs.text || node.attrs['content-desc']);
const overlaps = (a, b) => a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top;
function related(a, b) {
  for (let current = a; current; current = current.parent) if (current === b) return true;
  for (let current = b; current; current = current.parent) if (current === a) return true;
  return false;
}
function hasInteractiveDescendant(node) {
  const visit = (candidate) => candidate.children.some((child) => interactive(child) || visit(child));
  return visit(node);
}

const { density, files } = parseArgs(process.argv.slice(2));
let outOfBounds = 0;
let undersized = 0;
let siblingOverlaps = 0;
let invalidBounds = 0;

for (const file of files) {
  const { viewport, nodes } = parseXml(fs.readFileSync(file, 'utf8'));
  const minimum = (44 * density) / 160;
  for (const node of nodes) {
    const area = bounds(node.attrs.bounds);
    if (!area) {
      if (textNode(node) || interactive(node)) invalidBounds++;
      continue;
    }
    if (textNode(node) && (area.left < viewport.left || area.top < viewport.top || area.right > viewport.right || area.bottom > viewport.bottom)) outOfBounds++;
    if (interactive(node) && (area.right - area.left < minimum || area.bottom - area.top < minimum)) undersized++;
  }
  const leaves = nodes.filter((node) => interactive(node) && !hasInteractiveDescendant(node));
  for (const leaf of leaves) {
    const leafBounds = bounds(leaf.attrs.bounds);
    if (!leafBounds) {
      invalidBounds++;
      continue;
    }
    for (const label of nodes.filter(textNode)) {
      if (label === leaf || related(leaf, label)) continue;
      const labelBounds = bounds(label.attrs.bounds);
      if (!labelBounds) {
        invalidBounds++;
      } else if (overlaps(leafBounds, labelBounds)) siblingOverlaps++;
    }
  }
}

if (outOfBounds || undersized || siblingOverlaps || invalidBounds) {
  fail(`UI_XML_CHECK FAIL files=${files.length} out_of_bounds=${outOfBounds} undersized=${undersized} sibling_overlaps=${siblingOverlaps} invalid_bounds=${invalidBounds}`);
}
process.stdout.write(`UI_XML_CHECK PASS files=${files.length} out_of_bounds=0 undersized=0 sibling_overlaps=0\n`);
