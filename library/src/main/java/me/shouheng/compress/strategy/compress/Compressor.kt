package me.shouheng.compress.strategy.compress

import android.graphics.*
import android.os.AsyncTask
import io.reactivex.Flowable
import kotlinx.coroutines.withContext
import me.shouheng.compress.AbstractStrategy
import me.shouheng.compress.strategy.config.Config
import me.shouheng.compress.strategy.config.ScaleMode
import me.shouheng.compress.utils.CImageUtils
import me.shouheng.compress.utils.CLog

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Callable
import kotlin.coroutines.CoroutineContext

/**
 * The compress algorithm by [Compressor](https://github.com/zetbaitsu/Compressor).
 *
 * @author WngShhng
 * @version 2019-05-17
 */
open class Compressor : AbstractStrategy() {

    private var maxWidth: Float = Config.COMPRESSOR_DEFAULT_MAX_WIDTH

    private var maxHeight: Float = Config.COMPRESSOR_DEFAULT_MAX_HEIGHT

    @ScaleMode
    private var scaleMode: Int = Config.COMPRESSOR_DEFAULT_SCALE_MODE

    private var config: Bitmap.Config? = null

    override fun getBitmap(): Bitmap? = compressByQuality()

    /*--------------------------------------- public methods ------------------------------------------*/

    /**
     * Set the max width of compressed image.
     *
     * @param maxWidth the max width in pixels.
     * @return         the compressor instance.
     */
    fun setMaxWidth(maxWidth: Float): Compressor {
        this.maxWidth = maxWidth
        return this
    }

    /**
     * Set the max height of compressed image.
     *
     * @param maxHeight the max height in pixels.
     * @return          the compressor instance.
     */
    fun setMaxHeight(maxHeight: Float): Compressor {
        this.maxHeight = maxHeight
        return this
    }

    /**
     * Set the scale mode when the destination image ratio differ from the original original.
     *
     * Might be one of :
     * 1. [ScaleMode.SCALE_LARGER],
     * 2. [ScaleMode.SCALE_SMALLER],
     * 3. [ScaleMode.SCALE_WIDTH]
     * 4. [ScaleMode.SCALE_HEIGHT].
     *
     * @param scaleMode the scale mode.
     * @return          the compressor instance.
     * @see ScaleMode   for details ab out this field
     */
    fun setScaleMode(@ScaleMode scaleMode: Int): Compressor {
        this.scaleMode = scaleMode
        return this
    }

    /**
     * Set the image configuration for bitmap: [android.graphics.Bitmap.Config].
     *
     * @param config the config
     * @return       the compress instance
     * @see android.graphics.Bitmap.Config
     */
    fun setConfig(config: Bitmap.Config): Compressor {
        this.config = config
        return this
    }

    override fun get(): File {
        try {
            notifyCompressStart()
            compressAndWrite()
            notifyCompressSuccess(outFile!!)
        } catch (e: Exception) {
            CLog.e(e.message)
            notifyCompressError(e)
        }

        return outFile!!
    }

    override suspend fun get(coroutineContext: CoroutineContext): File = withContext(coroutineContext) {
        return@withContext get()
    }

    override fun asFlowable(): Flowable<File> {
        return Flowable.defer(Callable {
            try {
                notifyCompressStart()
                val succeed = compressAndWrite()
                if (succeed) {
                    notifyCompressSuccess(outFile!!)
                } else {
                    notifyCompressError(Exception("Failed to compress image, either caused by OOM or other problems."))
                }
                return@Callable Flowable.just(outFile)
            } catch (e: Exception) {
                notifyCompressError(e)
                CLog.e(e.message)
                return@Callable Flowable.error<File>(e)
            }
        })
    }

    override fun launch() {
        AsyncTask.SERIAL_EXECUTOR.execute {
            try {
                notifyCompressStart()
                val succeed = compressAndWrite()
                if (succeed) {
                    notifyCompressSuccess(outFile!!)
                } else {
                    notifyCompressError(Exception("Failed to compress image, either caused by OOM or other problems."))
                }
            } catch (e: Exception) {
                notifyCompressError(e)
                CLog.e(e.message)
            }
        }
    }

    /*--------------------------------------- protected methods ------------------------------------------*/

    open fun calculateRequiredWidth(imgRatio: Float, reqRatio: Float): Int {
        var ratio = imgRatio
        when (scaleMode) {
            ScaleMode.SCALE_LARGER -> if (srcHeight > maxHeight || srcWidth > maxWidth) {
                // If Height is greater
                if (ratio < reqRatio) {
                    ratio = maxHeight / srcHeight
                    return (ratio * srcWidth).toInt()
                }  // If Width is greater
                else if (ratio > reqRatio) {
                    return maxWidth.toInt()
                }
            }
            ScaleMode.SCALE_SMALLER -> if (srcHeight > maxHeight || srcWidth > maxWidth) {
                // If Height is greater
                if (ratio < reqRatio) {
                    return maxWidth.toInt()
                }  // If Width is greater
                else if (ratio > reqRatio) {
                    ratio = maxHeight / srcHeight
                    return (ratio * srcWidth).toInt()
                }
            }
            ScaleMode.SCALE_HEIGHT -> return (srcWidth * maxHeight / srcHeight).toInt()
            ScaleMode.SCALE_WIDTH -> return maxWidth.toInt()
            else -> return maxWidth.toInt()
        }
        return maxWidth.toInt()
    }

    open fun calculateRequiredHeight(imgRatio: Float, reqRatio: Float): Int {
        var ratio = imgRatio
        when (scaleMode) {
            ScaleMode.SCALE_LARGER -> if (srcHeight > maxHeight || srcWidth > maxWidth) {
                // If Height is greater
                if (ratio < reqRatio) {
                    return maxHeight.toInt()
                }  // If Width is greater
                else if (ratio > reqRatio) {
                    ratio = maxWidth / srcWidth
                    return (ratio * srcHeight).toInt()
                }
            }
            ScaleMode.SCALE_SMALLER -> if (srcHeight > maxHeight || srcWidth > maxWidth) {
                // If Height is greater
                if (ratio < reqRatio) {
                    ratio = maxWidth / srcWidth
                    return (ratio * srcHeight).toInt()
                }  // If Width is greater
                else if (ratio > reqRatio) {
                    return maxHeight.toInt()
                }
            }
            ScaleMode.SCALE_HEIGHT -> return maxHeight.toInt()
            ScaleMode.SCALE_WIDTH -> {
                ratio = maxWidth / srcWidth
                return (srcHeight * ratio).toInt()
            }
            else -> return maxHeight.toInt()
        }
        return maxHeight.toInt()
    }

    open fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        // Raw height and width of image
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            // Calculate the largest inSampleSize value that is a power of 2 and keeps both
            // height and width larger than the requested height and width.
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    /*--------------------------------------- inner methods ------------------------------------------*/

    /**
     * Compress bitmap and save it to target file.
     *
     * @return             is the full compress logic succeed.
     * @throws IOException io exception
     */
    private fun compressAndWrite(): Boolean {
        val bitmap = compressByScale()
        if (!CImageUtils.isEmptyBitmap(bitmap)) {
            val fos = FileOutputStream(outFile)
            bitmap!!.compress(format, quality, fos)
            fos.flush()
            fos.close()
        } else {
            return false
        }
        return true
    }

    /**
     * Compress by quality, the bitmap will be compressed by scale first.
     *
     * @return the compressed bitmap
     */
    private fun compressByQuality(): Bitmap? {
        val bitmap = compressByScale()
        if (CImageUtils.isEmptyBitmap(bitmap)) return null
        val baos = ByteArrayOutputStream()
        bitmap!!.compress(format, quality, baos)
        val bytes = baos.toByteArray()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    /**
     * Compress the source bitmap by scale.
     *
     * @return the compressed bitmap
     */
    private fun compressByScale(): Bitmap? {
        prepareImageSizeInfo()

        val imgRatio = srcWidth.toFloat() / srcHeight.toFloat()
        val reqRatio = maxWidth / maxHeight

        val reqWidth = calculateRequiredWidth(imgRatio, reqRatio)
        val reqHeight = calculateRequiredHeight(imgRatio, reqRatio)
        var bmp = srcBitmap

        if (srcData != null || srcFile != null) {
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            options.inSampleSize = 1

            if (srcData != null) {
                BitmapFactory.decodeByteArray(srcData, 0, srcData!!.size, options)
            } else {
                BitmapFactory.decodeFile(srcFile!!.absolutePath, options)
            }

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false
            options.inDither = false
            options.inPurgeable = true
            options.inInputShareable = true
            options.inTempStorage = ByteArray(16 * 1024)

            bmp = if (srcData != null) {
                BitmapFactory.decodeByteArray(srcData, 0, srcData!!.size, options)
            } else {
                BitmapFactory.decodeFile(srcFile!!.absolutePath, options)
            }
        }

        var scaledBitmap: Bitmap? = Bitmap.createBitmap(reqWidth, reqHeight, config?:Bitmap.Config.ARGB_8888)

        // return null if OOM.
        if (bmp == null || scaledBitmap == null) {
            return null
        }

        // scale the bitmap.
        val ratioX = reqWidth / bmp.width.toFloat()
        val ratioY = reqHeight / bmp.height.toFloat()
        val middleX = reqWidth / 2.0f
        val middleY = reqHeight / 2.0f

        val scaleMatrix = Matrix()
        scaleMatrix.setScale(ratioX, ratioY, middleX, middleY)

        val canvas = Canvas(scaledBitmap)
        canvas.matrix = scaleMatrix
        canvas.drawBitmap(
            bmp, middleX - bmp.width / 2,
            middleY - bmp.height / 2, Paint(Paint.FILTER_BITMAP_FLAG)
        )

        // recycle the source bitmap automatically when:
        // 1. the source bitmap is null, the bmp is derived from srdData and srcFile
        // 2. the autoRecycle is true
        if (srcBitmap == null || autoRecycle) {
            bmp.recycle()
        }

        if (srcFile != null) {
            val orientation = CImageUtils.getImageAngle(srcFile!!)
            if (orientation != 0) {
                scaledBitmap = CImageUtils.rotateBitmap(scaledBitmap, CImageUtils.getImageAngle(srcFile!!))
            }
        }

        return scaledBitmap
    }
}
