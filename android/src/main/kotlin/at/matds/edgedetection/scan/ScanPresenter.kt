package at.matds.edgedetection.scan

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.Camera
import android.media.MediaActionSound
import android.util.Log
import android.view.SurfaceHolder
import android.widget.Toast
import at.matds.edgedetection.SourceManager
import at.matds.edgedetection.crop.CropActivity
import at.matds.edgedetection.processor.Corners
import at.matds.edgedetection.processor.processPicture
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

class ScanPresenter constructor(private val context: Context, private val iView: IScanView.Proxy)
    : SurfaceHolder.Callback, Camera.PictureCallback, Camera.PreviewCallback {
    private val tag: String = "ScanPresenter"
    private var mCamera: Camera? = null
    private val mSurfaceHolder: SurfaceHolder = iView.getSurfaceView().holder
    private val executor: ExecutorService
    private val proxySchedule: Scheduler
    private var busy = false

    private val MAX_ASPECT_DISTORTION = 0.15
    private val ASPECT_RATIO_TOLERANCE = 0.01f

    init {
        mSurfaceHolder.addCallback(this)
        executor = Executors.newSingleThreadExecutor()
        proxySchedule = Schedulers.from(executor)
    }

    fun start() {
        mCamera?.startPreview() ?: Log.i(tag, "camera null")
    }

    fun stop() {
        mCamera?.stopPreview() ?: Log.i(tag, "camera null")
    }

    fun shut() {
        busy = true
        Log.i(tag, "try to focus")
        mCamera?.autoFocus { b, _ ->
            Log.i(tag, "focus result: $b")
            mCamera?.takePicture(null, null, this)
            MediaActionSound().play(MediaActionSound.SHUTTER_CLICK)
        }
    }

    fun updateCamera() {
        if (null == mCamera) {
            return
        }
        mCamera?.stopPreview()
        try {
            mCamera?.setPreviewDisplay(mSurfaceHolder)
        } catch (e: IOException) {
            e.printStackTrace()
            return
        }
        mCamera?.setPreviewCallback(this)
        mCamera?.startPreview()
    }

    fun initCamera() {
        try {
            mCamera = Camera.open(Camera.CameraInfo.CAMERA_FACING_BACK)
        } catch (e: RuntimeException) {
            e.stackTrace
            Toast.makeText(context, "cannot open camera, please grant camera", Toast.LENGTH_SHORT).show()
            return
        }

        val param = mCamera?.parameters
        val size = createSize(getMaxResolution())

        val previewSize = generateValidPreviewSize(mCamera, size.width, size.height)

        param?.setPreviewSize(previewSize?.previewSize?.width ?: 1920, previewSize?.previewSize?.height ?: 1080)

//        val display = iView.getDisplay()
//        val point = Point()
//        display.getRealSize(point)
//        val displayWidth = minOf(point.x, point.y)
//        val displayHeight = maxOf(point.x, point.y)
//        val displayRatio = displayWidth.div(displayHeight.toFloat())
//        val previewRatio = size?.height?.toFloat()?.div(size.width.toFloat()) ?: displayRatio
//        if (displayRatio > previewRatio) {
//            val surfaceParams = iView.getSurfaceView().layoutParams
//            surfaceParams.height = (displayHeight / displayRatio * previewRatio).toInt()
//            iView.getSurfaceView().layoutParams = surfaceParams
//        }
//
//        val supportPicSize = mCamera?.parameters?.supportedPictureSizes
//        supportPicSize?.sortByDescending { it.width.times(it.height) }
//        var pictureSize = supportPicSize?.find { it.height.toFloat().div(it.width.toFloat()) - previewRatio < 0.01 }

//        if (null == pictureSize) {
//            pictureSize = supportPicSize?.get(0)
//        }

        if (null == previewSize) {
            Log.e(tag, "can not get picture size")
        } else {
            param?.setPictureSize(previewSize.pictureSize.width, previewSize.pictureSize.height)
        }
//        val pm = context.packageManager
//        if (pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_AUTOFOCUS)) {
//            param?.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
//            Log.d(tag, "enabling autofocus")
//        } else {
//            Log.d(tag, "autofocus not available")
//        }
//        param?.flashMode = Camera.Parameters.FLASH_MODE_AUTO

        mCamera?.parameters = param
        mCamera?.setDisplayOrientation(90)
    }

    override fun surfaceCreated(p0: SurfaceHolder?) {
        initCamera()
    }

    override fun surfaceChanged(p0: SurfaceHolder?, p1: Int, p2: Int, p3: Int) {
        updateCamera()
    }

    override fun surfaceDestroyed(p0: SurfaceHolder?) {
        synchronized(this) {
            mCamera?.stopPreview()
            mCamera?.setPreviewCallback(null)
            mCamera?.release()
            mCamera = null
        }
    }

    override fun onPictureTaken(p0: ByteArray?, p1: Camera?) {
        Log.i(tag, "on picture taken")
        Observable.just(p0)
                .subscribeOn(proxySchedule)
                .subscribe {
                    val pictureSize = createSize(p1?.parameters?.pictureSize)


                    Log.i(tag, "picture size: " + pictureSize.toString())
                    val mat = Mat(Size(pictureSize.width.toDouble(),
                            pictureSize.height.toDouble()), CvType.CV_8U)
                    mat.put(0, 0, p0)
                    val pic = Imgcodecs.imdecode(mat, Imgcodecs.IMREAD_UNCHANGED)
                    Core.rotate(pic, pic, Core.ROTATE_90_CLOCKWISE)
                    mat.release()
                    SourceManager.corners = processPicture(pic)
                    Imgproc.cvtColor(pic, pic, Imgproc.COLOR_RGB2BGRA)
                    SourceManager.pic = pic
                    context.startActivity(Intent(context, CropActivity::class.java))
                    busy = false
                }
    }

    override fun onPreviewFrame(p0: ByteArray?, p1: Camera?) {
        if (busy) {
            return
        }
        Log.i(tag, "on process start")
        busy = true
        Observable.just(p0)
                .observeOn(proxySchedule)
                .subscribe {
                    Log.i(tag, "start prepare paper")
                    val parameters = p1?.parameters
                    val width = parameters?.previewSize?.width
                    val height = parameters?.previewSize?.height
                    val yuv = YuvImage(p0, parameters?.previewFormat ?: 0, width ?: 1080, height
                            ?: 1920, null)
                    val out = ByteArrayOutputStream()
                    yuv.compressToJpeg(Rect(0, 0, width ?: 1080, height ?: 1920), 100, out)
                    val bytes = out.toByteArray()
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

                    val img = Mat()
                    Utils.bitmapToMat(bitmap, img)
                    bitmap.recycle()
                    Core.rotate(img, img, Core.ROTATE_90_CLOCKWISE)
                    try {
                        out.close()
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }

                    Observable.create<Corners> {
                        val corner = processPicture(img)
                        busy = false
                        if (null != corner) {
                            it.onNext(corner)
                        } else {
                            it.onError(Throwable("paper not detected"))
                        }
                    }.observeOn(AndroidSchedulers.mainThread())
                            .subscribe({
                                iView.getPaperRect().onCornersDetected(it)

                            }, {
                                iView.getPaperRect().onCornersNotDetected()
                            })
                }
    }

    private fun getMaxResolution(): Camera.Size? = mCamera?.parameters?.supportedPreviewSizes?.maxBy { it.width }

    private fun generateValidPreviewSize(camera: Camera?, desiredWidth: Int,
                                         desiredHeight: Int): SizePair? {
        if (camera == null) throw IllegalStateException("Camera cannot be null when selecting a preview Size")

        val parameters = camera.parameters
        val screenAspectRatio =  desiredHeight.toDouble() /  desiredWidth.toDouble()
        val supportedPreviewSizes = parameters.supportedPreviewSizes
        val supportedPictureSizes = parameters.supportedPictureSizes
        var bestPair: SizePair? = null
        var currentMinDistortion = MAX_ASPECT_DISTORTION

        for (previewSize in supportedPreviewSizes) {
            val previewAspectRatio = previewSize.width.toDouble() / previewSize.height
            for (pictureSize in supportedPictureSizes) {
                val pictureAspectRatio = pictureSize.width.toDouble() / pictureSize.height
                if (abs(previewAspectRatio - pictureAspectRatio) < ASPECT_RATIO_TOLERANCE) {
                    val sizePair = SizePair(createSize(previewSize), createSize(pictureSize))

                    val isCandidatePortrait = previewSize.width < previewSize.height
                    val maybeFlippedWidth = if (isCandidatePortrait) previewSize.width else previewSize.height
                    val maybeFlippedHeight = if (isCandidatePortrait) previewSize.height else previewSize.width
                    val aspectRatio = maybeFlippedWidth.toDouble() / maybeFlippedHeight
                    val distortion = abs(aspectRatio - screenAspectRatio)
                    if (distortion < currentMinDistortion) {
                        currentMinDistortion = distortion.toDouble()
                        bestPair = sizePair
                    }
                    break
                }
            }
        }

        return bestPair
    }


    class SizePair(val previewSize: CustomSize, val pictureSize: CustomSize)

    class CustomSize(val width: Int, val height: Int)

    private fun createSize(size: Camera.Size?, default: CustomSize = CustomSize(1920, 1080)): CustomSize =  if (size != null) CustomSize(size.width, size.height) else default
}