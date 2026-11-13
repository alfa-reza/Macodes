package dev.termish.data

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryCreate
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSLog
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.stringByAppendingPathComponent
import platform.Foundation.writeToFile
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * iOS Security Storage: System Keychain (kSecClassGenericPassword).
 *
 * To query a dictionary, use `CFDictionaryCreate` to construct a real `CFDictionary`: Kotlin/Native’s
 * NSMutableDictionary() returns an internal wrapper class (NSDictionaryAsKMap); cast it
 * CFDictionaryRef will inevitably throw a ClassCastException; if the exception propagates through the UIKit gesture callback, it will cause
 * Touch input is unresponsive throughout the app (manifesting as the app freezing after tapping "Save"). Strings are bridged using CFBridgingRetain,
 * Release the memory using `CFBridgingRelease` after use, consistent with the CF memory conventions of the Apple Security API.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, ExperimentalNativeApi::class)
actual object SecretStore {
    /** errSecMissingEntitlement: Keychain is unavailable if the app does not have a signature or if the keychain-access-groups configuration is not complete. */
    private const val ERR_SEC_MISSING_ENTITLEMENT = -34018

    actual fun get(
        service: String,
        account: String,
    ): String? =
        withCfRetain(service, account) { cfService, cfAccount ->
            val query =
                cfDictionaryOf(
                    kSecClass to kSecClassGenericPassword,
                    kSecAttrService to cfService,
                    kSecAttrAccount to cfAccount,
                    kSecReturnData to kCFBooleanTrue,
                    kSecMatchLimit to kSecMatchLimitOne,
                )
            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, result.ptr)
            CFBridgingRelease(query)
            // Log only the status, not the service or account: Avoid writing the mapping between host IDs and credential types to the system logs.
            NSLog("Termish-KEYCHAIN get status=$status")
            if (status != errSecSuccess) {
                if (status == ERR_SEC_MISSING_ENTITLEMENT && Platform.isDebugBinary) {
                    NSLog("Termish-KEYCHAIN get fallback to file (missing entitlement)")
                    return@withCfRetain fallbackRead(account)
                }
                return@withCfRetain null
            }
            val data = CFBridgingRelease(result.value) as? NSData
            NSLog("Termish-KEYCHAIN get data=${data?.length} bytes")
            data?.let { NSString.create(it, NSUTF8StringEncoding)?.toString() }
        }

    actual fun set(
        service: String,
        account: String,
        value: String,
    ) {
        val data = NSString.create(string = value).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        withCfRetain(service, account, data) { cfService, cfAccount, cfData ->
            val query =
                cfDictionaryOf(
                    kSecClass to kSecClassGenericPassword,
                    kSecAttrService to cfService,
                    kSecAttrAccount to cfAccount,
                )
            val del = SecItemDelete(query)
            CFBridgingRelease(query)
            NSLog("Termish-KEYCHAIN set delete=$del")

            val attrs =
                cfDictionaryOf(
                    kSecClass to kSecClassGenericPassword,
                    kSecAttrService to cfService,
                    kSecAttrAccount to cfAccount,
                    kSecValueData to cfData,
                )
            val add = SecItemAdd(attrs, null)
            CFBridgingRelease(attrs)
            NSLog("Termish-KEYCHAIN set add=$add")
            if (add == ERR_SEC_MISSING_ENTITLEMENT && Platform.isDebugBinary) {
                NSLog("Termish-KEYCHAIN set fallback to file (missing entitlement)")
                fallbackWrite(account, value)
            }
        }
    }

    actual fun delete(
        service: String,
        account: String,
    ) {
        withCfRetain(service, account) { cfService, cfAccount ->
            val query =
                cfDictionaryOf(
                    kSecClass to kSecClassGenericPassword,
                    kSecAttrService to cfService,
                    kSecAttrAccount to cfAccount,
                )
            val del = SecItemDelete(query)
            CFBridgingRelease(query)
            NSLog("Termish-KEYCHAIN delete status=$del")
            if (del == ERR_SEC_MISSING_ENTITLEMENT && Platform.isDebugBinary) {
                fallbackDelete(account)
            }
        }
    }

    // ---------- Sandbox File Fallback ----------
    // Keychain requires the keychain-access-groups entitlement; CLI builds/unsigned emulator packages
    // -34018 (errSecMissingEntitlement) is returned. In debug builds only, fall back to storing in the app’s private directory,
    // Facilitates development and debugging; releases strictly follow a “fail-closed” policy and do not store credentials in plain text on disk.

    private fun fallbackDir(): String {
        val docs =
            NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
                .firstOrNull() as? String ?: return ""
        val dir = (docs as NSString).stringByAppendingPathComponent("termish-secrets")
        NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
        return dir
    }

    private fun fallbackPath(account: String): String = (fallbackDir() as NSString).stringByAppendingPathComponent("$account.txt")

    private fun fallbackRead(account: String): String? =
        NSString
            .create(contentsOfFile = fallbackPath(account), encoding = NSUTF8StringEncoding, error = null)
            ?.toString()

    private fun fallbackWrite(
        account: String,
        value: String,
    ) {
        NSString.create(string = value).writeToFile(fallbackPath(account), true, NSUTF8StringEncoding, null)
    }

    private fun fallbackDelete(account: String) {
        NSFileManager.defaultManager.removeItemAtPath(fallbackPath(account), null)
    }
}

/** Construct a true CFDictionary (NSMutableDictionary from K/N cannot be safely converted to CFDictionaryRef). */
@OptIn(ExperimentalForeignApi::class)
private fun MemScope.cfDictionaryOf(vararg items: Pair<CFStringRef?, CFTypeRef?>): CFDictionaryRef? {
    val size = items.size
    val keys = allocArrayOf(*items.map { it.first }.toTypedArray())
    val values = allocArrayOf(*items.map { it.second }.toTypedArray())
    return CFDictionaryCreate(
        kCFAllocatorDefault,
        keys.reinterpret(),
        values.reinterpret(),
        size.convert(),
        null,
        null,
    )
}

/** Bridge Kotlin objects to CFTypeRef for use by the Security API, and release them uniformly after the operation is complete. */
@OptIn(ExperimentalForeignApi::class)
private inline fun <T> withCfRetain(
    value1: Any?,
    value2: Any?,
    block: MemScope.(CFTypeRef?, CFTypeRef?) -> T,
): T =
    memScoped {
        val cfValue1 = CFBridgingRetain(value1)
        val cfValue2 = CFBridgingRetain(value2)
        try {
            block(cfValue1, cfValue2)
        } finally {
            CFBridgingRelease(cfValue1)
            CFBridgingRelease(cfValue2)
        }
    }

@OptIn(ExperimentalForeignApi::class)
private inline fun <T> withCfRetain(
    value1: Any?,
    value2: Any?,
    value3: Any?,
    block: MemScope.(CFTypeRef?, CFTypeRef?, CFTypeRef?) -> T,
): T =
    memScoped {
        val cfValue1 = CFBridgingRetain(value1)
        val cfValue2 = CFBridgingRetain(value2)
        val cfValue3 = CFBridgingRetain(value3)
        try {
            block(cfValue1, cfValue2, cfValue3)
        } finally {
            CFBridgingRelease(cfValue1)
            CFBridgingRelease(cfValue2)
            CFBridgingRelease(cfValue3)
        }
    }
