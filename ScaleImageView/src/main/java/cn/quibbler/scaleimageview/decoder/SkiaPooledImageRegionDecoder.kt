package cn.quibbler.scaleimageview.decoder;

import android.app.ActivityManager
import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetManager
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.text.TextUtils
import android.util.Log
import cn.quibbler.scaleimageview.SubsamplingScaleImageView
import java.io.File
import java.io.FileFilter
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReadWriteLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.regex.Pattern

public class SkiaPooledImageRegionDecoder : ImageRegionDecoder {

    companion object {
        private val TAG = SkiaPooledImageRegionDecoder::class.java.simpleName

        /**
         * Controls logging of debug messages. All instances are affected.
         * @param debug true to enable debug logging, false to disable.
         */
        var debug = false

        private const val FILE_PREFIX = "file://"
        private const val ASSET_PREFIX = "$FILE_PREFIX/android_asset/"
        private const val RESOURCE_PREFIX = "${ContentResolver.SCHEME_ANDROID_RESOURCE}://"
    }

    private val decoderLock: ReadWriteLock = ReentrantReadWriteLock(true)
    private val imageDimensions = Point(0, 0)
    private val lazyInited = AtomicBoolean(false)

    private var decoderPool: DecoderPool? = DecoderPool()
    private var bitmapConfig: Bitmap.Config
    private lateinit var context: Context
    private lateinit var uri: Uri

    private var fileLength = Long.MAX_VALUE

    constructor(bitmapConfig: Bitmap.Config?) {
        val globalBitmapConfig: Bitmap.Config? = SubsamplingScaleImageView.preferredBitmapConfig
        this.bitmapConfig = if (bitmapConfig != null) {
            bitmapConfig
        } else if (globalBitmapConfig != null) {
            globalBitmapConfig
        } else {
            Bitmap.Config.RGB_565
        }
    }

    /**
     * Initialises the decoder pool. This method creates one decoder on the current thread and uses
     * it to decode the bounds, then spawns an independent thread to populate the pool with an
     * additional three decoders. The thread will abort if {@link #recycle()} is called.
     */
    override fun init(context: Context, uri: Uri): Point {
        this.context = context
        this.uri = uri
        initialiseDecoder()
        return this.imageDimensions
    }

    /**
     * Initialises extra decoders for as long as {@link #allowAdditionalDecoder(int, long)} returns
     * true and the pool has not been recycled.
     */
    private fun lazyInit() {
        if (lazyInited.compareAndSet(false, true) && fileLength < Long.MAX_VALUE) {
            debug("Starting lazy init of additional decoders")
            Thread {
                while (decoderPool != null && allowAdditionalDecoder(decoderPool?.size() ?: 0, fileLength)) {
                    // New decoders can be created while reading tiles but this read lock prevents
                    // them being initialised while the pool is being recycled.
                    try {
                        decoderPool?.let {
                            val start = System.currentTimeMillis()
                            debug("Starting decoder")
                            initialiseDecoder()
                            val end = System.currentTimeMillis()
                            debug("Started decoder, took ${end - start}ms")
                        }
                    } catch (e: Exception) {
                        debug("Failed to start decoder: ${e.message}")
                    }
                }
            }.start()
        }
    }

    /**
     * Initialises a new {@link BitmapRegionDecoder} and adds it to the pool, unless the pool has
     * been recycled while it was created.
     */
    @Throws(Exception::class)
    private fun initialiseDecoder() {
        val uriString = uri.toString()
        var decoder: BitmapRegionDecoder? = null
        var fileLength = Long.MAX_VALUE
        if (uriString.startsWith(RESOURCE_PREFIX)) {
            val packageName = uri.authority
            val res = if (context.packageName == packageName) {
                context.resources
            } else {
                context.packageManager.getResourcesForApplication(packageName!!)
            }
            var id = 0
            val segments = uri.pathSegments
            val size = segments.size
            if (size == 2 && segments[0] == "drawable") {
                val resName = segments[1]
                id = res.getIdentifier(resName, "drawable", packageName)
            } else if (size == 1 && TextUtils.isDigitsOnly(segments[0])) {
                try {
                    id = segments[0].toInt()
                } catch (ignored: NumberFormatException) {
                }
            }
            try {
                val descriptor = context.resources.openRawResourceFd(id)
                fileLength = descriptor.length
            } catch (ignored: Exception) {
            }
            decoder = BitmapRegionDecoder.newInstance(context.resources.openRawResource(id), false)
        } else if (uriString.startsWith(ASSET_PREFIX)) {
            val assetName = uriString.substring(ASSET_PREFIX.length)
            try {
                val descriptor = context.assets.openFd(assetName)
                fileLength = descriptor.length
            } catch (ignored: Exception) {
            }
            decoder = BitmapRegionDecoder.newInstance(context.assets.open(assetName, AssetManager.ACCESS_RANDOM), false)
        } else if (uriString.startsWith(FILE_PREFIX)) {
            decoder = BitmapRegionDecoder.newInstance(uriString.substring(FILE_PREFIX.length), false)
            try {
                val file = File(uriString)
                if (file.exists()) {
                    fileLength = file.length()
                }
            } catch (ignored: Exception) {
            }
        } else {
            var inputStream: InputStream? = null
            try {
                inputStream = context.contentResolver.openInputStream(uri) ?: return
                decoder = BitmapRegionDecoder.newInstance(inputStream, false)
                try {
                    val descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r")
                    if (descriptor != null) {
                        fileLength = descriptor.length
                    }
                } catch (ignored: Exception) {
                }
            } finally {
                try {
                    inputStream?.close()
                } catch (ignored: Exception) {
                }
            }
        }

        this.fileLength = fileLength
        this.imageDimensions.set(decoder?.width ?: 0, decoder?.height ?: 0)
        decoderLock.writeLock().lock()
        try {
            decoderPool?.add(decoder)
        } finally {
            decoderLock.writeLock().unlock()
        }
    }

    /**
     * Holding a read lock to avoid returning true while the pool is being recycled, this returns
     * true if the pool has at least one decoder available.
     */
    override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap {
        debug("Decode region $sRect on thread ${Thread.currentThread().name}")
        if (sRect.width() < imageDimensions.x || sRect.height() < imageDimensions.y) {
            lazyInit()
        }

        decoderLock.readLock().lock()
        try {
            decoderPool?.let {
                val decoder: BitmapRegionDecoder? = it.acquire()
                try {
                    // Decoder can't be null or recycled in practice
                    if (decoder != null && !decoder.isRecycled) {
                        val options = BitmapFactory.Options()
                        options.inSampleSize = sampleSize
                        options.inPreferredConfig = bitmapConfig
                        val bitmap = decoder.decodeRegion(sRect, options) ?: throw RuntimeException("Skia image decoder returned null bitmap - image format may not be supported")
                        return bitmap
                    }
                } finally {
                    decoder?.let { d ->
                        decoderPool?.release(d)
                    }
                }
            }
            throw IllegalStateException("Cannot decode region after decoder has been recycled")
        } finally {
            decoderLock.readLock().unlock()
        }
    }

    /**
     * Wait until all read locks held by {@link #decodeRegion(Rect, int)} are released, then recycle
     * and destroy the pool. Elsewhere, when a read lock is acquired, we must check the pool is not null.
     */
    override fun isReady(): Boolean {
        return decoderPool != null && !decoderPool!!.isEmpty()
    }

    /**
     * Called before creating a new decoder. Based on number of CPU cores, available memory, and the
     * size of the image file, determines whether another decoder can be created. Subclasses can
     * override and customise this.
     * @param numberOfDecoders the number of decoders that have been created so far
     * @param fileLength the size of the image file in bytes. Creating another decoder will use approximately this much native memory.
     * @return true if another decoder can be created.
     */
    fun allowAdditionalDecoder(numberOfDecoders: Int, fileLength: Long): Boolean {
        if (numberOfDecoders >= 4) {
            debug("No additional decoders allowed, reached hard limit (4)")
            return false
        } else if (numberOfDecoders * fileLength > 20 * 1024 * 1024) {
            debug("No additional encoders allowed, reached hard memory limit (20Mb)")
            return false
        } else if (numberOfDecoders >= getNumberOfCores()) {
            debug("No additional encoders allowed, limited by CPU cores (${getNumberOfCores()})")
            return false
        } else if (isLowMemory()) {
            debug("No additional encoders allowed, memory is low")
            return false
        }
        debug("Additional decoder allowed, current count is $numberOfDecoders, estimated native memory ${fileLength * numberOfDecoders / (1024 * 1024)}Mb")
        return true
    }

    /**
     * A simple pool of {@link BitmapRegionDecoder} instances, all loading from the same source.
     */
    private class DecoderPool {
        private val available = Semaphore(0, true)
        private val decoders: MutableMap<BitmapRegionDecoder, Boolean> = ConcurrentHashMap()

        /**
         * Returns false if there is at least one decoder in the pool.
         */
        @Synchronized
        fun isEmpty(): Boolean = decoders.isEmpty()

        /**
         * Returns number of encoders.
         */
        @Synchronized
        fun size(): Int = decoders.size

        /**
         * Acquire a decoder. Blocks until one is available.
         */
        fun acquire(): BitmapRegionDecoder? {
            available.acquireUninterruptibly()
            return getNextAvailable()
        }

        /**
         * Release a decoder back to the pool.
         */
        fun release(decoder: BitmapRegionDecoder) {
            if (markAsUnused(decoder)) {
                available.release()
            }
        }

        /**
         * Adds a newly created decoder to the pool, releasing an additional permit.
         */
        @Synchronized
        fun add(decoder: BitmapRegionDecoder?) {
            decoder?.let {
                decoders[it] = false
                available.release()
            }
        }

        /**
         * While there are decoders in the map, wait until each is available before acquiring,
         * recycling and removing it. After this is called, any call to {@link #acquire()} will
         * block forever, so this call should happen within a write lock, and all calls to
         * {@link #acquire()} should be made within a read lock so they cannot end up blocking on
         * the semaphore when it has no permits.
         */
        @Synchronized
        fun recycle() {
            while (decoders.isNotEmpty()) {
                val decoder = acquire()
                decoder?.recycle()
                decoders.remove(decoder)
            }
        }

        @Synchronized
        private fun getNextAvailable(): BitmapRegionDecoder? {
            for (entry in decoders.entries) {
                if (!entry.value) {
                    entry.setValue(true)
                    return entry.key
                }
            }
            return null
        }

        @Synchronized
        private fun markAsUnused(decoder: BitmapRegionDecoder?): Boolean {
            for (entry in decoders.entries) {
                if (decoder == entry.key) {
                    if (entry.value) {
                        entry.setValue(false)
                        return true
                    } else {
                        return false
                    }
                }
            }
            return false
        }

    }

    /**
     * Wait until all read locks held by {@link #decodeRegion(Rect, int)} are released, then recycle
     * and destroy the pool. Elsewhere, when a read lock is acquired, we must check the pool is not null.
     */
    override fun recycle() {
        decoderLock.writeLock().lock()
        try {
            decoderPool?.recycle()
            decoderPool = null
        } finally {
            decoderLock.writeLock().unlock()
        }
    }

    private fun getNumberOfCores(): Int {
        return if (Build.VERSION.SDK_INT >= 17) {
            Runtime.getRuntime().availableProcessors()
        } else {
            getNumCoresOldPhones()
        }
    }

    /**
     * Gets the number of cores available in this device, across all processors.
     * Requires: Ability to peruse the filesystem at "/sys/devices/system/cpu"
     * @return The number of cores, or 1 if failed to get result
     */
    private fun getNumCoresOldPhones(): Int {
        class CpuFilter : FileFilter {
            override fun accept(pathname: File): Boolean {
                return Pattern.matches("cpu[0-9]+", pathname.name)
            }
        }
        try {
            val dir = File("/sys/devices/system/cpu/")
            val files = dir.listFiles(CpuFilter())
            return files?.size ?: 1
        } catch (ignored: Exception) {
            return 1
        }
    }

    private fun isLowMemory(): Boolean {
        val activityManager: ActivityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager? ?: return true
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return memoryInfo.lowMemory
    }

    private fun debug(message: String?) {
        message?.let {
            if (debug) {
                Log.d(TAG, it)
            }
        }
    }

}
