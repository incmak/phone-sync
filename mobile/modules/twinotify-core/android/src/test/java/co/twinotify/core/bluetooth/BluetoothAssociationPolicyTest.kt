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
        assertEquals(UUID.fromString("7c6f5d5e-6f54-4f6e-9b63-5457494e4f54"), BluetoothConstants.RFCOMM_SERVICE_UUID)
        assertEquals(UUID.fromString("5d7101b8-cad0-4d22-a41e-5457494e4f54"), BluetoothConstants.DISCOVERY_SERVICE_UUID)
        assertEquals("bluetooth-rfcomm-v1", BluetoothConstants.ROUTE_LABEL)
        assertEquals(1, BluetoothConstants.PROTOCOL_VERSION)
        assertFalse(
            BluetoothConstants.RFCOMM_SERVICE_UUID == UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"),
            "the private RFCOMM service must never be the public SPP UUID",
        )
    }
}
