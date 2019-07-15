package com.fondeadora.mobile.jumio_flutter

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Parcelable
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import com.jumio.MobileSDK
import com.jumio.core.enums.JumioDataCenter
import com.jumio.core.exceptions.MissingPermissionException
import com.jumio.core.exceptions.PlatformNotSupportedException
import com.jumio.nv.IsoCountryConverter
import com.jumio.nv.NetverifyDocumentData
import com.jumio.nv.NetverifyInitiateCallback
import com.jumio.nv.NetverifySDK
import com.jumio.nv.data.document.NVDocumentType
import com.jumio.nv.data.document.NVDocumentVariant
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import io.flutter.plugin.common.PluginRegistry.Registrar
import java.text.SimpleDateFormat

class JumioFlutterPlugin(private var activity: Activity) : MethodCallHandler,
    PluginRegistry.ActivityResultListener,
    PluginRegistry.RequestPermissionsResultListener {

  private lateinit var netverifySDK: NetverifySDK
  private lateinit var result: Result

  companion object {
    private const val TAG = "JumioFlutterPlugin"
    private const val REQUEST_PERMISSIONS = 9000

    @JvmStatic
    fun registerWith(registrar: Registrar) {
      val instance = JumioFlutterPlugin(registrar.activity())
      registrar.addActivityResultListener(instance)
      registrar.addRequestPermissionsResultListener(instance)

      val channel = MethodChannel(registrar.messenger(), "com.fondeadora.mobile/jumio_flutter")
      channel.setMethodCallHandler(instance)
    }
  }

  override fun onMethodCall(call: MethodCall, result: Result) {
    this.result = result

    try {
      when (call.method) {
        "init" -> {
          val arguments = call.arguments as java.util.HashMap<String, String>
          result.success(
              initializeNetverifySDK(
                arguments["apiKey"],
                arguments["apiSecret"],
                arguments["scanReference"],
                arguments["userReference"]
              )
          )
        }
        "scanDocument" -> startDocumentScan()
        else -> result.notImplemented()
      }
    } catch(e: Exception) {
      result.error("MethodCallError", e.message, e)
    }
  }

  private fun initializeNetverifySDK(apiKey: String?, apiSecret: String?, scanReference: String?, userReference: String?): Boolean {
    try {
      if (!NetverifySDK.isSupportedPlatform(activity)) {
        Log.w(TAG, "Device not supported")
        return false
      }

      if (NetverifySDK.isRooted(activity)) {
        Log.w(TAG, "Device is rooted")
        return false
      }

      netverifySDK = NetverifySDK.create(activity, apiKey, apiSecret, JumioDataCenter.US)

      netverifySDK.setEnableVerification(true)

			val alpha3 = IsoCountryConverter.convertToAlpha3("MX")
			netverifySDK.setPreselectedCountry(alpha3)

			val documentTypes = ArrayList<NVDocumentType>()
			documentTypes.add(NVDocumentType.PASSPORT)
      documentTypes.add(NVDocumentType.IDENTITY_CARD)
			netverifySDK.setPreselectedDocumentTypes(documentTypes)
			netverifySDK.setPreselectedDocumentVariant(NVDocumentVariant.PLASTIC)

			netverifySDK.setCustomerInternalReference(scanReference)
      netverifySDK.setUserReference(userReference)

			netverifySDK.setEnableEMRTD(false)
			netverifySDK.setDataExtractionOnMobileOnly(false)
			netverifySDK.sendDebugInfoToJumio(false)

			netverifySDK.initiate(object : NetverifyInitiateCallback {
				override fun onNetverifyInitiateSuccess() {}
				override fun onNetverifyInitiateError(errorCode: String, errorMessage: String, retryPossible: Boolean) {}
			})

    } catch (e: PlatformNotSupportedException) {
      Log.e(TAG, "Error in initializeNetverifySDK: ", e)
      return false
    } catch (e1: NullPointerException) {
      Log.e(TAG, "Error in initializeNetverifySDK: ", e1)
      return false
    }

    return true
  }

  private fun startDocumentScan() {
    if (checkPermissions()) {
      try {
        if (::netverifySDK.isInitialized) {
          activity.startActivityForResult(netverifySDK.intent, NetverifySDK.REQUEST_CODE)
        }
      } catch (e: MissingPermissionException) {
        Toast.makeText(activity, e.message, Toast.LENGTH_LONG).show()
        result.success(HashMap<String, String>())
      }
    }
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
    if (requestCode == NetverifySDK.REQUEST_CODE) {
      if (resultCode == Activity.RESULT_OK) {
        result.success(mapScanResults(data))

      } else if (resultCode == Activity.RESULT_CANCELED) {
        val errorMessage = data?.getStringExtra(NetverifySDK.EXTRA_ERROR_MESSAGE)
        val errorCode = data?.getStringExtra(NetverifySDK.EXTRA_ERROR_CODE)

        result.error(errorCode, errorMessage, null)
      }

      netverifySDK.destroy()
    }

    return true
  }

  private fun mapScanResults(data: Intent?): HashMap<String, String?>{
    val scanReference = data?.getStringExtra(NetverifySDK.EXTRA_SCAN_REFERENCE)
    val documentData = data?.getParcelableExtra<Parcelable>(NetverifySDK.EXTRA_SCAN_DATA) as? NetverifyDocumentData

    val dateFormat = SimpleDateFormat.getDateInstance()

    val resultMap = HashMap<String, String?>()

    resultMap["scanReference"] = scanReference
    resultMap["addressLine"] = documentData?.addressLine
    resultMap["city"] = documentData?.city
    resultMap["firstName"] = documentData?.firstName
    resultMap["idNumber"] = documentData?.idNumber
    resultMap["issuingCountry"] = documentData?.issuingCountry
    resultMap["lastName"] = documentData?.lastName
    resultMap["nameSuffix"] = documentData?.nameSuffix
    resultMap["optionalData1"] = documentData?.optionalData1
    resultMap["optionalData2"] = documentData?.optionalData2
    resultMap["originatingCountry"] = documentData?.originatingCountry
    resultMap["personalNumber"] = documentData?.personalNumber
    resultMap["placeOfBirth"] = documentData?.placeOfBirth
    resultMap["postCode"] = documentData?.postCode
    resultMap["selectedCountry"] = documentData?.selectedCountry
    resultMap["subdivision"] = documentData?.subdivision
    resultMap["dob"] = if (documentData?.dob != null) dateFormat.format(documentData.dob) else null
    resultMap["emrtdStatus"] = documentData?.emrtdStatus?.name
    resultMap["expiryDate"] = if(documentData?.expiryDate != null) dateFormat.format(documentData.expiryDate) else null
    resultMap["extractionMethod"] = documentData?.extractionMethod?.name
    resultMap["gender"] = documentData?.gender?.name
    resultMap["issuingDate"] = if (documentData?.issuingDate != null) dateFormat.format(documentData.issuingDate) else null
    resultMap["selectedDocumentType"] = documentData?.selectedDocumentType?.name
    resultMap["mrzLine1"] = documentData?.mrzData?.mrzLine1
    resultMap["mrzLine2"] = documentData?.mrzData?.mrzLine2
    resultMap["mrzLine3"] = documentData?.mrzData?.mrzLine3

    return resultMap
  }

  private fun checkPermissions(): Boolean {
    return if (!MobileSDK.hasAllRequiredPermissions(activity)) {
      val mp = MobileSDK.getMissingPermissions(activity)

      ActivityCompat.requestPermissions(activity, mp, REQUEST_PERMISSIONS)

      false

    } else {
      true
    }
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>?, grantResults: IntArray?): Boolean {
    if (requestCode == REQUEST_PERMISSIONS) {
      if (grantResults?.isNotEmpty() == true &&
          grantResults.all { result -> result == PackageManager.PERMISSION_GRANTED }) {
        startDocumentScan()
      } else {
        Toast.makeText(activity, "Missing permissions", Toast.LENGTH_LONG).show()
      }
      return true
    }
    return false
  }

}
