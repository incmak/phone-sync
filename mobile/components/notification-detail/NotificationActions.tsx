import React, { useEffect, useRef, useState } from 'react';
import { StyleSheet, Text, TextInput, View } from 'react-native';

import { useTheme } from '../Theme';
import { TwButton } from '../primitives/TwButton';
import type {
  MirrorActionInvocationResult,
  NotificationActionInvocationState,
  NotificationDetail,
  NotificationDetailAction,
} from '../../modules/twinotify-core/src/TwinotifyCoreModule';
import { NotificationActionStatus } from './NotificationActionStatus';

function utf8ByteLength(value: string): number {
  let bytes = 0;
  for (const character of value) {
    const codePoint = character.codePointAt(0) ?? 0;
    bytes += codePoint <= 0x7f ? 1 : codePoint <= 0x7ff ? 2 : codePoint <= 0xffff ? 3 : 4;
  }
  return bytes;
}

function immediateState(result: MirrorActionInvocationResult): NotificationActionInvocationState | null {
  if (result.status === 'queued') return 'PENDING';
  if (result.status === 'gone') return 'NOTIFICATION_GONE';
  if (result.status === 'failed') return 'FAILED';
  return null;
}

interface NotificationActionsProps {
  detail: NotificationDetail;
  queuedActionId: string | null;
  onQueued: (actionId: string) => void;
  onInvoke: (action: NotificationDetailAction, replyText: string | null) => Promise<MirrorActionInvocationResult>;
}

export function NotificationActions({ detail, queuedActionId, onQueued, onInvoke }: NotificationActionsProps) {
  const theme = useTheme();
  const [openReplyId, setOpenReplyId] = useState<string | null>(null);
  const [draft, setDraft] = useState('');
  const [validation, setValidation] = useState<string | null>(null);
  const [replyFocused, setReplyFocused] = useState(false);
  const [localStates, setLocalStates] = useState<Record<string, NotificationActionInvocationState | null>>({});
  const [localMessages, setLocalMessages] = useState<Record<string, string | null>>({});
  const replyRef = useRef<TextInput>(null);

  useEffect(() => {
    if (!openReplyId) return;
    const timer = setTimeout(() => replyRef.current?.focus(), 0);
    return () => clearTimeout(timer);
  }, [openReplyId]);

  const invoke = async (action: NotificationDetailAction, replyText: string | null) => {
    setLocalMessages((current) => ({ ...current, [action.actionId]: null }));
    const result = await onInvoke(action, replyText).catch(() => ({
      status: 'failed' as const,
      invocationId: null,
    }));
    const state = immediateState(result);
    if (state) setLocalStates((current) => ({ ...current, [action.actionId]: state }));
    if (result.status === 'queued') {
      setValidation(null);
      onQueued(action.actionId);
    } else if (result.status === 'locked') {
      if (action.reply) {
        setValidation('Unlock this phone to reply');
      } else {
        setLocalMessages((current) => ({ ...current, [action.actionId]: 'Unlock this phone to continue' }));
      }
    } else if (result.status === 'invalid_reply') {
      setValidation('Reply is too long');
    }
  };

  const submitReply = async (action: NotificationDetailAction) => {
    const reply = draft.trim();
    if (!reply) {
      setValidation('Write a reply first');
      replyRef.current?.focus();
      return;
    }
    if (utf8ByteLength(reply) > 4_096) {
      setValidation('Reply is too long');
      replyRef.current?.focus();
      return;
    }
    await invoke(action, reply);
  };

  return (
    <View style={styles.container}>
      {detail.actions.map((action) => {
        const state = action.invocationState
          ?? (queuedActionId === action.actionId ? 'PENDING' : localStates[action.actionId] ?? null);
        const pending = state === 'PENDING';
        const replyOpen = openReplyId === action.actionId;

        return (
          <View key={action.actionId} style={styles.actionGroup}>
            {action.reply && replyOpen ? (
              <>
                <Text style={[theme.type.title2, { color: theme.colors.onSurface, fontFamily: theme.fonts.uiSemi }]}>
                  {action.title}
                </Text>
                <TextInput
                  ref={replyRef}
                  accessibilityLabel="Reply"
                  accessibilityHint="Type the reply to send through your paired phone"
                  accessibilityState={{ disabled: pending }}
                  editable={!pending}
                  multiline
                  placeholder="Write a reply"
                  placeholderTextColor={theme.colors.onSurfaceVariant}
                  value={draft}
                  onChangeText={(value) => {
                    setDraft(value);
                    if (validation) setValidation(null);
                  }}
                  onFocus={() => setReplyFocused(true)}
                  onBlur={() => setReplyFocused(false)}
                  style={[
                    styles.replyInput,
                    theme.type.body,
                    {
                      backgroundColor: theme.colors.surfaceContainerHigh,
                      borderColor: validation
                        ? theme.colors.error
                        : replyFocused
                          ? theme.colors.primary
                          : theme.colors.outline,
                      borderRadius: theme.radius.md,
                      color: theme.colors.onSurface,
                      fontFamily: theme.fonts.ui,
                    },
                  ]}
                />
                {validation ? (
                  <Text
                    accessibilityLiveRegion="polite"
                    style={[theme.type.caption, { color: theme.colors.error, fontFamily: theme.fonts.ui }]}
                  >
                    {validation}
                  </Text>
                ) : null}
                <TwButton
                  fullWidth
                  loading={pending}
                  disabled={pending}
                  accessibilityLabel="Send reply"
                  accessibilityHint="Sends this reply through your paired phone"
                  onPress={() => { void submitReply(action); }}
                >
                  Send reply
                </TwButton>
              </>
            ) : (
              <TwButton
                fullWidth
                variant="secondary"
                disabled={pending}
                accessibilityLabel={action.title}
                accessibilityHint={action.reply ? 'Opens the reply field' : 'Runs this action on your paired phone'}
                onPress={() => {
                  if (action.reply) {
                    setOpenReplyId(action.actionId);
                    setDraft('');
                    setValidation(null);
                  } else {
                    void invoke(action, null);
                  }
                }}
              >
                {action.title}
              </TwButton>
            )}
            <NotificationActionStatus state={state} />
            {localMessages[action.actionId] ? (
              <Text
                accessibilityLiveRegion="polite"
                style={[theme.type.caption, { color: theme.sem.warn.foreground, fontFamily: theme.fonts.ui }]}
              >
                {localMessages[action.actionId]}
              </Text>
            ) : null}
          </View>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    gap: 28,
  },
  actionGroup: {
    gap: 10,
  },
  replyInput: {
    minHeight: 72,
    paddingHorizontal: 16,
    paddingVertical: 14,
    borderWidth: 1,
    textAlignVertical: 'top',
  },
});
