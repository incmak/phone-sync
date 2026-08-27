import { spawnSync } from 'node:child_process';
import path from 'node:path';

const script = path.resolve(__dirname, '..', 'check-ui-xml.mjs');
const fixture = (name: string) => path.resolve(__dirname, 'fixtures', name);

function check(density: number, ...files: string[]) {
  return spawnSync(process.execPath, [script, '--density', String(density), '--expect-files', String(files.length), ...files], {
    encoding: 'utf8',
  });
}

describe('check-ui-xml', () => {
  test('prints the exact pass summary for valid bounds and targets', () => {
    const result = check(160, fixture('ui-pass.xml'));
    expect(result.status).toBe(0);
    expect(result.stdout.trim()).toBe('UI_XML_CHECK PASS files=1 out_of_bounds=0 undersized=0 sibling_overlaps=0');
  });

  test('accepts the standard uiautomator XML declaration before one hierarchy root', () => {
    const result = check(160, fixture('ui-declaration.xml'));
    expect(result.status).toBe(0);
  });

  test.each([
    ['ui-undersized.xml', 'undersized'],
    ['ui-out-of-bounds.xml', 'out_of_bounds'],
    ['ui-overlap.xml', 'sibling_overlaps'],
  ])('rejects %s', (file, reason) => {
    const result = check(160, fixture(file));
    expect(result.status).not.toBe(0);
    expect(result.stderr).toContain(reason);
  });

  test('accepts a nested uiautomator root node and applies density-scaled targets', () => {
    const result = check(320, fixture('ui-nested-320.xml'));
    expect(result.status).toBe(0);
  });

  test('fails closed when a checked node omits valid bounds', () => {
    const result = check(160, fixture('ui-missing-bounds.xml'));
    expect(result.status).not.toBe(0);
    expect(result.stderr).toContain('invalid_bounds');
  });

  test('rejects truncated node XML instead of accepting partial evidence', () => {
    const result = check(160, fixture('ui-truncated.xml'));
    expect(result.status).not.toBe(0);
    expect(result.stderr).toContain('malformed_xml');
  });

  test.each(['ui-unclosed-hierarchy.xml', 'ui-extra-hierarchy-close.xml', 'ui-trailing-garbage.xml', 'ui-second-hierarchy-open.xml'])('rejects malformed hierarchy document %s', (file) => {
    const result = check(160, fixture(file));
    expect(result.status).not.toBe(0);
    expect(result.stderr).toContain('malformed_xml');
  });
});
