import React, { useState } from 'react';
import { View, Text, Pressable, ScrollView, StyleSheet } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { router } from 'expo-router';
import Svg, { Rect, Circle, Path } from 'react-native-svg';
import { useTheme, TwButton } from '../../components';
import { OnboardingState, Role } from '../../state/onboardingState';

function QrIcon({ color, size = 28 }: { color: string; size?: number }) {
  return (
    <Svg width={size} height={size} viewBox="0 0 28 28">
      <Rect x={2} y={2} width={11} height={11} rx={2} stroke={color} strokeWidth={1.5} fill="none" />
      <Rect x={4} y={4} width={7} height={7} rx={1} fill={color} opacity={0.6} />
      <Rect x={15} y={2} width={11} height={11} rx={2} stroke={color} strokeWidth={1.5} fill="none" />
      <Rect x={17} y={4} width={7} height={7} rx={1} fill={color} opacity={0.6} />
      <Rect x={2} y={15} width={11} height={11} rx={2} stroke={color} strokeWidth={1.5} fill="none" />
      <Rect x={4} y={17} width={7} height={7} rx={1} fill={color} opacity={0.6} />
      <Rect x={16} y={16} width={4} height={4} fill={color} />
      <Rect x={22} y={16} width={4} height={4} fill={color} opacity={0.6} />
      <Rect x={16} y={22} width={4} height={4} fill={color} opacity={0.6} />
      <Rect x={22} y={22} width={4} height={4} fill={color} />
    </Svg>
  );
}

function CameraIcon({ color, size = 28 }: { color: string; size?: number }) {
  return (
    <Svg width={size} height={size} viewBox="0 0 28 28">
      <Rect x={2} y={7} width={24} height={17} rx={3} stroke={color} strokeWidth={1.5} fill="none" />
      <Circle cx={14} cy={15} r={5} stroke={color} strokeWidth={1.5} fill="none" />
      <Circle cx={14} cy={15} r={2.5} fill={color} opacity={0.7} />
      <Path d="M9,7 L10.5,4 L17.5,4 L19,7" stroke={color} strokeWidth={1.5} strokeLinecap="round" fill="none" />
      <Circle cx={23} cy={10} r={1.5} fill={color} opacity={0.6} />
    </Svg>
  );
}

interface RoleCardProps {
  selected: boolean;
  onPress: () => void;
  icon: React.ReactNode;
  title: string;
  description: string;
  accent: string;
  border: string;
  fill: string;
  ink: string;
  ink3: string;
  uiSemi: string;
  ui: string;
  radius: number;
}

function RoleCard({
  selected, onPress, icon, title, description,
  accent, border, fill, ink, ink3, uiSemi, ui, radius,
}: RoleCardProps) {
  return (
    <Pressable
      onPress={onPress}
      accessibilityRole="radio"
      accessibilityState={{ checked: selected }}
      accessibilityLabel={`${title}. ${description}`}
      style={[
        cardStyles.card,
        {
          backgroundColor: fill,
          borderColor: selected ? accent : border,
          borderWidth: selected ? 2 : 1,
          borderRadius: radius,
        },
      ]}
    >
      <View style={cardStyles.iconWrap}>
        {icon}
      </View>
      <View style={cardStyles.text}>
        <Text style={{ fontSize: 16, fontWeight: '600', color: ink, fontFamily: uiSemi, marginBottom: 4 }}>
          {title}
        </Text>
        <Text style={{ fontSize: 14, color: ink3, fontFamily: ui, lineHeight: 20 }}>
          {description}
        </Text>
      </View>
      {selected && (
        <View style={[cardStyles.check, { backgroundColor: accent }]}>
          <Svg width={14} height={14} viewBox="0 0 14 14">
            <Path d="M2,7 L5.5,10.5 L12,4" stroke="#fff" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" fill="none" />
          </Svg>
        </View>
      )}
    </Pressable>
  );
}

const cardStyles = StyleSheet.create({
  card: { flexDirection: 'row', alignItems: 'center', padding: 16, gap: 14 },
  iconWrap: { width: 40, minHeight: 48, alignItems: 'center', justifyContent: 'center' },
  text: { flex: 1 },
  check: { width: 24, height: 24, borderRadius: 12, alignItems: 'center', justifyContent: 'center' },
});

export default function RoleScreen() {
  const theme = useTheme();
  const [role, setRole] = useState<Role | null>(null);

  async function handleContinue() {
    if (!role) return;
    await OnboardingState.setRole(role);
    router.push('/onboarding/connect');
  }

  const cardProps = {
    border: theme.border,
    fill: theme.fill,
    ink: theme.ink,
    ink3: theme.ink2,
    accent: theme.accent,
    uiSemi: theme.fonts.uiSemi,
    ui: theme.fonts.ui,
    radius: theme.radius.lg,
  };

  return (
    <SafeAreaView
      edges={['top', 'bottom']}
      style={[styles.safe, { backgroundColor: theme.bg }]}
    >
      <ScrollView contentContainerStyle={styles.container}>
        <View style={styles.header}>
          <Text style={[
            theme.type.display,
            { color: theme.ink, fontFamily: theme.fonts.uiBold, fontWeight: '700' },
          ]}>
            Which device{'\n'}is this?
          </Text>
          <Text style={[theme.type.body, styles.subtitle, { color: theme.ink3, fontFamily: theme.fonts.ui }]}>
            Choose the role for this phone. You can pair more devices later.
          </Text>
        </View>

        <View style={styles.cards}>
          <RoleCard
            {...cardProps}
            selected={role === 'A'}
            onPress={() => setRole('A')}
            icon={<QrIcon color={role === 'A' ? theme.accent : theme.ink3} />}
            title="This is my first phone"
            description="I'll generate a QR code to pair with my second device."
          />
          <RoleCard
            {...cardProps}
            selected={role === 'B'}
            onPress={() => setRole('B')}
            icon={<CameraIcon color={role === 'B' ? theme.accent : theme.ink3} />}
            title="I have a code already"
            description="I'll scan the QR on my first phone to link them."
          />
        </View>

        <View style={styles.footer}>
          <TwButton
            variant="primary"
            size="lg"
            fullWidth
            disabled={!role}
            onPress={handleContinue}
          >
            Continue
          </TwButton>
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1 },
  container: { flexGrow: 1, paddingHorizontal: 24, paddingTop: 20, paddingBottom: 8 },
  header: { marginBottom: 32 },
  subtitle: { marginTop: 10 },
  cards: { gap: 12, flex: 1 },
  footer: { paddingBottom: 8 },
});
