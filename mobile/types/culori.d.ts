// Minimal ambient type declaration for culori v4 (no bundled .d.ts files).
// Only the subset used by tokens.ts is typed here.

declare module 'culori' {
  export type ColorMode = string;

  export interface OklchColor {
    mode: 'oklch';
    l: number;
    c: number;
    h: number;
    alpha?: number;
  }

  export interface RgbColor {
    mode: 'rgb';
    r: number;
    g: number;
    b: number;
    alpha?: number;
  }

  export type AnyColor = OklchColor | RgbColor | { mode: string; [key: string]: unknown };

  /** Convert a color to the target color mode. */
  export function converter(mode: 'rgb'): (color: AnyColor) => RgbColor | undefined;
  export function converter(mode: 'oklch'): (color: AnyColor) => OklchColor | undefined;
  export function converter(mode: string): (color: AnyColor) => AnyColor | undefined;

  /** Serialize a color object to a CSS hex string. */
  export function formatHex(color: AnyColor | null | undefined): string | undefined;
}
