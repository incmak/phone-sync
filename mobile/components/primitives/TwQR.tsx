import React from 'react';
import { View, StyleSheet } from 'react-native';
import QRCode from 'react-native-qrcode-svg';
import Svg, { Circle, Rect } from 'react-native-svg';
import { useTheme } from '../Theme';

interface TwQRProps {
  value: string;
  size?: number;
  accentOverlay?: boolean;
}

// Overlay: two small overlapping circles (mirror logo) at center
function MirrorOverlay({ size, fg, ac }: { size: number; fg: string; ac: string }) {
  const cell = size / 25;
  return (
    <Svg width={cell * 5} height={cell * 5} viewBox={`0 0 ${cell * 5} ${cell * 5}`}>
      <Rect width={cell * 5} height={cell * 5} rx={cell} fill="white" />
      <Circle
        cx={cell * 1.8}
        cy={cell * 2.5}
        r={cell * 1.2}
        fill="none"
        stroke={fg}
        strokeWidth={cell * 0.5}
      />
      <Circle
        cx={cell * 3.2}
        cy={cell * 2.5}
        r={cell * 1.2}
        fill="none"
        stroke={ac}
        strokeWidth={cell * 0.5}
      />
    </Svg>
  );
}

export function TwQR({ value, size = 200, accentOverlay = true }: TwQRProps) {
  const theme = useTheme();
  const overlaySize = size * 0.18;

  return (
    <View
      style={[
        styles.container,
        {
          width: size + size * 0.08,
          height: size + size * 0.08,
          backgroundColor: theme.card,
          borderColor: theme.border,
          borderRadius: 14,
          padding: size * 0.04,
        },
      ]}
    >
      <QRCode
        value={value || 'twinotify'}
        size={size}
        color={theme.ink}
        backgroundColor={theme.card}
      />

      {/* Mirror-glyph overlay at center */}
      <View
        style={[
          styles.overlay,
          {
            top: (size - overlaySize) / 2 + size * 0.04,
            left: (size - overlaySize) / 2 + size * 0.04,
            width: overlaySize,
            height: overlaySize,
          },
        ]}
        pointerEvents="none"
      >
        <MirrorOverlay
          size={overlaySize}
          fg={theme.ink}
          ac={accentOverlay ? theme.accent : theme.ink}
        />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    borderWidth: 1,
    alignSelf: 'flex-start',
  },
  overlay: {
    position: 'absolute',
  },
});
