import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  BackHandler,
  Keyboard,
  KeyboardAvoidingView,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
  useWindowDimensions,
} from 'react-native';
import { router, useFocusEffect } from 'expo-router';
import { SafeAreaView } from 'react-native-safe-area-context';

import { useTheme } from '../Theme';
import { TwButton } from '../primitives/TwButton';
import { TwIcon } from '../primitives/TwIcon';
import { TwSpinner } from '../primitives/TwSpinner';
import TwinotifyCoreModule, {
  type NotificationDetail,
  type NotificationDetailAction,
} from '../../modules/twinotify-core/src/TwinotifyCoreModule';
import { HandoffMotionMark } from './HandoffMotionMark';
import { NotificationActions } from './NotificationActions';
import { NotificationBody } from './NotificationBody';
import { NotificationSourceHeader } from './NotificationSourceHeader';

type LoadState = 'loading' | 'ready' | 'missing' | 'error';

function appName(detail: NotificationDetail): string {
  return detail.sourceAppName?.trim() || detail.sourcePackage;
}

export function NotificationDetailScreen({
  detailId,
  sourceLaunchFailed = false,
}: {
  detailId: string;
  sourceLaunchFailed?: boolean;
}) {
  const theme = useTheme();
  const { width, fontScale } = useWindowDimensions();
  const [loadState, setLoadState] = useState<LoadState>('loading');
  const [detail, setDetail] = useState<NotificationDetail | null>(null);
  const [sourceLaunchable, setSourceLaunchable] = useState(false);
  const [queuedActionId, setQueuedActionId] = useState<string | null>(null);
  const [focused, setFocused] = useState(false);
  const detailRef = useRef<NotificationDetail | null>(null);
  const queuedActionIdRef = useRef<string | null>(null);
  const queuedAt = useRef<number | null>(null);
  const pendingPollStartedAt = useRef<number | null>(null);
  const gutter = width <= 360 ? 16 : 24;
  const stackedHeader = width <= 360 || fontScale >= 1.5;

  const goBack = useCallback(() => {
    Keyboard.dismiss();
    router.back();
  }, []);

  const load = useCallback(async (showLoading: boolean) => {
    if (!detailId) {
      setDetail(null);
      setLoadState('missing');
      return;
    }
    if (showLoading) setLoadState('loading');
    try {
      const next = await TwinotifyCoreModule.getNotificationDetail(detailId);
      const canLaunch = next && !sourceLaunchFailed
        ? await TwinotifyCoreModule.canLaunchSourceApp(next.sourcePackage).catch(() => false)
        : false;
      detailRef.current = next;
      setDetail(next);
      setSourceLaunchable(canLaunch);
      setLoadState(next ? 'ready' : 'missing');
      if (!next || next.state !== 'ACTIVE') {
        Keyboard.dismiss();
        queuedActionIdRef.current = null;
        setQueuedActionId(null);
        queuedAt.current = null;
      } else if (queuedActionIdRef.current) {
        const matching = next.actions.find((action) => action.actionId === queuedActionIdRef.current);
        if (matching?.invocationState && matching.invocationState !== 'PENDING') {
          queuedActionIdRef.current = null;
          setQueuedActionId(null);
          queuedAt.current = null;
        }
      }
    } catch {
      if (showLoading || !detailRef.current) setLoadState('error');
    }
  }, [detailId, sourceLaunchFailed]);

  useFocusEffect(useCallback(() => {
    setFocused(true);
    void load(detailRef.current === null);
    return () => { setFocused(false); };
  }, [load]));

  useEffect(() => {
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => {
      goBack();
      return true;
    });
    return () => subscription.remove();
  }, [goBack]);

  const hasPending = Boolean(
    queuedActionId || detail?.actions.some((action) => action.invocationState === 'PENDING'),
  );

  useEffect(() => {
    if (!focused || !hasPending || detail?.state !== 'ACTIVE') return;
    pendingPollStartedAt.current = Date.now();
    const interval = setInterval(() => {
      const startedAt = queuedAt.current ?? pendingPollStartedAt.current;
      if (startedAt !== null && Date.now() - startedAt >= 120_000) {
        setQueuedActionId(null);
        queuedAt.current = null;
        pendingPollStartedAt.current = null;
        clearInterval(interval);
        return;
      }
      void load(false);
    }, 1_000);
    return () => {
      clearInterval(interval);
      pendingPollStartedAt.current = null;
    };
  }, [detail?.state, focused, hasPending, load]);

  const invoke = useCallback(async (action: NotificationDetailAction, replyText: string | null) => {
    const result = await TwinotifyCoreModule.invokeMirrorAction(detailId, action.actionId, replyText);
    if (result.status === 'queued') {
      queuedAt.current = Date.now();
      queuedActionIdRef.current = action.actionId;
      setQueuedActionId(action.actionId);
      await load(false);
    }
    return result;
  }, [detailId, load]);

  const openSourceApp = useCallback(async () => {
    const opened = await TwinotifyCoreModule.openNotificationSourceApp(detailId).catch(() => false);
    if (!opened) setSourceLaunchable(false);
  }, [detailId]);

  return (
    <SafeAreaView edges={['top', 'bottom']} style={[styles.safe, { backgroundColor: theme.colors.surface }]}>
      <KeyboardAvoidingView
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
        style={styles.safe}
      >
        <View style={styles.appBar}>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="Back"
            accessibilityHint="Returns to the previous screen"
            onPress={goBack}
            style={({ pressed }) => [
              styles.back,
              { backgroundColor: pressed ? theme.colors.surfaceContainerHigh : 'transparent' },
            ]}
          >
            <TwIcon name="chevronLeft" size={24} color={theme.colors.onSurface} strokeWidth={2} />
          </Pressable>
          <Text style={[theme.type.title2, styles.appBarTitle, { color: theme.colors.onSurface, fontFamily: theme.fonts.uiSemi }]}>
            Notification
          </Text>
          <View style={styles.markSlot}>
            <HandoffMotionMark queuedToken={queuedActionId} />
          </View>
        </View>

        <ScrollView
          keyboardShouldPersistTaps="handled"
          showsVerticalScrollIndicator={false}
          contentContainerStyle={[styles.content, { paddingHorizontal: gutter }]}
          testID="notification-detail-content"
        >
          {loadState === 'loading' ? (
            <View style={styles.centerState} accessibilityLiveRegion="polite">
              <TwSpinner size={28} />
              <Text style={[theme.type.body, { color: theme.colors.onSurfaceVariant, fontFamily: theme.fonts.ui }]}>
                Loading notification
              </Text>
            </View>
          ) : null}

          {loadState === 'missing' ? (
            <View style={styles.stateCopy}>
              <Text style={[theme.type.title1, { color: theme.colors.onSurface, fontFamily: theme.fonts.uiSemi }]}>
                This notification is no longer available
              </Text>
              <Text style={[theme.type.body, { color: theme.colors.onSurfaceVariant, fontFamily: theme.fonts.ui }]}>
                Twinotify keeps dismissed notification details briefly, then removes them from this phone.
              </Text>
              <TwButton fullWidth variant="secondary" onPress={goBack}>Back</TwButton>
            </View>
          ) : null}

          {loadState === 'error' ? (
            <View style={styles.stateCopy} accessibilityLiveRegion="polite">
              <Text style={[theme.type.title1, { color: theme.colors.onSurface, fontFamily: theme.fonts.uiSemi }]}>
                Couldn’t load this notification
              </Text>
              <Text style={[theme.type.body, { color: theme.colors.onSurfaceVariant, fontFamily: theme.fonts.ui }]}>
                Try again. Your notification content stays on your paired phones.
              </Text>
              <TwButton fullWidth onPress={() => { void load(true); }}>Try again</TwButton>
              <TwButton fullWidth variant="ghost" onPress={goBack}>Back</TwButton>
            </View>
          ) : null}

          {loadState === 'ready' && detail ? (
            <>
              <NotificationSourceHeader detail={detail} stacked={stackedHeader} />
              <NotificationBody detail={detail} />
              {detail.state === 'ACTIVE' && detail.actions.length > 0 ? (
                <NotificationActions
                  detail={detail}
                  queuedActionId={queuedActionId}
                  onQueued={(actionId) => {
                    queuedActionIdRef.current = actionId;
                    setQueuedActionId(actionId);
                  }}
                  onInvoke={invoke}
                />
              ) : null}
              {sourceLaunchable ? (
                <TwButton
                  fullWidth
                  variant="ghost"
                  accessibilityLabel={`Open ${appName(detail)}`}
                  accessibilityHint="Opens the source app on this phone"
                  onPress={() => { void openSourceApp(); }}
                >
                  {`Open ${appName(detail)}`}
                </TwButton>
              ) : null}
            </>
          ) : null}
        </ScrollView>
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: {
    flex: 1,
  },
  appBar: {
    minHeight: 64,
    paddingHorizontal: 8,
    flexDirection: 'row',
    alignItems: 'center',
  },
  back: {
    minWidth: 48,
    minHeight: 48,
    borderRadius: 24,
    alignItems: 'center',
    justifyContent: 'center',
  },
  appBarTitle: {
    flex: 1,
    marginHorizontal: 8,
  },
  markSlot: {
    width: 48,
    minHeight: 48,
    alignItems: 'center',
    justifyContent: 'center',
  },
  content: {
    flexGrow: 1,
    paddingTop: 20,
    paddingBottom: 40,
    gap: 32,
  },
  centerState: {
    flex: 1,
    minHeight: 240,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 16,
  },
  stateCopy: {
    flex: 1,
    justifyContent: 'center',
    gap: 20,
    paddingVertical: 48,
  },
});
