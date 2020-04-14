package tech.gdragon.data

import io.minio.MinioClient
import io.minio.ObjectStat
import mu.KotlinLogging
import net.jodah.failsafe.ExecutionContext
import net.jodah.failsafe.Failsafe
import net.jodah.failsafe.RetryPolicy
import org.apache.commons.io.FileUtils
import org.joda.time.DateTime
import org.koin.core.KoinComponent
import java.io.File
import java.time.temporal.ChronoUnit

class DataStore : KoinComponent {
  val logger = KotlinLogging.logger { }

  private val accessKey: String? = getKoin().getProperty("DS_ACCESS_KEY")
  private val bucketName: String? = getKoin().getProperty("DS_BUCKET")
  private val endpoint: String? = getKoin().getProperty("DS_HOST")
  private val secretKey: String? = getKoin().getProperty("DS_SECRET_KEY")
  private val baseUrl: String = getKoin().getProperty("DS_BASEURL", "$endpoint/$bucketName")

  private val client: MinioClient = MinioClient(endpoint, accessKey, secretKey)

  private val retryPolicy: RetryPolicy<Unit> = RetryPolicy<Unit>()
    .withBackoff(2, 30, ChronoUnit.SECONDS)
    .withJitter(.25)
    .onRetry { ex -> logger.warn { "Failure #${ex.attemptCount}. Retrying!" } }

  init {
    require(client.bucketExists(bucketName)) {
      "$bucketName bucket does not exist!"
    }
  }

  fun upload(key: String, file: File): UploadResult {
    logger.info {
      "Uploading: $baseUrl/$key"
    }

    Failsafe.with(retryPolicy).run { ->
      client.putObject(bucketName, key, file.path, null)
    }

    val stat = UploadResult.from(baseUrl, client.statObject(bucketName, key))

    logger.info {
      "Finished uploading file - (${FileUtils.byteCountToDisplaySize(stat.size)}) ${stat.key}"
    }

    return stat
  }
}

data class UploadResult(val key: String, val timestamp: DateTime, val size: Long, val url: String) {
  companion object {
    fun from(baseUrl: String, stat: ObjectStat) = UploadResult(stat.name(), DateTime(stat.createdTime()), stat.length(), "$baseUrl/${stat.name()}")
  }
}
