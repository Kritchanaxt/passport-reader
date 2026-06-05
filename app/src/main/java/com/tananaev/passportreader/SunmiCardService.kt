package com.tananaev.passportreader

import android.util.Log
import com.sunmi.pay.hardware.aidl.AidlConstants
import com.sunmi.pay.hardware.aidlv2.readcard.ReadCardOptV2
import net.sf.scuba.smartcards.CardService
import net.sf.scuba.smartcards.CardServiceException
import net.sf.scuba.smartcards.CommandAPDU
import net.sf.scuba.smartcards.ResponseAPDU
import java.util.Arrays

/**
 * A [CardService] implementation that bridges the SCUBA / jMRTD smart-card
 * abstraction to the Sunmi Pay SDK v2 APDU transport layer.
 *
 * Once a contactless card has been detected via
 * [ReadCardOptV2.checkCard], create an instance of this class and pass it to
 * [org.jmrtd.PassportService] — all APDU traffic will be routed through the
 * Sunmi NFC hardware transparently.
 *
 * ### APDU exchange protocol
 *
 * [ReadCardOptV2.smartCardExchange] expects:
 * - **Send buffer:** ISO 7816 APDU bytes (CLA INS P1 P2 [Lc Data] [Le])
 * - **Recv buffer (≥260 bytes):** `outLen(2B, big-endian) | outData(outLen B) | SWA(1B) | SWB(1B)`
 * - **Return:** `0` on success, `< 0` on error
 *
 * [CommandAPDU.getBytes] already produces a valid ISO 7816 byte sequence,
 * so no additional encoding is needed.
 */
class SunmiCardService(
    private val readCardOpt: ReadCardOptV2,
) : CardService() {

    companion object {
        private const val TAG = "SunmiCardService"
        private const val RECV_BUFFER_SIZE = 4096  // generous buffer for large passport DGs
    }

    @Volatile
    private var opened = false

    // ──── CardService lifecycle ────

    /**
     * Open the card connection.
     *
     * Because the NFC card has already been activated by
     * [ReadCardOptV2.checkCard], there is nothing extra to do here.
     */
    override fun open() {
        Log.d(TAG, "open()")
        opened = true
    }

    override fun isOpen(): Boolean = opened

    /**
     * Close the card connection and power off the NFC field.
     */
    override fun close() {
        Log.d(TAG, "close()")
        opened = false
        try {
            readCardOpt.cardOff(AidlConstants.CardType.NFC.value)
        } catch (e: Exception) {
            Log.w(TAG, "cardOff error: ${e.message}")
        }
    }

    /**
     * Return the ATR (Answer To Reset) bytes.
     * Contactless NFC cards do not have a traditional ATR, so this returns null.
     */
    override fun getATR(): ByteArray? = null

    /**
     * Determine whether the connection to the card has been lost.
     */
    override fun isConnectionLost(e: Exception?): Boolean {
        if (!opened) return true
        return try {
            // Check if the NFC card is still present
            val status = readCardOpt.getCardExistStatus(AidlConstants.CardType.NFC.value)
            // 1 = card absent, 2 = card present
            status != 2
        } catch (ex: Exception) {
            Log.w(TAG, "isConnectionLost check failed: ${ex.message}")
            true
        }
    }

    // ──── APDU transport ────

    /**
     * Transmit a [CommandAPDU] to the contactless card and return the
     * [ResponseAPDU].
     *
     * Internally delegates to [ReadCardOptV2.transmitApdu] using the
     * NFC card-type flag.
     */
    override fun transmit(commandAPDU: CommandAPDU): ResponseAPDU {
        val sendBytes = commandAPDU.bytes
        val recvBytes = ByteArray(RECV_BUFFER_SIZE)

        Log.d(TAG, "→ APDU send (${sendBytes.size}B): ${bytesToHex(sendBytes)}")

        val result: Int
        try {
            result = readCardOpt.transmitApdu(
                AidlConstants.CardType.NFC.value,
                sendBytes,
                recvBytes,
            )
        } catch (e: Exception) {
            Log.e(TAG, "transmitApdu threw exception: ${e.message}", e)
            throw CardServiceException("transmitApdu failed: ${e.message}", e)
        }

        Log.d(TAG, "transmitApdu result (length): $result")

        if (result < 0) {
            throw CardServiceException("transmitApdu returned error code: $result")
        }

        if (result < 2) {
            throw CardServiceException("transmitApdu returned invalid length: $result")
        }

        val responseBytes = Arrays.copyOf(recvBytes, result)

        val sw1 = responseBytes[result - 2].toInt() and 0xFF
        val sw2 = responseBytes[result - 1].toInt() and 0xFF
        Log.d(TAG, "← APDU recv (${result - 2}B data) SW=${String.format("%02X%02X", sw1, sw2)}")

        return ResponseAPDU(responseBytes)
    }

    // ──── Utility ────

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02X", b))
        }
        return sb.toString()
    }
}
