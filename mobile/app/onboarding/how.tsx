import React, { useState } from 'react';
import { View, Text, Pressable, StyleSheet } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { router } from 'expo-router';
import Svg, { Rect, Circle, Path, Line } from 'react-native-svg';
import { useTheme, TwButton } from '../../components';

// --- Slide art components ---

function QrSlideArt({ accent, border, ink }: { accent: string; border: string; ink: string }) {
  return (
    <Svg width={160} height={160} viewBox="0 0 160 160">
      {/* QR outer frame */}
      <Rect x={30} y={30} width={100} height={100} rx={8} stroke={border} strokeWidth={2} fill="none" />
      {/* Top-left finder */}
      <Rect x={40} y={40} width={28} height={28} rx={3} stroke={ink} strokeWidth={1.5} fill="none" />
      <Rect x={46} y={46} width={16} height={16} rx={2} fill={ink} opacity={0.7} />
      {/* Top-right finder */}
      <Rect x={92} y={40} width={28} height={28} rx={3} stroke={ink} strokeWidth={1.5} fill="none" />
      <Rect x={98} y={46} width={16} height={16} rx={2} fill={ink} opacity={0.7} />
      {/* Bottom-left finder */}
      <Rect x={40} y={92} width={28} height={28} rx={3} stroke={ink} strokeWidth={1.5} fill="none" />
      <Rect x={46} y={98} width={16} height={16} rx={2} fill={ink} opacity={0.7} />
      {/* Data dots */}
      <Rect x={92} y={92} width={6} height={6} fill={accent} />
      <Rect x={102} y={92} width={6} height={6} fill={accent} opacity={0.6} />
      <Rect x={112} y={92} width={6} height={6} fill={accent} />
      <Rect x={92} y={102} width={6} height={6} fill={accent} opacity={0.6} />
      <Rect x={102} y={102} width={6} height={6} fill={accent} />
      <Rect x={112} y={102} width={6} height={6} fill={accent} opacity={0.6} />
      <Rect x={92} y={112} width={6} height={6} fill={accent} />
      <Rect x={102} y={112} width={6} height={6} fill={accent} opacity={0.6} />
      <Rect x={112} y={112} width={6} height={6} fill={accent} />
    </Svg>
  );
}

function MirrorSlideArt({ accent, border, ink }: { accent: string; border: string; ink: string }) {
  return (
    <Svg width={160} height={160} viewBox="0 0 160 160">
      {/* Left phone */}
      <Rect x={20} y={40} width={44} height={80} rx={8} stroke={ink} strokeWidth={1.5} fill="none" />
      <Rect x={27} y={52} width={30} height={40} rx={2} fill={border} />
      <Circle cx={42} cy={108} r={4} fill={border} />
      {/* Right phone */}
      <Rect x={96} y={40} width={44} height={80} rx={8} stroke={ink} strokeWidth={1.5} fill="none" />
      <Rect x={103} y={52} width={30} height={40} rx={2} fill={border} />
      <Circle cx={118} cy={108} r={4} fill={border} />
      {/* Notification lines on left */}
      <Rect x={30} y={56} width={20} height={3} rx={1.5} fill={accent} />
      <Rect x={30} y={63} width={14} height={3} rx={1.5} fill={accent} opacity={0.5} />
      {/* Arrow */}
      <Line x1={68} y1={80} x2={90} y2={80} stroke={accent} strokeWidth={2} strokeLinecap="round" />
      <Path d="M85,75 L91,80 L85,85" stroke={accent} strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" fill="none" />
      {/* Notification lines on right (mirrored) */}
      <Rect x={106} y={56} width={20} height={3} rx={1.5} fill={accent} />
      <Rect x={106} y={63} width={14} height={3} rx={1.5} fill={accent} opacity={0.5} />
    </Svg>
  );
}

function ShieldSlideArt({ accent, border, ink }: { accent: string; border: string; ink: string }) {
  return (
    <Svg width={160} height={160} viewBox="0 0 160 160">
      {/* Shield path */}
      <Path
        d="M80,28 L116,44 L116,88 Q116,116 80,132 Q44,116 44,88 L44,44 Z"
        stroke={ink}
        strokeWidth={1.5}
        fill="none"
      />
      <Path
        d="M80,38 L108,52 L108,88 Q108,110 80,122 Q52,110 52,88 L52,52 Z"
        fill={accent}
        opacity={0.12}
      />
      {/* Lock icon */}
      <Rect x={68} y={76} width={24} height={18} rx={3} fill={accent} opacity={0.8} />
      <Path
        d="M72,76 L72,70 Q72,64 80,64 Q88,64 88,70 L88,76"
        stroke={accent}
        strokeWidth={2}
        fill="none"
        strokeLinecap="round"
      />
      <Circle cx={80} cy={84} r={3} fill={border} />
    </Svg>
  );
}

// --- Dot indicator ---

function Dots({ count, active, accent, border }: { count: number; active: number; accent: string; border: string }) {
  return (
    <View style={dotStyles.row}>
      {Array.from({ length: count }).map((_, i) => (
        <View
          key={i}
          style={[
            dotStyles.dot,
            {
              width: i === active ? 24 : 8,
              backgroundColor: i === active ? accent : border,
            },
          ]}
        />
      ))}
    </View>
  );
}

const dotStyles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  dot: { height: 8, borderRadius: 4 },
});

// --- Slide data ---

interface Slide {
  title: string;
  body: string;
}

const SLIDES: Slide[] = [
  {
    title: 'Pair once with a QR',
    body: 'Scan a code to link your two devices. No accounts, no email — just a one-time local handshake.',
  },
  {
    title: 'Mirror every notification',
    body: 'Every alert from phone A appears silently on phone B in real time, even when your screen is off.',
  },
  {
    title: 'Private by default',
    body: 'All data is encrypted on-device before it leaves. Twinotify never sees your notifications.',
  },
];

export default function HowScreen() {
  const theme = useTheme();
  const [step, setStep] = useState(0);

  const artProps = { accent: theme.accent, border: theme.border, ink: theme.ink };
  const arts = [
    <QrSlideArt key="qr" {...artProps} />,
    <MirrorSlideArt key="mirror" {...artProps} />,
    <ShieldSlideArt key="shield" {...artProps} />,
  ];

  const isLast = step === SLIDES.length - 1;

  function handleNext() {
    if (isLast) {
      router.push('/onboarding/role');
    } else {
      setStep((s) => s + 1);
    }
  }

  return (
    <SafeAreaView
      edges={['top', 'bottom']}
      style={[styles.safe, { backgroundColor: theme.bg }]}
    >
      <View style={styles.container}>
        <View style={styles.hero}>{arts[step]}</View>

        <View style={styles.copy}>
          <Text
            style={[
              theme.type.title1,
              styles.headline,
              { color: theme.ink, fontFamily: theme.fonts.uiSemi },
            ]}
          >
            {SLIDES[step].title}
          </Text>
          <Text
            style={[
              theme.type.body,
              { color: theme.ink3, fontFamily: theme.fonts.ui },
            ]}
          >
            {SLIDES[step].body}
          </Text>
        </View>

        <View style={styles.footer}>
          <Dots count={SLIDES.length} active={step} accent={theme.accent} border={theme.border} />
          <View style={styles.navRow}>
            {step > 0 && (
              <Pressable onPress={() => setStep((s) => s - 1)} style={styles.backBtn}>
                <Text
                  style={[
                    theme.type.bodyMed,
                    { color: theme.ink3, fontFamily: theme.fonts.uiMedium },
                  ]}
                >
                  Back
                </Text>
              </Pressable>
            )}
            <View style={styles.nextBtnWrap}>
              <TwButton
                variant="primary"
                size="md"
                fullWidth
                onPress={handleNext}
              >
                {isLast ? 'Continue' : 'Next'}
              </TwButton>
            </View>
          </View>
        </View>
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  container: { flex: 1, paddingHorizontal: 24, paddingTop: 16, paddingBottom: 8 },
  hero: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  copy: { marginBottom: 32 },
  headline: { marginBottom: 10 },
  footer: { gap: 20, paddingBottom: 8 },
  navRow: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  backBtn: { paddingVertical: 14, paddingHorizontal: 4 },
  nextBtnWrap: { flex: 1 },
});
