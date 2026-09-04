package co.twinotify.core.bluetooth

import android.Manifest
import android.bluetooth.BluetoothDevice
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BluetoothAssociationPolicyTest {
    @Test
    fun bluetoothPermissionSetIsNearbyOnly() {
        assertEquals(
            setOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            ),
            BluetoothAssociationPolicy.RUNTIME_PERMISSIONS,
        )
        assertFalse(BluetoothAssociationPolicy.RUNTIME_PERMISSIONS.contains(Manifest.permission.ACCESS_FINE_LOCATION))
        assertFalse(BluetoothAssociationPolicy.RUNTIME_PERMISSIONS.contains(Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    @Test
    fun associationFlowIsBoundedToTwoMinutes() {
        assertEquals(120_000L, BluetoothAssociationPolicy.ASSOCIATION_TIMEOUT_MILLIS)
    }

    @Test
    fun theAdvertisementOutlivesThePickerAndTheConnectThatFollowsIt() {
        // The stack gives every new advertising set a fresh resolvable private address, so an
        // advertisement stopped at selection and restarted for the connect would leave the peer
        // dialling an address nobody answers on. One advertisement has to cover both phases.
        assertEquals(
            BluetoothAssociationPolicy.ASSOCIATION_TIMEOUT_MILLIS + BluetoothConnector.RENDEZVOUS_WINDOW_MILLIS,
            BluetoothAssociationPolicy.ADVERTISEMENT_TIMEOUT_MILLIS,
        )
        assertTrue(
            BluetoothAssociationPolicy.ADVERTISEMENT_TIMEOUT_MILLIS <= 180_000L,
            "AdvertiseSettings rejects a timeout above 180 s",
        )
    }

    @Test
    fun onlyDualModeDevicesAreAccepted() {
        // An LE-discovered peer reports LE or unknown, never DUAL, so a DUAL-only rule refused
        // every real phone. Only the absence of a device is a rejection here.
        assertTrue(BluetoothAssociationPolicy.acceptsSelectedDeviceType(BluetoothDevice.DEVICE_TYPE_DUAL))
        assertTrue(BluetoothAssociationPolicy.acceptsSelectedDeviceType(BluetoothDevice.DEVICE_TYPE_LE))
        assertTrue(BluetoothAssociationPolicy.acceptsSelectedDeviceType(BluetoothDevice.DEVICE_TYPE_CLASSIC))
        assertTrue(BluetoothAssociationPolicy.acceptsSelectedDeviceType(BluetoothDevice.DEVICE_TYPE_UNKNOWN))
        assertFalse(BluetoothAssociationPolicy.acceptsSelectedDeviceType(null))
    }

    @Test
    fun routeEnablementRequiresPermissionsConfirmedPeerAndValidatedBinding() {
        assertTrue(
            BluetoothAssociationPolicy.canEnableRoute(permissionsGranted = true, peerConfirmed = true, bindingValidated = true),
        )
        assertFalse(
            BluetoothAssociationPolicy.canEnableRoute(permissionsGranted = false, peerConfirmed = true, bindingValidated = true),
        )
        assertFalse(
            BluetoothAssociationPolicy.canEnableRoute(permissionsGranted = true, peerConfirmed = false, bindingValidated = true),
        )
        assertFalse(
            BluetoothAssociationPolicy.canEnableRoute(permissionsGranted = true, peerConfirmed = true, bindingValidated = false),
        )
    }

    @Test
    fun foregroundTypeRequiresDurableEnablementAndEveryPermission() {
        assertTrue(BluetoothAssociationPolicy.foregroundRouteActive(routeEnabled = true, permissionsGranted = true))
        assertFalse(BluetoothAssociationPolicy.foregroundRouteActive(routeEnabled = true, permissionsGranted = false))
        assertFalse(BluetoothAssociationPolicy.foregroundRouteActive(routeEnabled = false, permissionsGranted = true))
    }

    @Test
    fun constantsMatchTheRouteContract() {
        assertEquals(UUID.fromString("5d7101b8-cad0-4d22-a41e-5457494e4f54"), BluetoothConstants.DISCOVERY_SERVICE_UUID)
        // The transport is an LE L2CAP connection-oriented channel, and the label says so: a
        // Classic RFCOMM socket cannot reach the resolvable private address the picker returns.
        assertEquals("bluetooth-l2cap-v1", BluetoothConstants.ROUTE_LABEL)
        assertEquals(1, BluetoothConstants.PROTOCOL_VERSION)
    }
}
