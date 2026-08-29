import React from 'react';
import { useLocalSearchParams } from 'expo-router';

import { NotificationDetailScreen } from '../../components/notification-detail/NotificationDetailScreen';

export default function NotificationDetailRoute() {
  const { detailId } = useLocalSearchParams<{ detailId?: string | string[] }>();
  const opaqueDetailId = Array.isArray(detailId) ? detailId[0] : detailId;
  return <NotificationDetailScreen detailId={opaqueDetailId ?? ''} />;
}
