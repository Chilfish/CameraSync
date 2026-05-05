package dev.sebastiano.camerasync.vendors.nikon

import com.juul.kable.Peripheral
import com.juul.khronicle.Log
import dev.sebastiano.camerasync.domain.model.Camera
import dev.sebastiano.camerasync.domain.vendor.DefaultConnectionDelegate
import kotlin.uuid.ExperimentalUuidApi

private const val TAG = "NikonConnectionDelegate"

/**
 * Nikon SnapBridge connection delegate.
 *
 * Nikon SnapBridge requires an authentication handshake before GPS/time sync operations are
 * permitted:
 * 1. BLE bonding (standard Android OS level)
 * 2. SnapBridge auth exchange (app level) — camera displays 8-digit verification code in the
 *    official SnapBridge app; user enters code to complete pairing
 * 3. After auth: GPS sync, time sync, and image transfer are available
 *
 * **POC Status**: For the initial proof-of-concept, this delegate extends
 * [DefaultConnectionDelegate] and delegates sync to it. GPS/time sync will throw
 * [UnsupportedOperationException] until the actual SnapBridge UUIDs are populated in
 * [NikonGattSpec].
 *
 * The authentication handshake will be implemented after BLE packet capture confirms the exact
 * UUIDs and command format.
 */
@OptIn(ExperimentalUuidApi::class)
class NikonConnectionDelegate : DefaultConnectionDelegate() {

    private var snapBridgeAuthenticated = false

    override suspend fun onConnected(peripheral: Peripheral, camera: Camera) {
        Log.info(tag = TAG) { "Nikon camera connected: ${camera.name} (${camera.macAddress})" }
        // TODO: Implement SnapBridge authentication handshake:
        //  1. Subscribe to SnapBridge notification characteristic
        //  2. Write auth initiation command
        //  3. Wait for camera challenge response
        //  4. Camera displays 8-digit code → user enters in app
        //  5. Send confirmation to camera
        //  6. Camera confirms auth → set snapBridgeAuthenticated = true
    }

    override suspend fun syncLocation(
        peripheral: Peripheral,
        camera: Camera,
        location: dev.sebastiano.camerasync.domain.model.GpsLocation,
    ) {
        if (NikonGattSpec.SnapBridge.GPS_CHARACTERISTIC_UUID == null) {
            throw UnsupportedOperationException(
                "SnapBridge GPS characteristic UUID not yet reverse-engineered. " +
                    "BLE service discovery after bonding is needed to identify the GPS write " +
                    "characteristic within service ${NikonGattSpec.SNAPBRIDGE_SERVICE_UUID}."
            )
        }
        super.syncLocation(peripheral, camera, location)
    }

    override suspend fun syncDateTime(
        peripheral: Peripheral,
        camera: Camera,
        dateTime: java.time.ZonedDateTime,
    ) {
        if (NikonGattSpec.SnapBridge.DATE_TIME_CHARACTERISTIC_UUID == null) {
            throw UnsupportedOperationException(
                "SnapBridge date/time characteristic UUID not yet reverse-engineered. " +
                    "BLE service discovery after bonding is needed to identify the time " +
                    "characteristic within service ${NikonGattSpec.SNAPBRIDGE_SERVICE_UUID}."
            )
        }
        super.syncDateTime(peripheral, camera, dateTime)
    }

    override suspend fun onDisconnecting(peripheral: Peripheral) {
        Log.debug(tag = TAG) { "Disconnecting Nikon camera" }
        snapBridgeAuthenticated = false
    }
}
