package com.tananaev.passportreader

import android.content.Context
import android.util.Log
import java.lang.reflect.Proxy

object SunmiNfcSwitcher {
    private const val TAG = "SunmiNfcSwitcher"
    private var activeNfcSn: String? = null
    private var registeredListener: Any? = null

    fun initAndSwitch(context: Context) {
        try {
            // 1. Resolve classes dynamically
            val nfcManagerClass = Class.forName("com.sunmi.peripheral.aidl.NfcManager")
            val nfcControlManagerClass = Class.forName("com.sunmi.peripheral.aidl.NfcControlManager")
            val nfcClass = Class.forName("com.sunmi.peripheral.aidl.bean.Nfc")
            val iNfcListenerClass = Class.forName("com.sunmi.peripheral.aidl.callback.INfcListener")

            // 2. Initialize NfcManager
            val initMethod = nfcManagerClass.methods.find { it.name == "init" && it.parameterCount == 2 }
            if (initMethod != null) {
                val callbackParamType = initMethod.parameterTypes[1]
                
                // Dynamically implement the initialization callback interface using Proxy
                val callbackProxy = Proxy.newProxyInstance(
                    callbackParamType.classLoader,
                    arrayOf(callbackParamType)
                ) { _, method, args ->
                    // Typically the callback has a method like onInitSuccess or passes Boolean success
                    Log.i(TAG, "SUNMI NFC SDK Callback invoked: ${method.name}")
                    registerListener(nfcControlManagerClass, nfcClass, iNfcListenerClass)
                    null
                }
                
                initMethod.invoke(null, context, callbackProxy)
                Log.i(TAG, "SUNMI NfcManager.init called successfully via reflection")
            } else {
                Log.e(TAG, "NfcManager.init method not found via reflection")
            }
        } catch (e: ClassNotFoundException) {
            Log.i(TAG, "SUNMI Peripheral NFC SDK not available on this device (normal behavior for non-Sunmi devices)")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing SUNMI NFC via reflection", e)
        }
    }

    private fun registerListener(
        nfcControlManagerClass: Class<*>,
        nfcClass: Class<*>,
        iNfcListenerClass: Class<*>
    ) {
        try {
            val registerMethod = nfcControlManagerClass.getMethod("registerNfcListener", iNfcListenerClass)
            val switchMethod = nfcControlManagerClass.getMethod("switchNfc", String::class.java)

            // Dynamically implement the INfcListener AIDL callback interface using Proxy
            val listenerProxy = Proxy.newProxyInstance(
                iNfcListenerClass.classLoader,
                arrayOf(iNfcListenerClass)
            ) { _, method, args ->
                if (method.name == "onNfcListChanged") {
                    val nfcList = args?.get(0) as? List<*>
                    if (!nfcList.isNullOrEmpty()) {
                        Log.d(TAG, "SUNMI Available NFC Modules: ${nfcList.size}")
                        
                        // Dynamically retrieve properties from com.sunmi.peripheral.aidl.bean.Nfc
                        val getSn = nfcClass.getMethod("getSn")
                        val getName = nfcClass.getMethod("getName")
                        val getType = nfcClass.getMethod("getType")

                        var targetSn: String? = null
                        for (nfcObj in nfcList) {
                            if (nfcObj == null) continue
                            val name = getName.invoke(nfcObj) as? String ?: ""
                            val sn = getSn.invoke(nfcObj) as? String ?: ""
                            val type = getType.invoke(nfcObj) as? Int ?: 0
                            Log.d(TAG, "SUNMI NFC Device -> Name: $name, SN: $sn, Type: $type")
                            
                            // Check if external module
                            if (type == 1 || name.contains("external", ignoreCase = true)) {
                                targetSn = sn
                            }
                        }

                        if (targetSn == null && nfcList.isNotEmpty()) {
                            targetSn = getSn.invoke(nfcList[0]) as? String
                        }

                        targetSn?.let { sn ->
                            if (activeNfcSn != sn) {
                                Log.i(TAG, "Switching NFC active path to SN: $sn")
                                switchMethod.invoke(null, sn)
                                activeNfcSn = sn
                            }
                        }
                    }
                }
                null
            }

            registerMethod.invoke(null, listenerProxy)
            registeredListener = listenerProxy
            Log.i(TAG, "SUNMI NFC Listener registered successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register SUNMI NFC listener via reflection", e)
        }
    }

    fun cleanup(context: Context) {
        try {
            if (registeredListener != null) {
                val nfcControlManagerClass = Class.forName("com.sunmi.peripheral.aidl.NfcControlManager")
                val iNfcListenerClass = Class.forName("com.sunmi.peripheral.aidl.callback.INfcListener")
                val unregisterMethod = nfcControlManagerClass.getMethod("unregisterNfcListener", iNfcListenerClass)
                unregisterMethod.invoke(null, registeredListener)
                
                try {
                    val destroyMethod = nfcControlManagerClass.getMethod("destroy", Context::class.java)
                    destroyMethod.invoke(null, context)
                } catch (e: Exception) {
                    val destroyMethodNoParam = nfcControlManagerClass.getMethod("destroy")
                    destroyMethodNoParam.invoke(null)
                }
                
                registeredListener = null
                Log.i(TAG, "SUNMI NFC SDK cleaned up successfully")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up SUNMI NFC via reflection: ${e.message}")
        }
    }
}
