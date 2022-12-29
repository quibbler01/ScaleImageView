package cn.quibbler.scaleimageview

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.net.Uri
import android.os.AsyncTask
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.View
import cn.quibbler.scaleimageview.decoder.*
import java.util.*
import java.util.concurrent.locks.ReadWriteLock
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * <p>
 * Displays an image subsampled as necessary to avoid loading too much image data into memory. After zooming in,
 * a set of image tiles subsampled at higher resolution are loaded and displayed over the base layer. During pan and
 * zoom, tiles off screen or higher/lower resolution than required are discarded from memory.
 * </p><p>
 * Tiles are no larger than the max supported bitmap size, so with large images tiling may be used even when zoomed out.
 * </p><p>
 * v prefixes - coordinates, translations and distances measured in screen (view) pixels
 * <br>
 * s prefixes - coordinates, translations and distances measured in rotated and cropped source image pixels (scaled)
 * <br>
 * f prefixes - coordinates, translations and distances measured in original unrotated, uncropped source file pixels
 * </p><p>
 * <a href="https://github.com/davemorrissey/subsampling-scale-image-view">View project on GitHub</a>
 * </p>
 */
class SubsamplingScaleImageView : View {

    companion object {

        private val TAG = SubsamplingScaleImageView::class.java.simpleName

        /** Attempt to use EXIF information on the image to rotate it. Works for external files only.  */
        const val ORIENTATION_USE_EXIF = -1

        /** Display the image file in its native orientation.  */
        const val ORIENTATION_0 = 0

        /** Rotate the image 90 degrees clockwise.  */
        const val ORIENTATION_90 = 90

        /** Rotate the image 180 degrees.  */
        const val ORIENTATION_180 = 180

        /** Rotate the image 270 degrees clockwise.  */
        const val ORIENTATION_270 = 270

        private val VALID_ORIENTATIONS = listOf(ORIENTATION_0, ORIENTATION_90, ORIENTATION_180, ORIENTATION_270, ORIENTATION_USE_EXIF)

        /** During zoom animation, keep the point of the image that was tapped in the same place, and scale the image around it.  */
        const val ZOOM_FOCUS_FIXED = 1

        /** During zoom animation, move the point of the image that was tapped to the center of the screen.  */
        const val ZOOM_FOCUS_CENTER = 2

        /** Zoom in to and center the tapped point immediately without animating.  */
        const val ZOOM_FOCUS_CENTER_IMMEDIATE = 3

        private val VALID_ZOOM_STYLES = Arrays.asList(ZOOM_FOCUS_FIXED, ZOOM_FOCUS_CENTER, ZOOM_FOCUS_CENTER_IMMEDIATE)

        /** Quadratic ease out. Not recommended for scale animation, but good for panning.  */
        const val EASE_OUT_QUAD = 1

        /** Quadratic ease in and out.  */
        const val EASE_IN_OUT_QUAD = 2

        private val VALID_EASING_STYLES = listOf(EASE_IN_OUT_QUAD, EASE_OUT_QUAD)

        /** Don't allow the image to be panned off screen. As much of the image as possible is always displayed, centered in the view when it is smaller. This is the best option for galleries.  */
        const val PAN_LIMIT_INSIDE = 1

        /** Allows the image to be panned until it is just off screen, but no further. The edge of the image will stop when it is flush with the screen edge.  */
        const val PAN_LIMIT_OUTSIDE = 2

        /** Allows the image to be panned until a corner reaches the center of the screen but no further. Useful when you want to pan any spot on the image to the exact center of the screen.  */
        const val PAN_LIMIT_CENTER = 3

        private val VALID_PAN_LIMITS = listOf(PAN_LIMIT_INSIDE, PAN_LIMIT_OUTSIDE, PAN_LIMIT_CENTER)

        /** Scale the image so that both dimensions of the image will be equal to or less than the corresponding dimension of the view. The image is then centered in the view. This is the default behaviour and best for galleries.  */
        const val SCALE_TYPE_CENTER_INSIDE = 1

        /** Scale the image uniformly so that both dimensions of the image will be equal to or larger than the corresponding dimension of the view. The image is then centered in the view.  */
        const val SCALE_TYPE_CENTER_CROP = 2

        /** Scale the image so that both dimensions of the image will be equal to or less than the maxScale and equal to or larger than minScale. The image is then centered in the view.  */
        const val SCALE_TYPE_CUSTOM = 3

        /** Scale the image so that both dimensions of the image will be equal to or larger than the corresponding dimension of the view. The top left is shown.  */
        const val SCALE_TYPE_START = 4

        private val VALID_SCALE_TYPES = listOf(SCALE_TYPE_CENTER_CROP, SCALE_TYPE_CENTER_INSIDE, SCALE_TYPE_CUSTOM, SCALE_TYPE_START)

        /** State change originated from animation.  */
        const val ORIGIN_ANIM = 1

        /** State change originated from touch gesture.  */
        const val ORIGIN_TOUCH = 2

        /** State change originated from a fling momentum anim.  */
        const val ORIGIN_FLING = 3

        /** State change originated from a double tap zoom anim.  */
        const val ORIGIN_DOUBLE_TAP_ZOOM = 4

        // overrides for the dimensions of the generated tiles
        const val TILE_SIZE_AUTO = Int.MAX_VALUE
    }

    constructor(context: Context?) : super(context)
    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)
    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) : super(context, attrs, defStyleAttr, defStyleRes)

    // Bitmap (preview or full image)
    private var bitmap: Bitmap? = null

    // Whether the bitmap is a preview image
    private var bitmapIsPreview = false

    // Specifies if a cache handler is also referencing the bitmap. Do not recycle if so.
    private var bitmapIsCached = false

    // Uri of full size image
    private var uri: Uri? = null

    // Sample size used to display the whole image when fully zoomed out
    private var fullImageSampleSize = 0

    private var tileMap: MutableMap<Intent, MutableList<Tile>>? = null

    // Overlay tile boundaries and other info
    private var debug = false

    // Image orientation setting
    private var orientation = ORIENTATION_0

    // Max scale allowed (prevent infinite zoom)
    private var maxScale = 2f

    // Min scale allowed (prevent infinite zoom)
    private var minScale: Float = minScale()

    // Density to reach before loading higher resolution tiles
    private var minimumTileDpiv = -1

    // Pan limiting style
    private var panLimit = PAN_LIMIT_INSIDE

    // Minimum scale type
    private var minimumScaleType = SCALE_TYPE_CENTER_INSIDE

    private var maxTileWidth = TILE_SIZE_AUTO
    private var maxTileHeight = TILE_SIZE_AUTO

    // An executor service for loading of images
    private val executor = AsyncTask.THREAD_POOL_EXECUTOR

    // Whether tiles should be loaded while gestures and animations are still in progress
    private var eagerLoadingEnabled = true

    // Gesture detection settings
    private var panEnabled = true
    private var zoomEnabled = true
    private var quickScaleEnabled = true

    // Double tap zoom behaviour
    private var doubleTapZoomScale: Float = 1f
    private var doubleTapZoomStyle = ZOOM_FOCUS_FIXED
    private var doubleTapZoomDuration = 500

    // Current scale and scale at start of zoom
    private var scale: Float = 0f
    private var scaleStart: Float = 0f

    // Screen coordinate of top-left corner of source image
    private var vTranslate: PointF? = null
    private var vTranslateStart: PointF? = null
    private var vTranslateBefore: PointF? = null

    // Source coordinate to center on, used when new position is set externally before view is ready
    private var pendingScale: Float = 0f
    private var sPendingCenter: PointF? = null
    private var sRequestedCenter: PointF? = null

    // Source image dimensions and orientation - dimensions relate to the unrotated image
    private var sWidth = 0
    private var sHeight = 0
    private var sOrientation = 0
    private var sRegion: Rect? = null
    private var pRegion: Rect? = null

    // Is two-finger zooming in progress
    private var isZooming = false

    // Is one-finger panning in progress
    private var isPanning = false

    // Is quick-scale gesture in progress
    private var isQuickScaling = false

    // Max touches used in current gesture
    private var maxTouchCount = 0

    // Fling detector
    private var detector: GestureDetector? = null
    private var singleDetector: GestureDetector? = null

    // Tile and image decoding
    private var decoder: ImageRegionDecoder? = null
    private val decoderLock: ReadWriteLock = ReentrantReadWriteLock(true)
    private val bitmapDecoderFactory: DecoderFactory<out ImageDecoder> = CompatDecoderFactory<ImageDecoder>(SkiaImageDecoder::class.java)
    private var regionDecoderFactory: DecoderFactory<out ImageRegionDecoder> = CompatDecoderFactory<ImageRegionDecoder>(SkiaImageRegionDecoder::class.java)


    // Debug values
    private var vCenterStart: PointF? = null
    private var vDistStart: Float = 0f

    // Current quickscale state
    private val quickScaleThreshold: Float
    private var quickScaleLastDistance: Float = 0f
    private var quickScaleMoved = false
    private var quickScaleVLastPoint: PointF? = null
    private var quickScaleSCenter: PointF? = null
    private var quickScaleVStart: PointF? = null

    // Scale and center animation tracking
    private var anim: Anim? = null


    private class Tile {
        private var sRect: Rect? = null
        private var sampleSize = 0
        private var bitmap: Bitmap? = null
        private var loading = false
        private var visible = false

        // Volatile fields instantiated once then updated before use to reduce GC.
        private var vRect: Rect? = null
        private var fileSRect: Rect? = null
    }

    private class Anim {
        private val scaleStart = 0f// Scale at start of anim

        private val scaleEnd = 0f // Scale at end of anim (target)

        private val sCenterStart: PointF? = null // Source center point at start

        private val sCenterEnd: PointF? = null // Source center point at end, adjusted for pan limits

        private val sCenterEndRequested: PointF? = null // Source center point that was requested, without adjustment

        private val vFocusStart: PointF? = null // View point that was double tapped

        private val vFocusEnd: PointF? = null // Where the view focal point should be moved to during the anim

        private val duration: Long = 500 // How long the anim takes

        private val interruptible = true // Whether the anim can be interrupted by a touch

        private val easing = EASE_IN_OUT_QUAD // Easing style

        private val origin = ORIGIN_ANIM // Animation origin (API, double tap or fling)

        private val time = System.currentTimeMillis() // Start time

        private val listener: OnAnimationEventListener? = null // Event listener

    }

    /**
     * An event listener for animations, allows events to be triggered when an animation completes,
     * is aborted by another animation starting, or is aborted by a touch event. Note that none of
     * these events are triggered if the activity is paused, the image is swapped, or in other cases
     * where the view's internal state gets wiped or draw events stop.
     */
    interface OnAnimationEventListener {

        /**
         * The animation has completed, having reached its endpoint.
         */
        fun onComplete()

        /**
         * The animation has been aborted before reaching its endpoint because the user touched the screen.
         */
        fun onInterruptedByUser()

        /**
         * The animation has been aborted before reaching its endpoint because a new animation has been started.
         */
        fun onInterruptedByNewAnim()
    }


}