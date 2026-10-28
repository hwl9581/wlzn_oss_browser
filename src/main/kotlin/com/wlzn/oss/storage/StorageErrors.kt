package com.wlzn.oss.storage

import com.aliyun.oss.OSSException
import com.intellij.openapi.diagnostic.Logger
import com.wlzn.oss.model.StorageOperationException
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.s3.model.S3Exception
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

object StorageErrors {
    /** SDK 原始异常可能包含签名 URL、AK 或请求头，不直接显示或打印。 */
    fun describe(error: Throwable): String {
        if (error is StorageOperationException || error is IllegalArgumentException) return error.message ?: "操作参数无效"
        if (error is NullPointerException) return "插件内部异常（NullPointerException），请更新插件；若仍失败，可通过 IDEA 日志中的 WLZN OSS transfer 任务记录定位"
        val code: String
        val request: String
        when (error) {
            is OSSException -> { code = error.errorCode.orEmpty(); request = error.requestId.orEmpty() }
            is S3Exception -> { code = error.awsErrorDetails()?.errorCode().orEmpty(); request = error.requestId().orEmpty() }
            else -> {
                val causes = generateSequence(error) { it.cause }.take(8).toList()
                val hint = when {
                    causes.any { it is UnknownHostException } -> "Endpoint 域名无法解析，请检查地址是否正确（AWS S3 地址需要包含 s3），或清空 Endpoint 自动选择"
                    causes.any { it is SSLException || it is java.security.cert.CertificateException } -> "TLS/证书握手失败，请检查 IDEA 的代理、证书信任及网络连接"
                    causes.any { it is SocketTimeoutException || it is java.net.http.HttpTimeoutException } -> "网络请求超时，请检查 IDEA 代理和 Endpoint 的可达性"
                    causes.any { it is ConnectException } -> "无法建立网络连接，请检查 Endpoint、IDEA 代理和代理服务是否运行"
                    error is SdkClientException -> "S3 SDK 客户端请求失败，详见 IDEA 日志中的 WLZN OSS browser 诊断记录"
                    else -> "操作失败，请检查网络、Endpoint、Region 和凭据"
                }
                return "$hint（${causes.last().javaClass.simpleName}）"
            }
        }
        val hint = when (code) {
            "AccessDenied", "AllAccessDisabled" -> "访问被拒绝，请检查账号权限和 Bucket 策略"
            "InvalidAccessKeyId", "SignatureDoesNotMatch", "InvalidSecurityToken", "ExpiredToken" -> "凭据或签名无效，请检查密钥、临时 Token、Region 和本机时间"
            "NoSuchBucket" -> "Bucket 不存在或 Region 不匹配"
            "NoSuchKey", "NotFound" -> "对象不存在"
            "PreconditionFailed" -> "对象已发生变化，请刷新后重新操作"
            "AccessControlListNotSupported" -> "此 S3 Bucket 禁用了 ACL（BucketOwnerEnforced），请通过 Bucket 策略管理权限"
            "InvalidObjectState" -> "对象处于归档状态，需要先解冻"
            "NoSuchUpload" -> "分片任务不存在或已经完成，请刷新任务状态"
            "BucketNotEmpty" -> "Bucket 不是空的，不能删除（版本化 Bucket 还可能含历史版本）"
            else -> "云端操作失败：$code"
        }
        return "$hint${if (request.isBlank()) "" else "；RequestId=$request"}"
    }

    /** 仅记录异常类型与代码位置，不记录 SDK 异常消息、请求头或签名链接。 */
    fun diagnostic(error: Throwable): String = generateSequence(error) { it.cause }.take(5).joinToString("\nCaused by: ") { cause ->
        cause.javaClass.name + cause.stackTrace.take(30).joinToString("\n", prefix = "\n") { "  at $it" } +
            cause.suppressed.take(3).joinToString("") { suppressed ->
                "\nSuppressed: ${suppressed.javaClass.name}" + suppressed.stackTrace.take(10).joinToString("\n", prefix = "\n") { "  at $it" }
            }
    }

    fun logBrowserFailure(error: Throwable) {
        Logger.getInstance(StorageErrors::class.java).warn("WLZN OSS browser request failed\n${diagnostic(error)}")
    }
}
