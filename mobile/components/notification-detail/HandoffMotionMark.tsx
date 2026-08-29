import React, { useEffect } from 'react';
import { StyleSheet, View } from 'react-native';
import Animated, {
  Easing,
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withSequence,
  withTiming,
} from 'react-native-reanimated';

import { TwinotifyMark } from '../primitives/TwinotifyMark';

const MARK_SIZE = 40;
const MARK_HEIGHT = MARK_SIZE * 0.88;

export function HandoffMotionMark({ queuedToken }: { queuedToken: string | null }) {
  const progress = useSharedValue(0);
  const reduceMotion = useReducedMotion();

  useEffect(() => {
    if (!queuedToken || reduceMotion) return;
    progress.value = 0;
    progress.value = withSequence(
      withTiming(1, { duration: 90, easing: Easing.out(Easing.cubic) }),
      withTiming(0, { duration: 90, easing: Easing.out(Easing.cubic) }),
    );
  }, [progress, queuedToken, reduceMotion]);

  const leftStyle = useAnimatedStyle(() => ({
    transform: [{ translateX: progress.value * 2 }],
  }));
  const rightStyle = useAnimatedStyle(() => ({
    transform: [{ translateX: progress.value * -2 }],
  }));

  return (
    <View
      testID="handoff-motion-mark"
      accessible={false}
      importantForAccessibility="no"
      style={styles.container}
    >
      <Animated.View style={[styles.half, styles.left, leftStyle]}>
        <TwinotifyMark size={MARK_SIZE} />
      </Animated.View>
      <Animated.View style={[styles.half, styles.right, rightStyle]}>
        <View style={styles.rightMark}>
          <TwinotifyMark size={MARK_SIZE} />
        </View>
      </Animated.View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    width: MARK_SIZE,
    height: MARK_HEIGHT,
  },
  half: {
    position: 'absolute',
    top: 0,
    width: MARK_SIZE / 2,
    height: MARK_HEIGHT,
    overflow: 'hidden',
  },
  left: {
    left: 0,
  },
  right: {
    right: 0,
  },
  rightMark: {
    marginLeft: -(MARK_SIZE / 2),
  },
});
