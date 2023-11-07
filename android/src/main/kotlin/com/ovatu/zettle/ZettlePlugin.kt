package com.ovatu.zettle

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.annotation.NonNull
import androidx.core.app.ActivityCompat.startActivityForResult
import androidx.lifecycle.Observer
import com.zettle.sdk.commons.state.StateObserver
import com.zettle.sdk.config
import com.zettle.sdk.commons.ext.state.toLiveData
import com.zettle.sdk.core.auth.User
import com.zettle.sdk.feature.cardreader.payment.TippingStyle
import com.zettle.sdk.feature.cardreader.payment.TransactionReference
import com.zettle.sdk.feature.cardreader.payment.refunds.RefundFailureReason
import com.zettle.sdk.feature.cardreader.payment.refunds.RetrieveCardPaymentFailureReason
import com.zettle.sdk.feature.cardreader.ui.payment.CardPaymentActivity
import com.zettle.sdk.feature.cardreader.ui.payment.CardPaymentResult
import com.zettle.sdk.feature.cardreader.ui.CardReaderAction
import com.zettle.sdk.feature.cardreader.ui.refunds.RefundResult
import com.zettle.sdk.feature.cardreader.ui.CardReaderFeature
import com.zettle.sdk.feature.cardreader.payment.refunds.CardPaymentPayload
import com.zettle.sdk.features.refund
import com.zettle.sdk.features.charge
import com.zettle.sdk.features.show
import com.zettle.sdk.ui.ZettleResult
import com.zettle.sdk.ui.zettleResult
import com.zettle.sdk.ZettleSDK
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import java.util.*

/** ZettlePlugin */
class ZettlePlugin: FlutterPlugin, MethodCallHandler, ActivityAware, PluginRegistry.ActivityResultListener {
  private val tag = "_ZettlePlugin"

  /// The MethodChannel that will the communication between Flutter and native Android
  ///
  /// This local reference serves to register the plugin with the Flutter Engine and unregister it
  /// when the Flutter Engine is detached from the Activity
  private lateinit var channel : MethodChannel
  private lateinit var activity: FlutterActivity

  private var sdkStarted: Boolean = false

  private var operations: MutableMap<String, ZettlePluginResponseWrapper> = mutableMapOf()
  private var currentOperation: ZettlePluginResponseWrapper? = null
  private var sdk: ZettleSDK? = null

  override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    Log.d(tag, "onAttachedToEngine")
    channel = MethodChannel(flutterPluginBinding.binaryMessenger, "zettle")
    channel.setMethodCallHandler(this)
  }

  override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    Log.d(tag, "onDetachedFromEngine")
    channel.setMethodCallHandler(null)
  }

  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    Log.d(tag, "onAttachedToActivity")
    activity = binding.activity as FlutterActivity
    binding.addActivityResultListener(this)
  }

  override fun onDetachedFromActivityForConfigChanges() {
    Log.d(tag, "onDetachedFromActivityForConfigChanges")
  }

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    Log.d(tag, "onReattachedToActivityForConfigChanges")
  }

  override fun onDetachedFromActivity() {
    Log.d(tag, "onDetachedFromActivity")
    if (sdkStarted) {
      sdk?.stop()
    }
  }

  override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
    Log.d(tag, "onMethodCall: ${call.method}")
    if (!operations.containsKey(call.method)) {
      operations[call.method] = ZettlePluginResponseWrapper(result)
    }

    val pluginResponseWrapper = operations[call.method]!!
    pluginResponseWrapper.methodResult = result
    pluginResponseWrapper.response = ZettlePluginResponse(call.method)

    currentOperation = pluginResponseWrapper

    when (call.method) {
      "init" -> init(call.arguments as Map<*, *>).flutterResult()
      "login" -> login()
      "logout" -> logout()
      "loggedIn" -> loggedIn().flutterResult()
      "requestPayment" -> requestPayment(call.arguments as Map<*, *>)
      "requestRefund" -> requestRefund(call.arguments as Map<*, *>)
      "showSettings" -> showSettings()
      else -> result.notImplemented()
    }
  }

  var loggedIn = false
  var info: User.Info? = null

  private val authObserver = Observer<User.AuthState> {
    when (it) {
      is User.AuthState.LoggedIn -> { // User authorized
        loggedIn = true
        info = it.info
        loginChange()
      }
      is User.AuthState.LoggedOut -> { // There is no authorized use
        loggedIn = false
        info = null
        loginChange()
      }
      else -> {}
    }
  }

  private fun init(@NonNull args: Map<*, *>): ZettlePluginResponseWrapper {
    val currentOp = operations["init"]!!
    try {
      val clientID = args["androidClientId"] as String
      val redirect = args["redirect"] as String

      val config = config(activity) {
        isDevMode = true
        auth {
          this.clientId = clientID
          this.redirectUrl = redirect
        }
        addFeature(CardReaderFeature.Configuration)
      }

      sdk = ZettleSDK.configure(config)
      sdk?.start()
      sdkStarted = true

      currentOp.response.message = mutableMapOf("initialized" to true)
      currentOp.response.status = true
    } catch (e: Exception) {
      currentOp.response.message = mutableMapOf("initialized" to false, "errors" to e.message)
      currentOp.response.status = false
    }
    return currentOp
  }

  private fun login() {
    Log.d(tag, "login")
    ZettleSDK.instance?.login(activity)
  }

  private fun logout() {
    Log.d(tag, "logout")
    ZettleSDK.instance?.logout()
  }

  private fun loginChange() {
    Log.d(tag, "loginChange")
    var currentOp: ZettlePluginResponseWrapper? = null
    if (operations["login"] != null) {
      currentOp = operations["login"]
    } else if (operations["logout"] != null) {
      currentOp = operations["logout"]
    }

    if (currentOp != null) {
      currentOp.response.status = loggedIn
      currentOp.response.message = mutableMapOf("loggedIn" to loggedIn)
      currentOp.flutterResult()
      operations.remove(currentOp.response.methodName)
    }
    Log.d(tag, "currentOp: $currentOp")
  }

  private fun loggedIn(): ZettlePluginResponseWrapper {
    Log.d(tag, "loggedIn")
    val currentOp = operations["loggedIn"]!!
    try {
      currentOp.response.status = loggedIn
      currentOp.response.message = mutableMapOf("loggedIn" to loggedIn)
    } catch (e: Exception) {
      currentOp.response.message = mutableMapOf("errors" to e.message)
      currentOp.response.status = false
    }
    return currentOp
  }

  private fun requestPayment(@NonNull args: Map<*, *>) {
    Log.d(tag, "requestPayment: $args")

    val internalUniqueTraceId = args["reference"] as String
    val reference = TransactionReference.Builder(internalUniqueTraceId).build()
    val action = CardReaderAction.Payment(reference, (((args["amount"] as Double) * 100).toInt()).toLong(), TippingStyle.None)
    val intent = action.charge(activity)
    startActivityForResult(activity, intent, ZettleTask.REQUEST_PAYMENT.code, null)
  }

  private fun requestRefund(@NonNull args: Map<*, *>) {
    Log.d(tag, "requestRefund: $args")

    val internalUniqueTraceId = args["reference"] as String
    var refundAmount = (args["refundAmount"] as Double?)
    if (refundAmount == null) {
      return
    }
    var amount = ((refundAmount!! * 100).toInt()).toLong()
    val reference = TransactionReference.Builder(UUID.randomUUID().toString()).build()
    val action = CardReaderAction.Refund(reference, amount, internalUniqueTraceId)
    val intent = action.refund(activity)
    startActivityForResult(activity, intent, ZettleTask.REQUEST_REFUND.code, null)
  }

  private fun showSettings(): ZettlePluginResponseWrapper {
    val currentOp = operations["showSettings"]!!

    var action = CardReaderAction.Settings
    val intent = action.show(activity)
    startActivityForResult(activity, intent, ZettleTask.SETTINGS.code, null)

    return currentOp
  }

 override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
   if (resultCode != Activity.RESULT_OK) {
     return false
   }

   Log.d(tag, "onActivityResult - RequestCode: $requestCode - Result Code: $resultCode")

   Log.d(tag, "onActivityResult - operations: $operations")

   val currentOp: ZettlePluginResponseWrapper? = when (ZettleTask.valueOf(requestCode)) {
     ZettleTask.REQUEST_PAYMENT -> operations["requestPayment"]
     ZettleTask.REQUEST_REFUND -> operations["requestRefund"]
     else -> null
   }

   Log.d(tag, "onActivityResult - cuurent op: $currentOp")

   if (currentOp == null) {
     return false
   }

   if (data != null && data.extras != null) {
     val result = resultCode == Activity.RESULT_OK

     currentOp.response.status = result

     when (ZettleTask.valueOf(requestCode)) {
       ZettleTask.REQUEST_PAYMENT -> {

         when (val result: ZettleResult? = data?.zettleResult()) {
           is ZettleResult.Completed<*> -> {
             val paymentResult: CardPaymentResult.Completed = CardReaderAction.fromPaymentResult(result)
             currentOp.response.status = true
             currentOp.response.message = mutableMapOf(
                     "status" to "completed",
                     "amount" to paymentResult.payload.amount,
                     "gratuityAmount" to paymentResult.payload.gratuityAmount,
                     "cardType" to paymentResult.payload.cardType,
                     "cardPaymentEntryMode" to paymentResult.payload.cardPaymentEntryMode,
                     "cardholderVerificationMethod" to paymentResult.payload.cardholderVerificationMethod,
                     "tsi" to paymentResult.payload.tsi,
                     "tvr" to paymentResult.payload.tvr,
                     "applicationIdentifier" to paymentResult.payload.applicationIdentifier,
                     "cardIssuingBank" to paymentResult.payload.cardIssuingBank,
                     "maskedPan" to paymentResult.payload.maskedPan,
                     "panHash" to paymentResult.payload.panHash,
                     "applicationName" to paymentResult.payload.applicationName,
                     "authorizationCode" to paymentResult.payload.authorizationCode,
                     "installmentAmount" to paymentResult.payload.installmentAmount,
                     "nrOfInstallments" to paymentResult.payload.nrOfInstallments,
                     "mxFiid" to paymentResult.payload.mxFiid,
                     "mxCardType" to paymentResult.payload.mxCardType,
                     "panHash" to paymentResult.payload.panHash,
                     "reference" to paymentResult.payload.reference?.id,
             )
           }
           is ZettleResult.Cancelled -> {
             currentOp.response.status = false
             currentOp.response.message = mutableMapOf(
                     "status" to "canceled"
             )
           }
           is ZettleResult.Failed -> {
             currentOp.response.message = mutableMapOf(
                     "status" to "failed",
             )
           }
           else -> {}
         }
         currentOp.flutterResult()
         operations.remove(currentOp.response.methodName)
       }
       ZettleTask.REQUEST_REFUND -> {

         when (val result = data?.zettleResult()) {
           is ZettleResult.Completed<*> -> {
             currentOp.response.status = true
             val refund : RefundResult.Completed = CardReaderAction.fromRefundResult(result)
             currentOp.response.message = mutableMapOf(
                     "status" to "completed",
                     "originalAmount" to refund.payload.originalAmount,
                     "refundedAmount" to refund.payload.refundedAmount,
                     "cardType" to refund.payload.cardType,
                     "maskedPan" to refund.payload.maskedPan,
                     "cardPaymentUUID" to refund.payload.cardPaymentUUID,
             )
           }
           is ZettleResult.Cancelled -> {
             currentOp.response.status = false
             currentOp.response.message = mutableMapOf(
                     "status" to "canceled"
             )
           }
           is ZettleResult.Failed -> {
             currentOp.response.status = false
             currentOp.response.message = mutableMapOf(
                     "status" to "failed",
             )
           }
           else -> {}
         }
         currentOp.flutterResult()
         operations.remove(currentOp.response.methodName)
       }
       else -> {
         currentOp.response.message = mutableMapOf("errors" to "Intent Data and/or Extras are null or empty")
         currentOp.response.status = false
         currentOp.flutterResult()
         operations.remove(currentOp.response.methodName)
       }
     }
   } else {
     currentOp.response.message = mutableMapOf("errors" to "Intent Data and/or Extras are null or empty")
     currentOp.response.status = false
     currentOp.flutterResult()
     operations.remove(currentOp.response.methodName)
   }
   return currentOp.response.status
 }
}


enum class ZettleTask(val code: Int) {
  REQUEST_PAYMENT(1), REQUEST_REFUND(2), SETTINGS(3);

  companion object {
    fun valueOf(value: Int) = values().find { it.code == value }
  }
}

class ZettlePluginResponseWrapper(@NonNull var methodResult: Result) {
  lateinit var response: ZettlePluginResponse
  fun flutterResult() {
    methodResult.success(response.toMap())
  }
}

class ZettlePluginResponse(@NonNull var methodName: String) {
  var status: Boolean = false
  lateinit var message: MutableMap<String, Any?>
  fun toMap(): Map<String, Any?> {
    return mapOf("status" to status, "message" to message, "methodName" to methodName)
  }
}
