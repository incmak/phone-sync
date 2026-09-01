import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';

function fail(message) {
  process.stderr.write(`LAUNCHER_ASSET_CHECK FAIL ${message}\n`);
  process.exit(1);
}

function rootArgument(argv) {
  const index = argv.indexOf('--root');
  if (index < 0) return process.cwd();
  if (!argv[index + 1] || index + 2 !== argv.length) fail('bad --root argument');
  return path.resolve(argv[index + 1]);
}

function paeth(left, up, upperLeft) {
  const estimate = left + up - upperLeft;
  const leftDistance = Math.abs(estimate - left);
  const upDistance = Math.abs(estimate - up);
  const upperLeftDistance = Math.abs(estimate - upperLeft);
  if (leftDistance <= upDistance && leftDistance <= upperLeftDistance) return left;
  return upDistance <= upperLeftDistance ? up : upperLeft;
}

function decodeRgbaPng(file) {
  const bytes = fs.readFileSync(file);
  const signature = Buffer.from('89504e470d0a1a0a', 'hex');
  if (bytes.length < signature.length || !bytes.subarray(0, signature.length).equals(signature)) fail('monochromeImage is not a PNG');

  let offset = signature.length;
  let header = null;
  const compressed = [];
  while (offset + 12 <= bytes.length) {
    const length = bytes.readUInt32BE(offset);
    const type = bytes.toString('ascii', offset + 4, offset + 8);
    const dataStart = offset + 8;
    const dataEnd = dataStart + length;
    if (dataEnd + 4 > bytes.length) fail('monochromeImage has a truncated PNG chunk');
    const data = bytes.subarray(dataStart, dataEnd);
    if (type === 'IHDR') {
      if (data.length !== 13) fail('monochromeImage has an invalid PNG header');
      header = {
        width: data.readUInt32BE(0),
        height: data.readUInt32BE(4),
        bitDepth: data[8],
        colorType: data[9],
        compression: data[10],
        filter: data[11],
        interlace: data[12],
      };
    } else if (type === 'IDAT') {
      compressed.push(data);
    } else if (type === 'IEND') {
      break;
    }
    offset = dataEnd + 4;
  }
  if (!header || compressed.length === 0) fail('monochromeImage is missing PNG image data');
  if (header.bitDepth !== 8 || header.colorType !== 6 || header.compression !== 0 || header.filter !== 0 || header.interlace !== 0) {
    fail('monochromeImage must be a non-interlaced 8-bit RGBA PNG');
  }

  const bytesPerPixel = 4;
  const stride = header.width * bytesPerPixel;
  const filtered = zlib.inflateSync(Buffer.concat(compressed));
  if (filtered.length !== (stride + 1) * header.height) fail('monochromeImage has invalid scanline data');
  const pixels = Buffer.alloc(stride * header.height);
  let source = 0;
  for (let y = 0; y < header.height; y++) {
    const filterType = filtered[source++];
    if (filterType > 4) fail('monochromeImage uses an invalid PNG filter');
    for (let x = 0; x < stride; x++) {
      const left = x >= bytesPerPixel ? pixels[y * stride + x - bytesPerPixel] : 0;
      const up = y > 0 ? pixels[(y - 1) * stride + x] : 0;
      const upperLeft = y > 0 && x >= bytesPerPixel ? pixels[(y - 1) * stride + x - bytesPerPixel] : 0;
      const predictor = filterType === 1 ? left
        : filterType === 2 ? up
          : filterType === 3 ? Math.floor((left + up) / 2)
            : filterType === 4 ? paeth(left, up, upperLeft)
              : 0;
      pixels[y * stride + x] = (filtered[source++] + predictor) & 0xff;
    }
  }
  return { ...header, pixels };
}

const root = rootArgument(process.argv.slice(2));
const configFile = path.join(root, 'app.json');
if (!fs.existsSync(configFile)) fail('app.json is missing');
const config = JSON.parse(fs.readFileSync(configFile, 'utf8'));
const configured = config?.expo?.android?.adaptiveIcon?.monochromeImage;
if (typeof configured !== 'string' || configured.trim() === '') fail('android.adaptiveIcon.monochromeImage is missing');
const expected = './assets/brand/adaptive-monochrome.png';
if (configured !== expected) fail(`monochromeImage must remain ${expected}`);
const asset = path.resolve(root, configured);
if (!fs.existsSync(asset)) fail('configured monochromeImage file is missing');

const image = decodeRgbaPng(asset);
let transparent = 0;
let visible = 0;
let fullyOpaque = true;
let minX = image.width;
let minY = image.height;
let maxX = -1;
let maxY = -1;
for (let y = 0; y < image.height; y++) {
  for (let x = 0; x < image.width; x++) {
    const alpha = image.pixels[(y * image.width + x) * 4 + 3];
    if (alpha === 0) transparent++;
    else {
      visible++;
      minX = Math.min(minX, x);
      minY = Math.min(minY, y);
      maxX = Math.max(maxX, x);
      maxY = Math.max(maxY, y);
    }
    if (alpha !== 255) fullyOpaque = false;
  }
}
if (visible === 0) fail('monochromeImage is fully transparent');
if (fullyOpaque || transparent === 0) fail('monochromeImage is fully opaque');
if (image.width !== 1024 || image.height !== 1024) fail('monochromeImage must be 1024x1024');

const safeInset = image.width / 4;
if (minX < safeInset || minY < safeInset || maxX >= image.width - safeInset || maxY >= image.height - safeInset) {
  fail(`monochromeImage leaves the adaptive safe zone: bounds=${minX},${minY},${maxX},${maxY}`);
}
const width = maxX - minX + 1;
const height = maxY - minY + 1;
if (width < image.width * 0.35 || height < image.height * 0.3) fail('monochromeImage silhouette is implausibly small');
const centerX = (minX + maxX + 1) / 2;
const centerY = (minY + maxY + 1) / 2;
if (Math.abs(centerX - image.width / 2) > image.width * 0.03 || Math.abs(centerY - image.height / 2) > image.height * 0.03) {
  fail(`monochromeImage is not optically centered: center=${centerX},${centerY}`);
}

process.stdout.write(
  `LAUNCHER_ASSET_CHECK PASS size=${image.width}x${image.height} visible=${visible} transparent=${transparent} bounds=${minX},${minY},${maxX},${maxY}\n`,
);
