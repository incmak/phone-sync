import React from 'react';
import { useLocalSearchParams } from 'expo-router';

import { NotificationDetailScreen } from '../../components/notification-detail/NotificationDetailScreen';

export default function NotificationDetailRoute() {
  const { detailId, sourceLaunchFailed } = useLocalSearchParams<{
    detailId?: string | string[];
    sourceLaunchFailed?: string | string[];
  }>();
  const opaqueDetailId = Array.isArray(detailId) ? detailId[0] : detailId;
  const launchFailed = Array.isArray(sourceLaunchFailed) ? sourceLaunchFailed[0] : sourceLaunchFailed;
  return (
    <NotificationDetailScreen
      detailId={opaqueDetailId ?? ''}
      sourceLaunchFailed={launchFailed === '1'}
    />
  );
}
