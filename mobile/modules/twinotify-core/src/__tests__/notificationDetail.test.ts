import TwinotifyCoreModule from '../TwinotifyCoreModule';
import { useTwinotifyCore } from '../../../../hooks/useTwinotifyCore';

const native = TwinotifyCoreModule as jest.Mocked<typeof TwinotifyCoreModule>;

describe('notification detail bridge', () => {
  it('forwards detail, invocation, and live launchability calls', async () => {
    const detail = { detailId: 'detail', state: 'ACTIVE' } as never;
    native.getNotificationDetail.mockResolvedValue(detail);
    native.invokeMirrorAction.mockResolvedValue({ status: 'queued', invocationId: 'invoke' });
    native.canLaunchSourceApp.mockResolvedValue(true);
    const bridge = useTwinotifyCore();

    await expect(bridge.getNotificationDetail('detail')).resolves.toBe(detail);
    await expect(bridge.invokeMirrorAction('detail', 'action', 'reply')).resolves.toEqual({
      status: 'queued', invocationId: 'invoke',
    });
    await expect(bridge.canLaunchSourceApp('com.example')).resolves.toBe(true);
    expect(native.invokeMirrorAction).toHaveBeenCalledWith('detail', 'action', 'reply');
  });
});
