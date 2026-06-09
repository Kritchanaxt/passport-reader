package com.tananaev.passportreader

import android.content.Context
import android.os.Bundle
import android.os.RemoteException
import com.tananaev.passportreader.AppLog as Log
import com.sunmi.pay.hardware.aidl.AidlConstants
import com.sunmi.pay.hardware.aidlv2.readcard.CheckCardCallbackV2
import com.sunmi.pay.hardware.aidlv2.readcard.ReadCardOptV2
import sunmi.paylib.SunmiPayKernel

/**
 * Manages the Sunmi Pay SDK v2.0.41 lifecycle and NFC card detection.
 *
 * Sunmi payment terminals (P1, P2, P3, etc.) disable the standard Android
 * NfcAdapter. Instead, NFC communication must go through the Sunmi Pay SDK
 * (PayLib) AIDL service. This manager:
 *
 * 1. Binds to the PaySDK service via [SunmiPayKernel.initPaySDK]
 * 2. Exposes [ReadCardOptV2] for NFC card polling and APDU exchange
 * 3. Provides [startNfcPolling] to detect contactless cards (passports)
 */
object SunmiPaySdkManager {
    private const val TAG = "SunmiPaySdkManager"

    // ──── State ────
    var isConnected = false
        private set

    var readCardOpt: ReadCardOptV2? = null
        private set

    var initStatus = "Idle"
        private set

    var initError: String? = null
        private set

    private var onConnectedCallback: ((ReadCardOptV2) -> Unit)? = null

    var onDisconnectedCallback: (() -> Unit)? = null

    // ──── Callback Chaining ────

    /**
     * Chain an additional callback to be invoked when the SDK connects.
     * Safe to call even when SDK is already connected — the callback fires immediately.
     * This fixes the race condition where onResume runs while SDK is still "Initializing".
     */
    fun chainOnConnectedCallback(callback: (ReadCardOptV2) -> Unit) {
        if (isConnected && readCardOpt != null) {
            // Already connected — fire immediately
            callback(readCardOpt!!)
            return
        }
        val existing = onConnectedCallback
        onConnectedCallback = { opt ->
            existing?.invoke(opt)
            callback(opt)
        }
    }

    // ──── Initialise / Destroy ────

    /**
     * Bind to the Sunmi PaySDK service.
     *
     * @param context        Application or Activity context
     * @param onConnected    Called (once) when the SDK service is connected and
     *                       [ReadCardOptV2] is ready.
     */
    fun init(context: Context, onConnected: ((ReadCardOptV2) -> Unit)? = null) {
        if (isConnected) {
            Log.i(TAG, "PaySDK already connected ✓")
            readCardOpt?.let { onConnected?.invoke(it) }
            return
        }
        if (initStatus == "Initializing") {
            Log.i(TAG, "PaySDK is already initializing, chaining callbacks.")
            onConnected?.let { newCallback ->
                val existing = onConnectedCallback
                onConnectedCallback = { opt ->
                    existing?.invoke(opt)
                    newCallback(opt)
                }
            }
            return
        }

        initStatus = "Initializing"
        initError = null
        onConnectedCallback = onConnected

        try {
            val kernel = SunmiPayKernel.getInstance()
            val bound = kernel.initPaySDK(context.applicationContext, object : SunmiPayKernel.ConnectCallback {
                override fun onConnectPaySDK() {
                    Log.i(TAG, "PaySDK connected ✓")
                    val opt = kernel.mReadCardOptV2
                    if (opt != null) {
                        readCardOpt = opt
                        isConnected = true
                        initStatus = "Connected"
                        onConnectedCallback?.invoke(opt)
                    } else {
                        val err = "PaySDK connected but ReadCardOptV2 is null (NFC card reader not supported on this device)"
                        Log.e(TAG, err)
                        readCardOpt = null
                        isConnected = false
                        initStatus = "No Card Reader"
                        initError = err
                    }
                }

                override fun onDisconnectPaySDK() {
                    Log.w(TAG, "PaySDK disconnected")
                    readCardOpt = null
                    isConnected = false
                    initStatus = "Disconnected"
                    onDisconnectedCallback?.invoke()
                }
            })

            if (!bound) {
                val err = "PaySDK service not found — is com.sunmi.pay.hardware_v3 installed?"
                Log.e(TAG, err)
                initStatus = "Service Not Found"
                initError = err
            }
        } catch (e: Exception) {
            val err = "Failed to initialise PaySDK: ${e.message}"
            Log.e(TAG, err, e)
            initStatus = "Init Failed"
            initError = err
        }
    }

    fun destroy() {
        try {
            cancelNfcPolling()
            SunmiPayKernel.getInstance().destroyPaySDK()
            readCardOpt = null
            isConnected = false
            initStatus = "Destroyed"
            Log.i(TAG, "PaySDK destroyed")
        } catch (e: Exception) {
            Log.w(TAG, "Error destroying PaySDK: ${e.message}")
        }
    }

    // ──── NFC Card Polling ────

    /**
     * Start polling for a contactless (NFC) card.
     *
     * When a card is detected the [onCardFound] callback fires with the card's UUID.
     * On error [onError] fires.
     *
     * Internally this calls [ReadCardOptV2.checkCard] with the NFC card-type flag.
     *
     * @param timeoutSec  Detection timeout in seconds (1–120, default 120)
     * @param onCardFound Called when an NFC card is tapped — receives the UUID string
     * @param onError     Called on detection error — receives (errorCode, message)
     */
    fun startNfcPolling(
        timeoutSec: Int = 120,
        onCardFound: (uuid: String) -> Unit,
        onError: (code: Int, message: String) -> Unit,
    ) {
        cancelNfcPolling()
        val opt = readCardOpt
        if (opt == null) {
            val msg = "ReadCardOptV2 not available — SDK not connected"
            Log.e(TAG, msg)
            onError(-1, msg)
            return
        }

        try {
            val cardType = AidlConstants.CardType.NFC.value
            Log.i(TAG, "Starting NFC polling (timeout=${timeoutSec}s, cardType=$cardType)")

            opt.checkCard(cardType, object : CheckCardCallbackV2.Stub() {
                @Throws(RemoteException::class)
                override fun findMagCard(info: Bundle?) {
                    Log.d(TAG, "findMagCard (ignored for passport)")
                }

                @Throws(RemoteException::class)
                override fun findICCard(atr: String?) {
                    Log.d(TAG, "findICCard atr=$atr (ignored for passport)")
                }

                @Throws(RemoteException::class)
                override fun findRFCard(uuid: String?) {
                    Log.i(TAG, "findRFCard uuid=$uuid")
                    uuid?.let { onCardFound(it) }
                }

                @Throws(RemoteException::class)
                override fun onError(code: Int, message: String?) {
                    Log.e(TAG, "checkCard error: code=$code, msg=$message")
                    onError(code, message ?: "Unknown error")
                }

                @Throws(RemoteException::class)
                override fun findICCardEx(info: Bundle?) {
                    Log.d(TAG, "findICCardEx (ignored)")
                }

                @Throws(RemoteException::class)
                override fun findRFCardEx(info: Bundle?) {
                    val uuid = info?.getString("uuid")
                    val ats = info?.getString("ats")
                    val sak = info?.getInt("sak", -1)
                    val cardCategory = info?.getInt("cardCategory", -1)
                    Log.i(TAG, "findRFCardEx uuid=$uuid, ats=$ats, sak=$sak, cardCategory=$cardCategory")
                    uuid?.let { onCardFound(it) }
                }

                @Throws(RemoteException::class)
                override fun onErrorEx(info: Bundle?) {
                    val code = info?.getInt("code") ?: -1
                    val message = info?.getString("message") ?: "Unknown error"
                    Log.e(TAG, "checkCard errorEx: code=$code, msg=$message")
                    onError(code, message)
                }
            }, timeoutSec)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start NFC polling: ${e.message}", e)
            onError(-1, "Exception: ${e.message}")
        }
    }

    /**
     * Cancel any active NFC polling and power off the NFC interface.
     */
    fun cancelNfcPolling() {
        try {
            readCardOpt?.cancelCheckCard()
            readCardOpt?.cardOff(AidlConstants.CardType.NFC.value)
        } catch (e: Exception) {
            Log.w(TAG, "Error cancelling NFC polling: ${e.message}")
        }
    }
}
