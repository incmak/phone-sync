import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import zlib from 'node:zlib';

const script = path.resolve(__dirname, '..', 'check-launcher-assets.mjs');
const mobileRoot = path.resolve(__dirname, '..', '..');
const temporaryRoots: string[] = [];

function crc32(data: Buffer): number {
  let crc = 0xffffffff;
  for (const byte of data) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1));
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function chunk(type: string, data: Buffer): Buffer {
  const name = Buffer.from(type, 'ascii');
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length);
  const checksum = Buffer.alloc(4);
  checksum.writeUInt32BE(crc32(Buffer.concat([name, data])));
  return Buffer.concat([length, name, data, checksum]);
}

function rgbaPng(width: number, height: number, alpha: (x: number, y: number) => number): Buffer {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;
  ihdr[9] = 6;
  const scanlines = Buffer.alloc((width * 4 + 1) * height);
  for (let y = 0; y < height; y++) {
    const row = y * (width * 4 + 1);
    scanlines[row] = 0;
    for (let x = 0; x < width; x++) {
      const pixel = row + 1 + x * 4;
      scanlines[pixel + 3] = alpha(x, y);
    }
  }
  return Buffer.concat([
    Buffer.from('89504e470d0a1a0a', 'hex'),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(scanlines)),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

function fixtureRoot(monochrome: Buffer | null): string {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'twinotify-launcher-'));
  temporaryRoots.push(root);
  fs.mkdirSync(path.join(root, 'assets', 'brand'), { recursive: true });
  fs.writeFileSync(
    path.join(root, 'app.json'),
    JSON.stringify({ expo: { android: { adaptiveIcon: monochrome === null ? {} : { monochromeImage: './assets/brand/adaptive-monochrome.png' } } } }),
  );
  if (monochrome !== null) fs.writeFileSync(path.join(root, 'assets', 'brand', 'adaptive-monochrome.png'), monochrome);
  return root;
}

function check(root: string) {
  return spawnSync(process.execPath, [script, '--root', root], { encoding: 'utf8' });
}

afterEach(() => {
  for (const root of temporaryRoots.splice(0)) fs.rmSync(root, { recursive: true, force: true });
});

describe('launcher asset source check', () => {
  test('accepts Twinotify’s configured transparent monochrome silhouette', () => {
    const result = check(mobileRoot);
    expect(result.status).toBe(0);
    expect(result.stdout).toContain('LAUNCHER_ASSET_CHECK PASS');
  });

  test('rejects an omitted monochrome layer', () => {
    const result = check(fixtureRoot(null));
    expect(result.status).not.toBe(0);
    expect(result.stderr).toContain('monochromeImage');
  });

  test.each([
    ['fully transparent', 0],
    ['fully opaque', 255],
  ])('rejects a %s monochrome layer', (reason, alpha) => {
    const result = check(fixtureRoot(rgbaPng(8, 8, () => alpha)));
    expect(result.status).not.toBe(0);
    expect(result.stderr).toContain(reason);
  });
});
