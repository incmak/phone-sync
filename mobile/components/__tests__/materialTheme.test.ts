import {
  MATERIAL_COLOR_ROLE_KEYS,
  fixedSeamScheme,
  resolveMaterialScheme,
  type MaterialColorScheme,
} from '../tokens';

function completeDynamicScheme(seed: string): MaterialColorScheme {
  return Object.fromEntries(
    MATERIAL_COLOR_ROLE_KEYS.map((role, index) => [role, `${seed}-${index}`]),
  ) as unknown as MaterialColorScheme;
}

describe('Material 3 color scheme resolution', () => {
  test('uses a complete dynamic scheme as one atomic value', () => {
    const dynamic = completeDynamicScheme('dynamic');

    expect(resolveMaterialScheme({ dark: false, dynamic })).toBe(dynamic);
  });

  test('falls back to the complete Seam scheme when one dynamic role is absent', () => {
    const dynamic = completeDynamicScheme('dynamic');
    const incomplete = { ...dynamic } as Partial<MaterialColorScheme>;
    delete incomplete.outline;

    const resolved = resolveMaterialScheme({ dark: false, dynamic: incomplete });

    expect(resolved).toEqual(fixedSeamScheme(false));
    expect(Object.values(resolved).some((value) => String(value).startsWith('dynamic'))).toBe(false);
  });

  test('provides every Material role in distinct light and dark fallback schemes', () => {
    const light = fixedSeamScheme(false);
    const dark = fixedSeamScheme(true);

    expect(Object.keys(light).sort()).toEqual([...MATERIAL_COLOR_ROLE_KEYS].sort());
    expect(Object.keys(dark).sort()).toEqual([...MATERIAL_COLOR_ROLE_KEYS].sort());
    expect(light.surface).not.toBe(dark.surface);
    expect(light.onSurface).not.toBe(dark.onSurface);
  });
});
