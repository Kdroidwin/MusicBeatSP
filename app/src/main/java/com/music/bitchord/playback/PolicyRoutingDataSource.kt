package com.music.bitchord.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/** Internal request marker carried through Media3's resolving and cache layers. */
internal const val MODULE_STREAM_POLICY_HEADER = "X-MusicBeatSP-Module-Stream"
private const val MODULE_STREAM_POLICY_VALUE = "public-internet-only"

/**
 * Selects a credential-free public-Internet client for module-returned stream
 * URLs while preserving the normal client for every existing playback source.
 * The selected upstream is retained across the bounded range reopenings made
 * by [ChunkedDataSource].
 */
@UnstableApi
internal class PolicyRoutingDataSource(
    private val normalFactory: DataSource.Factory,
    private val moduleFactory: DataSource.Factory,
) : DataSource {

    private var upstream: DataSource? = null
    private var modulePolicy: Boolean? = null
    private val transferListeners = mutableListOf<TransferListener>()

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners += transferListener
        upstream?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val isModuleStream = dataSpec.httpRequestHeaders[MODULE_STREAM_POLICY_HEADER] == MODULE_STREAM_POLICY_VALUE
        val selectedPolicy = modulePolicy
        if (selectedPolicy != null && selectedPolicy != isModuleStream) {
            throw IOException("A media data source cannot change its network policy")
        }
        modulePolicy = isModuleStream

        val source = upstream ?: (if (isModuleStream) moduleFactory else normalFactory)
            .createDataSource()
            .also { created ->
                transferListeners.forEach(created::addTransferListener)
                upstream = created
            }
        val request = if (isModuleStream) {
            val headers = dataSpec.httpRequestHeaders.toMutableMap().apply {
                remove(MODULE_STREAM_POLICY_HEADER)
            }
            dataSpec.buildUpon().setHttpRequestHeaders(headers).build()
        } else {
            dataSpec
        }
        return source.open(request)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        (upstream ?: throw IOException("Data source has not been opened")).read(buffer, offset, length)

    override fun getUri(): Uri? = upstream?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream?.responseHeaders.orEmpty()

    override fun close() {
        try {
            upstream?.close()
        } finally {
            upstream = null
            modulePolicy = null
        }
    }

    class Factory(
        private val normalFactory: DataSource.Factory,
        private val moduleFactory: DataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            PolicyRoutingDataSource(normalFactory, moduleFactory)
    }
}
