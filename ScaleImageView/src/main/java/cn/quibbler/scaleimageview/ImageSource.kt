package cn.quibbler.scaleimageview

import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import java.io.File
import java.io.UnsupportedEncodingException
import java.net.URLDecoder

/**
 * Helper class used to set the source and additional attributes from a variety of sources. Supports
 * use of a bitmap, asset, resource, external file or any other URI.
 *
 * When you are using a preview image, you must set the dimensions of the full size image on the
 * ImageSource object for the full size image using the {@link #dimensions(int, int)} method.
 */
class ImageSource {

    companion object {
        const val FILE_SCHEME = "file:///"
        const val ASSET_SCHEME = "file:///android_asset/"

        /**
         * Create an instance from a resource. The correct resource for the device screen resolution will be used.
         * @param resId resource ID.
         * @return an {@link ImageSource} instance.
         */
        fun resource(resId: Int): ImageSource = ImageSource(resId)

        /**
         * Create an instance from an asset name.
         * @param assetName asset name.
         * @return an {@link ImageSource} instance.
         */
        fun asset(assetName: String?): ImageSource {
            if (assetName == null) {
                throw NullPointerException("Asset name must not be null")
            }
            return uri(ASSET_SCHEME + assetName)
        }

        /**
         * Create an instance from a URI. If the URI does not start with a scheme, it's assumed to be the URI
         * of a file.
         * @param uri image URI.
         * @return an {@link ImageSource} instance.
         */
        fun uri(uri_: String?): ImageSource {
            var uri: String = uri_ ?: throw NullPointerException("Uri must not be null")
            if (!uri.contains("://")) {
                if (uri.startsWith("/")) {
                    uri = uri.substring(1)
                }
                uri = FILE_SCHEME + uri
            }
            return ImageSource(Uri.parse(uri))
        }

        /**
         * Create an instance from a URI.
         * @param uri image URI.
         * @return an {@link ImageSource} instance.
         */
        fun uri(uri: Uri?): ImageSource {
            if (uri == null) {
                throw NullPointerException("Uri must not be null")
            }
            return ImageSource(uri)
        }

        /**
         * Provide a loaded bitmap for display.
         * @param bitmap bitmap to be displayed.
         * @return an {@link ImageSource} instance.
         */
        fun bitmap(bitmap: Bitmap?): ImageSource {
            if (bitmap == null) {
                throw NullPointerException("Bitmap must not be null")
            }
            return ImageSource(bitmap, false)
        }

        /**
         * Provide a loaded and cached bitmap for display. This bitmap will not be recycled when it is no
         * longer needed. Use this method if you loaded the bitmap with an image loader such as Picasso
         * or Volley.
         * @param bitmap bitmap to be displayed.
         * @return an {@link ImageSource} instance.
         */
        fun cachedBitmap(bitmap: Bitmap?): ImageSource {
            if (bitmap == null) {
                throw NullPointerException("Bitmap must not be null")
            }
            return ImageSource(bitmap, true)
        }

    }

    val uri: Uri?
    val bitmap: Bitmap?
    val resource: Int?

    var title: Boolean = false
        private set
    var sWidth = 0
        private set
    var sHeight = 0
        private set
    var sRegion: Rect? = null
        private set
    var cached: Boolean = false

    private constructor(bitmap: Bitmap, cached: Boolean) {
        this.bitmap = bitmap
        this.uri = null
        this.resource = null
        this.sWidth = bitmap.width
        this.sHeight = bitmap.height
        this.cached = cached
    }

    private constructor(uri: Uri) {
        var uri_ = uri
        val uriString = uri.toString()
        if (uriString.startsWith(FILE_SCHEME)) {
            val uriFile = File(uriString.substring(FILE_SCHEME.length - 1))
            if (!uriFile.exists()) {
                try {
                    uri_ = Uri.parse(URLDecoder.decode(uriString, "UTF-8"))
                } catch (ignored: UnsupportedEncodingException) {
                }
            }
        }
        this.bitmap = null
        this.uri = uri_
        this.resource = null
        this.title = true
    }

    private constructor(resource: Int) {
        this.bitmap = null
        this.uri = null
        this.resource = resource
        this.title = true
    }

    /**
     * Enable tiling of the image. This does not apply to preview images which are always loaded as a single bitmap.,
     * and tiling cannot be disabled when displaying a region of the source image.
     * @return this instance for chaining.
     */
    fun tilingEnable(): ImageSource {
        return tiling(true)
    }

    /**
     * Disable tiling of the image. This does not apply to preview images which are always loaded as a single bitmap,
     * and tiling cannot be disabled when displaying a region of the source image.
     * @return this instance for chaining.
     */
    fun tilingDisabled(): ImageSource {
        return tiling(false)
    }

    /**
     * Enable or disable tiling of the image. This does not apply to preview images which are always loaded as a single bitmap,
     * and tiling cannot be disabled when displaying a region of the source image.
     * @param tile whether tiling should be enabled.
     * @return this instance for chaining.
     */
    fun tiling(title: Boolean): ImageSource {
        this.title = title
        return this
    }

    /**
     * Use a region of the source image. Region must be set independently for the full size image and the preview if
     * you are using one.
     * @param sRegion the region of the source image to be displayed.
     * @return this instance for chaining.
     */
    fun region(sRegion: Rect): ImageSource {
        this.sRegion = sRegion
        setInvariants()
        return this
    }

    /**
     * Declare the dimensions of the image. This is only required for a full size image, when you are specifying a URI
     * and also a preview image. When displaying a bitmap object, or not using a preview, you do not need to declare
     * the image dimensions. Note if the declared dimensions are found to be incorrect, the view will reset.
     * @param sWidth width of the source image.
     * @param sHeight height of the source image.
     * @return this instance for chaining.
     */
    fun dimensions(sWidth: Int, sHeight: Int): ImageSource {
        if (bitmap == null) {
            this.sWidth = sWidth
            this.sHeight = sHeight
        }
        setInvariants()
        return this
    }

    private fun setInvariants() {
        sRegion?.let {
            this.title = true
            this.sWidth = it.width()
            this.sHeight = it.height()
        }

    }

}