package com.dji.videostreamdecodingsample

//import io.socket.client.Socket


//import java.io.BufferedOutputStream
//import java.net.InetSocketAddress
//import java.net.Socket
import org.opencv.imgcodecs.Imgcodecs
import android.app.Activity
import android.graphics.SurfaceTexture
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.dji.videostreamdecodingsample.media.DJIVideoStreamDecoder
import com.dji.videostreamdecodingsample.media.NativeHelper
import dji.common.camera.SettingsDefinitions
import dji.common.error.DJIError
import dji.common.flightcontroller.FlightControllerState
import dji.common.flightcontroller.virtualstick.*
import dji.common.gimbal.Rotation
import dji.common.gimbal.RotationMode
import dji.common.product.Model
import dji.common.util.CommonCallbacks
import dji.sdk.base.BaseProduct
import dji.sdk.camera.Camera
import dji.common.mission.waypoint.*
import dji.sdk.mission.waypoint.WaypointMissionOperator
import dji.sdk.mission.waypoint.WaypointMissionOperatorListener
import dji.sdk.camera.VideoFeeder
import dji.sdk.mission.MissionControl
import dji.sdk.codec.DJICodecManager
import dji.sdk.flightcontroller.FlightController
import dji.sdk.flightcontroller.FlightAssistant
import dji.sdk.products.Aircraft
import dji.sdk.sdkmanager.DJISDKManager
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.ArucoDetector
import org.opencv.objdetect.DetectorParameters
import org.opencv.objdetect.Objdetect
import java.nio.ByteBuffer
import kotlin.math.abs
import io.crossbar.autobahn.wamp.exceptions.*
import org.opencv.core.CvType
import org.opencv.core.Mat

import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

import org.opencv.core.MatOfByte

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.BufferedOutputStream

import org.opencv.core.MatOfInt




fun main() {
    val number = -5
    val absoluteValue = abs(number)
    println("The absolute value of $number is $absoluteValue")
}


class MainActivity : Activity(), DJICodecManager.YuvDataCallback {


    private var flightController: FlightController? = null
    private var flightControllerState: FlightControllerState? = null
    private var waypointMissionBuilder: WaypointMission.Builder? = null
    private var surfaceCallback: SurfaceHolder.Callback? = null

    private enum class DemoType {
        USE_TEXTURE_VIEW, USE_SURFACE_VIEW, USE_SURFACE_VIEW_DEMO_DECODER
    }

    private var standardVideoFeeder: VideoFeeder.VideoFeed? = null
    private var mReceivedVideoDataListener: VideoFeeder.VideoDataListener? = null
    private var titleTv: TextView? = null
    private var mainHandler: Handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_WHAT_SHOW_TOAST -> Toast.makeText(
                    applicationContext, msg.obj as String, Toast.LENGTH_SHORT
                ).show()
                MSG_WHAT_UPDATE_TITLE -> if (titleTv != null) {
                    titleTv!!.text = msg.obj as String
                }
                else -> {}
            }
        }
    }
    private var videostreamPreviewTtView: TextureView? = null
    private var videostreamPreviewSf: SurfaceView? = null
    private var videostreamPreviewSh: SurfaceHolder? = null
    private var mCamera: Camera? = null
    private var mCodecManager: DJICodecManager? = null
    private var savePath: TextView? = null
    private var screenShot: Button? = null
    private var stringBuilder: StringBuilder? = null
    private var videoViewWidth = 0
    private var videoViewHeight = 0
    private var count = 0
    private var count2 = 0
    private var prevtime = System.currentTimeMillis()
    private var curtime = System.currentTimeMillis()
    private var doneProcessing: Boolean = true
    private var points = Mat()
    private var rgbMat = Mat()
    private var yuvMat = Mat(1088 + 1088 / 2, 1632, CvType.CV_8UC1) //height = 1088, width = 1632
    private var ids = Mat()
    var product: BaseProduct? = null
    private var takeoff: Boolean = false
    private var gimbaldown: Boolean = false
    private var dPitch = 0.0f
    private var dRoll = 0.0f
    private var dYaw= 0.0f
    private var dThrottle = 0.0f
    private var markerSizeInMeters = 0.1 // Example: Marker size is 10 cm (0.1 meters)
    private var focalLengthInPixels = 1320.0 // Example: Focal length of the camera in pixels
    private var gimbalPitchAngle = -90.0f
    private var droneHeight = 0.0f
    private var velocityRoll = 0.0f
    private val url = "ws://24.116.171.128:8080/ws"
    private val realm = "realm1"
    private val topic = "com.myapp.images"
    private var r1Value = 0f
    private var r2Value = 0f
    private var r3Value = 0f
    private var t1Value = 0f
    private var t2Value = 0f
    private var t3Value = 0f
    private var PrecisionLanding = false



    private lateinit var myImageView: ImageView


    override fun onResume() {
        super.onResume()
        initSurfaceOrTextureView()
        notifyStatusChange()
    }

    private fun initSurfaceOrTextureView() {
        when (demoType) {
            DemoType.USE_SURFACE_VIEW -> initPreviewerSurfaceView()
            DemoType.USE_SURFACE_VIEW_DEMO_DECODER -> {
                /**
                 * we also need init the textureView because the pre-transcoded video steam will display in the textureView
                 */
                initPreviewerTextureView()
                /**
                 * we use standardVideoFeeder to pass the transcoded video data to DJIVideoStreamDecoder, and then display it
                 * on surfaceView
                 */
                initPreviewerSurfaceView()
            }
            DemoType.USE_TEXTURE_VIEW -> initPreviewerTextureView()
            else -> {}
        }
    }

    override fun onPause() {
        if (mCamera != null) {
            VideoFeeder.getInstance().primaryVideoFeed
                .removeVideoDataListener(mReceivedVideoDataListener)
            standardVideoFeeder?.removeVideoDataListener(mReceivedVideoDataListener)
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (mCodecManager != null) {
            mCodecManager!!.cleanSurface()
            mCodecManager!!.destroyCodec()
        }
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initUi()

        connectToServer()


        val aircraft: Aircraft? = DJISDKManager.getInstance().product as? Aircraft
        if (aircraft != null) {

            // Get the flight controller instance
            flightController = aircraft.flightController
            if (flightController != null) {

                flightController!!.setVerticalControlMode(VerticalControlMode.POSITION)
                flightController!!.setRollPitchControlMode(RollPitchControlMode.VELOCITY)
                flightController!!.setYawControlMode(YawControlMode.ANGULAR_VELOCITY)
                flightController!!.setRollPitchCoordinateSystem(FlightCoordinateSystem.BODY)
                flightController!!.setVirtualStickModeEnabled(true, null)

            } else {
                showToast("Flight controller not available")
            }
        } else {
            showToast("Aircraft not available")
        }
        val gimbal = aircraft!!.gimbal
        if (gimbal != null) {
            val rotation = Rotation.Builder()
                .pitch(gimbalPitchAngle)
                .mode(RotationMode.ABSOLUTE_ANGLE) // Use ABSOLUTE_ANGLE mode for precise angle control.
                .build()
            gimbal.rotate(rotation,null)
            gimbaldown = true

        }

        flightControllerState = flightController?.state


        val flightAssistant = aircraft.flightController.flightAssistant

        // Enable precision landing
        flightAssistant?.setPrecisionLandingEnabled(true, null)
        flightAssistant?.setVisionAssistedPositioningEnabled(true,null)

        if (flightControllerState != null && gimbal != null  && flightController != null && flightAssistant != null) {
            showToast("Flight Controller, Gimbal-90, flightControllerState, flightAssistant")

        }


    }

    private fun showToast(s: String) {
        mainHandler.sendMessage(
            mainHandler.obtainMessage(MSG_WHAT_SHOW_TOAST, s)
        )
    }

    private fun updateTitle(s: String) {
        mainHandler.sendMessage(
            mainHandler.obtainMessage(MSG_WHAT_UPDATE_TITLE, s)
        )
    }

    private fun initUi() {
        savePath = findViewById<View>(R.id.activity_main_save_path) as TextView
        screenShot = findViewById<View>(R.id.activity_main_screen_shot) as Button
        screenShot!!.isSelected = false
        titleTv = findViewById<View>(R.id.title_tv) as TextView
        videostreamPreviewTtView = findViewById<View>(R.id.livestream_preview_ttv) as TextureView
        videostreamPreviewSf = findViewById<View>(R.id.livestream_preview_sf) as SurfaceView
        videostreamPreviewSf!!.isClickable = true
        videostreamPreviewSf!!.setOnClickListener {
            //Do things on screen click


            /*
            //Precision Takeoff
            flightController!!.startPrecisionTakeoff(object : CommonCallbacks.CompletionCallback<DJIError> {
                override fun onResult(djiError: DJIError?) {
                    if (djiError == null) {
                        showToast("Precision Takeoff!")

                    } else {
                        showToast("Precision Takeoff failed: $djiError")
                    }
                }
            })
            showToast("Sleep 5 Seconds!")
            Thread.sleep((5 * 1000).toLong()) // Sleep 5 seconds

            //Move up to 12m
            flightController?.sendVirtualStickFlightControlData(FlightControlData(0.0f, 0.0f, 0.0f, 12.0f), object : CommonCallbacks.CompletionCallback<DJIError> {
                override fun onResult(djiError: DJIError?) {
                    if (djiError == null) {
                        showToast("12m high!")
                    } else {
                        showToast("12m high failed: $djiError")
                    }
                }
            })
            showToast("Sleep 5 Seconds!")
            Thread.sleep((5 * 1000).toLong()) // Sleep 5 seconds



            //Move forward ~15 meters
            // Calculate the time required to move 15 meters at the desired velocity
            val forwardVelocity = 3.0 // Adjust as needed
            val distanceToMove = 15.0 // 15 meters
            val durationInSeconds = distanceToMove / forwardVelocity
            flightController?.sendVirtualStickFlightControlData(FlightControlData(forwardVelocity.toFloat(), 0.0f, 0.0f, 12.0f), object : CommonCallbacks.CompletionCallback<DJIError> {
                override fun onResult(djiError: DJIError?) {
                    if (djiError == null) {
                        showToast("12m high!")
                    } else {
                        showToast("12m high failed: $djiError")
                    }
                }
            })
            // Delay for the calculated duration to allow the drone to move forward
            Thread.sleep((durationInSeconds * 1000).toLong()) // Convert seconds to milliseconds
            flightController?.sendVirtualStickFlightControlData(FlightControlData(0.0f, 0.0f, 0.0f, 12.0f), null)
            showToast("Stopping!")
            Thread.sleep((3 * 1000).toLong())
            showToast("Going Home!")


            //Go Home
            flightController?.startGoHome(null)
            Thread.sleep((60 * 1000).toLong())
            */

















        }


        updateUIVisibility()

    }


    private fun takeoff() {
        flightController?.startTakeoff(object : CommonCallbacks.CompletionCallback<DJIError> {
            override fun onResult(djiError: DJIError?) {
                if (djiError == null) {
                    showToast("Aircraft has taken off!")
                    takeoff=true
                    count=0
                } else {
                    showToast("Takeoff failed: $djiError")
                }
            }
        })

    }
    private fun land() {
        takeoff=false
        flightController?.startLanding(object : CommonCallbacks.CompletionCallback<DJIError> {
            override fun onResult(djiError: DJIError?) {
                if (djiError == null) {
                    showToast("Aircraft is landing!")
                } else {
                    showToast("Landing failed: $djiError")
                }
            }
        })

    }



    private fun updateUIVisibility() {
        when (demoType) {
            DemoType.USE_SURFACE_VIEW -> {
                videostreamPreviewSf!!.visibility = View.VISIBLE
                videostreamPreviewTtView!!.visibility = View.GONE
            }
            DemoType.USE_SURFACE_VIEW_DEMO_DECODER -> {
                /**
                 * we need display two video stream at the same time, so we need let them to be visible.
                 */
                videostreamPreviewSf!!.visibility = View.VISIBLE
                videostreamPreviewTtView!!.visibility = View.VISIBLE
            }
            DemoType.USE_TEXTURE_VIEW -> {
                videostreamPreviewSf!!.visibility = View.GONE
                videostreamPreviewTtView!!.visibility = View.VISIBLE
            }
            else -> {}
        }
    }

    private var lastupdate: Long = 0
    private fun notifyStatusChange() {
        val product: BaseProduct? = VideoDecodingApplication.productInstance
        Log.d(
            TAG,
            "notifyStatusChange: " + when {
                product == null -> "Disconnect"
                product.model == null -> "null model"
                else -> product.model.name
            }
        )
        if (product != null) {
            if (product.isConnected && product.model != null) {
                updateTitle(product.model.name + " Connected " + demoType?.name)
            } else {
                updateTitle("Disconnected")
            }
        }

        // The callback for receiving the raw H264 video data for camera live view
        mReceivedVideoDataListener =
            VideoFeeder.VideoDataListener { videoBuffer, size ->
                if (System.currentTimeMillis() - lastupdate > 1000) {
                    Log.d(
                        TAG,
                        "camera recv video data size: $size"
                    )
                    lastupdate = System.currentTimeMillis()
                }
                // val image = mCodecManager?.getBitmap(applicationContext)


                when (demoType) {
                    DemoType.USE_SURFACE_VIEW -> mCodecManager?.sendDataToDecoder(videoBuffer, size)
                    DemoType.USE_SURFACE_VIEW_DEMO_DECODER ->
                        /**
                         * we use standardVideoFeeder to pass the transcoded video data to DJIVideoStreamDecoder, and then display it
                         * on surfaceView
                         */
                        /**
                         * we use standardVideoFeeder to pass the transcoded video data to DJIVideoStreamDecoder, and then display it
                         * on surfaceView
                         */
                        DJIVideoStreamDecoder.instance?.parse(videoBuffer, size)
                    DemoType.USE_TEXTURE_VIEW -> mCodecManager?.sendDataToDecoder(videoBuffer, size)
                    else -> {}
                }
            }
        if (product != null) {
            if (!product.isConnected) {
                mCamera = null
                showToast("Disconnected")
            } else {
                if (!product.model.equals(Model.UNKNOWN_AIRCRAFT)) {
                    mCamera = product.camera
                    if (mCamera != null) {
                        if (mCamera!!.isFlatCameraModeSupported) {
                            mCamera!!.setFlatMode(
                                SettingsDefinitions.FlatCameraMode.PHOTO_SINGLE
                            ) { djiError: DJIError? ->
                                if (djiError != null) {
                                    showToast("can't change flat mode of camera, error:" + djiError.description)
                                }
                            }
                        } else {
                            mCamera!!.setMode(
                                SettingsDefinitions.CameraMode.SHOOT_PHOTO
                            ) { djiError: DJIError? ->
                                if (djiError != null) {
                                    showToast("can't change mode of camera, error:" + djiError.description)
                                }
                            }
                        }
                    }

                    //When calibration is needed or the fetch key frame is required by SDK, should use the provideTranscodedVideoFeed
                    //to receive the transcoded video feed from main camera.
                    if (demoType == DemoType.USE_SURFACE_VIEW_DEMO_DECODER && isTranscodedVideoFeedNeeded) {
                        standardVideoFeeder = VideoFeeder.getInstance().provideTranscodedVideoFeed()
                        standardVideoFeeder!!.addVideoDataListener(mReceivedVideoDataListener!!)
                        return
                    }
                    VideoFeeder.getInstance().primaryVideoFeed
                        .addVideoDataListener(mReceivedVideoDataListener!!)
                }
            }
        }
    }

    /**
     * Init a fake texture view to for the codec manager, so that the video raw data can be received
     * by the camera
     */
    private fun initPreviewerTextureView() {
        videostreamPreviewTtView!!.surfaceTextureListener = object :
            TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                Log.d(TAG, "real onSurfaceTextureAvailable")
                videoViewWidth = width
                videoViewHeight = height
                Log.d(
                    TAG,
                    "real onSurfaceTextureAvailable: width $videoViewWidth height $videoViewHeight"
                )
                if (mCodecManager == null) {
                    mCodecManager = DJICodecManager(applicationContext, surface, width, height)
                    //For M300RTK, you need to actively request an I frame.
                    mCodecManager!!.resetKeyFrame()
                }
            }

            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                videoViewWidth = width
                videoViewHeight = height
                Log.d(
                    TAG,
                    "real onSurfaceTextureAvailable2: width $videoViewWidth height $videoViewHeight"
                )
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                mCodecManager?.cleanSurface()
                return false
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
    }

    /**
     * Init a surface view for the DJIVideoStreamDecoder
     */
    private fun initPreviewerSurfaceView() {
        videostreamPreviewSh = videostreamPreviewSf!!.holder
        surfaceCallback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.d(TAG, "real onSurfaceTextureAvailable")
                videoViewWidth = videostreamPreviewSf!!.width
                videoViewHeight = videostreamPreviewSf!!.height
                Log.d(
                    TAG,
                    "real onSurfaceTextureAvailable3: width $videoViewWidth height $videoViewHeight"
                )
                when (demoType) {
                    DemoType.USE_SURFACE_VIEW -> if (mCodecManager == null) {
                        mCodecManager = DJICodecManager(
                            applicationContext, holder, videoViewWidth,
                            videoViewHeight
                        )
                    }
                    DemoType.USE_SURFACE_VIEW_DEMO_DECODER -> {
                        // This demo might not work well on P3C and OSMO.
                        NativeHelper.instance?.init()
                        DJIVideoStreamDecoder.instance?.init(applicationContext, holder.surface)
                        DJIVideoStreamDecoder.instance?.resume()
                    }
                    else -> {}
                }
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) {
                videoViewWidth = width
                videoViewHeight = height
                Log.d(
                    TAG,
                    "real onSurfaceTextureAvailable4: width $videoViewWidth height $videoViewHeight"
                )
                when (demoType) {
                    DemoType.USE_SURFACE_VIEW -> {}
                    DemoType.USE_SURFACE_VIEW_DEMO_DECODER -> DJIVideoStreamDecoder.instance
                        ?.changeSurface(holder.surface)
                    else -> {}
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                when (demoType) {
                    DemoType.USE_SURFACE_VIEW -> if (mCodecManager != null) {
                        mCodecManager!!.cleanSurface()
                        mCodecManager!!.destroyCodec()
                        mCodecManager = null
                    }
                    DemoType.USE_SURFACE_VIEW_DEMO_DECODER -> {
                        DJIVideoStreamDecoder.instance?.stop()
                        NativeHelper.instance?.release()
                    }
                    else -> {}
                }
            }
        }
        videostreamPreviewSh!!.addCallback(surfaceCallback)
    }

    override fun onYuvDataReceived(format: MediaFormat, yuvFrame: ByteBuffer?, dataSize: Int, width: Int, height: Int) {

        if ( doneProcessing && yuvFrame != null) {

            //Increase count of iterations and set doneProcessing to false so the code waits until processing is done to start another
            doneProcessing = false
            count++

            //Get latest parameters from the server
            //yaw = receiveResponseFromServer() ?: 0.0f

            sendFrameToServer(rgbMat)


            /*
            //Fill bytes with YUV data
            val bytes = ByteArray(dataSize)
            yuvFrame[bytes]

            //Put the YUV data in an OpenCV mat
            yuvMat.put(0, 0, bytes)

            // Convert the YUV data to RGB data
            Imgproc.cvtColor(yuvMat, rgbMat, Imgproc.COLOR_YUV2RGB_I420)





            //Show toast of variables of interest
            //curtime = System.currentTimeMillis()
            // if (count % 39 == 0) {
            // showToast("Time (ms): ".plus(curtime.minus(prevtime)).plus(" Yaw: ").plus(yaw).plus(" count: ").plus(count) )
            //runOnUiThread { displayPath("Time (ms): ".plus(curtime.minus(prevtime)).plus(" Yaw: ").plus(yaw).plus(" count: ").plus(count)) }
            // }
            //prevtime = System.currentTimeMillis()
            // if (count == 100) {
            //   connect2("ws://184.155.86.32:8080/ws", "realm1");
            //}


            val corners: List<Mat> = ArrayList()
            val ids = Mat()
            val dictionary = Objdetect.getPredefinedDictionary(0) // 0 = 4x4 markers
            val parameters = DetectorParameters() //Can alter thresholding value, expected marker size in pixels
            val arucoDetector = ArucoDetector(dictionary, parameters)

            //Detect Aruco Markers in the image
            arucoDetector.detectMarkers(rgbMat, corners, ids)

            val rvec = Mat()
            val tvec = Mat()
            val distCoeffs = MatOfDouble()

            if (ids.total() > 0 && ids.total() < 2 && ids.get(0,0)[0].toFloat() == 0.0f )  {      //Aruco Found //change if id includes ID 0

                if (count%10==0){runOnUiThread { displayPath("Detected marker") }}
                val detectedcorners = corners[0]

                val cameraMatrix = Mat(3, 3, CvType.CV_64F)
                cameraMatrix.put(0, 0, focalLengthInPixels)
                cameraMatrix.put(1, 1, focalLengthInPixels)
                cameraMatrix.put(0, 2, rgbMat.cols() / 2.0)
                cameraMatrix.put(1, 2, rgbMat.rows() / 2.0)

                val objectPointsArr = arrayOfNulls<Point3>(4)
                objectPointsArr[0] = Point3(0.0, 0.0, 0.0)
                objectPointsArr[1] = Point3(markerSizeInMeters, 0.0, 0.0)
                objectPointsArr[2] = Point3(markerSizeInMeters, markerSizeInMeters, 0.0)
                objectPointsArr[3] = Point3(0.0, markerSizeInMeters, 0.0)
                val objectPoints = MatOfPoint3f(*objectPointsArr)

                val imagePointsArr = arrayOfNulls<Point>(4)
                for (i in 0..3) {
                    val value: DoubleArray = detectedcorners.get(0, i)
                    imagePointsArr[i] = Point(value[0], value[1])
                }
                val imagePoints = MatOfPoint2f()
                imagePoints.fromArray(*imagePointsArr)



                // SolvePnP to calculate the rotation and translation vectors
                try {
                    Calib3d.solvePnP(
                        objectPoints,
                        imagePoints,
                        cameraMatrix,
                        distCoeffs,
                        rvec,
                        tvec
                    )
                } catch (e: Exception) {
                    // Handle error
                    showToast("Exception: ${e.message}")
                }

                //curtime = System.currentTimeMillis()
                //prevtime = System.currentTimeMillis()




                try {
                    if (rvec != null && tvec != null) {
                        r1Value = rvec.get(0, 0)[0].toFloat()
                        r2Value = rvec.get(1, 0)[0].toFloat()
                        r3Value = rvec.get(2, 0)[0].toFloat()

                        val message1 = "R (rad):${"%.2f".format(r1Value)} , ${"%.2f".format(r2Value)} , ${"%.2f".format(r3Value)}"
                        //runOnUiThread { displayPath(message1) }

                        t1Value = tvec.get(0, 0)[0].toFloat()
                        t2Value = tvec.get(1, 0)[0].toFloat()
                        t3Value = tvec.get(2, 0)[0].toFloat()

                        val message2 = "T (m):${"%.2f".format(t1Value)} , ${"%.2f".format(t2Value)} , ${"%.2f".format(t3Value)}"
                        //runOnUiThread { displayPath(message2) }

                        //runOnUiThread { displayPath("Time (ms): ".plus(curtime.minus(prevtime))) }
                    } else {
                        showToast("rvec or tvec is null.")
                    }
                } catch (e: Exception) {
                    // Handle error
                    showToast("Exception: ${e.message}")
                }


                if (takeoff && gimbaldown) {

                    val Kp = 0.1f
                    val Kd = -1.0f

                    var velocityPitch = flightControllerState?.velocityX ?: 0.0f
                    velocityRoll = flightControllerState?.velocityY ?: 0.0f
                    droneHeight = flightControllerState?.ultrasonicHeightInMeters ?: 0.0f
                    dThrottle = -0.05f

                    dRoll = Kp*t1Value + Kd*velocityRoll
                    dPitch = -1*(Kp*t2Value + Kd*velocityPitch)

                    if(abs(t1Value) > 0.03 || abs(t2Value) > 0.03 || (droneHeight?:0.0f) > 1.3f) { //  || (droneHeight?:0.0f) > 1.2f
                        flightController!!.sendVirtualStickFlightControlData(FlightControlData(dRoll, dPitch, dYaw, dThrottle), null)

                        if(count2++%5==0){
                            //val message = "dRoll: ${"%.2f".format(dRoll)} dPitch: ${"%.2f".format(dPitch)} droneHeight: ${"%.2f".format(droneHeight)} t1Value: ${"%.2f".format(t1Value)} t2Value: ${"%.2f".format(t2Value)} t3Value: ${"%.2f".format(t3Value)}"
                            val message = "velocityPitch: ${"%.6f".format(velocityPitch)} velocityRoll: ${"%.6f".format(velocityRoll)}"
                            runOnUiThread { displayPath(message) }}
                    }else{
                        flightController!!.sendVirtualStickFlightControlData(FlightControlData(0.0f, 0.0f, 0.0f, 0.0f), null)
                        land()
                    }


                }


            }
            */
            //Detection is done. Ready to start another.
            doneProcessing = true

        }
    }


    fun onClick(v: View) {
        if (v.id == R.id.activity_main_screen_shot) {
            handleYUVClick()
        } else {
            var newDemoType: DemoType? = null
            if (v.id == R.id.activity_main_screen_texture) {
                newDemoType = DemoType.USE_TEXTURE_VIEW
            } else if (v.id == R.id.activity_main_screen_surface) {
                newDemoType = DemoType.USE_SURFACE_VIEW
            } else if (v.id == R.id.activity_main_screen_surface_with_own_decoder) {
                newDemoType = DemoType.USE_SURFACE_VIEW_DEMO_DECODER
            }
            if (newDemoType != null && newDemoType != demoType) {
                // Although finish will trigger onDestroy() is called, but it is not called before OnCreate of new activity.
                if (mCodecManager != null) {
                    mCodecManager!!.cleanSurface()
                    mCodecManager!!.destroyCodec()
                    mCodecManager = null
                }
                demoType = newDemoType
                finish()
                overridePendingTransition(0, 0)
                startActivity(intent)
                overridePendingTransition(0, 0)
            }
        }
    }

    private fun handleYUVClick() {

        if (screenShot!!.isSelected) {
            screenShot!!.text = "YUV Screen Shot"
            screenShot!!.isSelected = false
            when (demoType) {
                DemoType.USE_SURFACE_VIEW, DemoType.USE_TEXTURE_VIEW -> {
                    mCodecManager?.enabledYuvData(false)
                    mCodecManager?.yuvDataCallback = null
                }
                DemoType.USE_SURFACE_VIEW_DEMO_DECODER -> {
                    DJIVideoStreamDecoder.instance
                        ?.changeSurface(videostreamPreviewSh!!.surface)
                    DJIVideoStreamDecoder.instance?.setYuvDataListener(null)
                }
                else -> {}
            }
            savePath!!.text = ""
            savePath!!.visibility = View.INVISIBLE
            stringBuilder = null
        } else {
            screenShot!!.text = "Live Stream"
            screenShot!!.isSelected = true
            when (demoType) {
                DemoType.USE_TEXTURE_VIEW, DemoType.USE_SURFACE_VIEW -> {
                    mCodecManager?.enabledYuvData(true)
                    mCodecManager?.yuvDataCallback = this
                }
                DemoType.USE_SURFACE_VIEW_DEMO_DECODER -> {
                    DJIVideoStreamDecoder.instance?.changeSurface(null)
                    DJIVideoStreamDecoder.instance?.setYuvDataListener(this@MainActivity)
                }
                else -> {}
            }
            savePath!!.text = ""
            savePath!!.visibility = View.VISIBLE
        }
    }

    private fun displayPath(_path: String) {
        var path = _path
        if (stringBuilder == null) {
            stringBuilder = StringBuilder()
        }
        path = """
            $path
           
            """.trimIndent()
        stringBuilder!!.append(path)
        savePath!!.text = stringBuilder.toString()
    }

    private val isTranscodedVideoFeedNeeded: Boolean
        get() = if (VideoFeeder.getInstance() == null) {
            false
        } else VideoFeeder.getInstance().isFetchKeyFrameNeeded || VideoFeeder.getInstance()
            .isLensDistortionCalibrationNeeded

    companion object {
        private val TAG = MainActivity::class.java.simpleName
        private const val MSG_WHAT_SHOW_TOAST = 0
        private const val MSG_WHAT_UPDATE_TITLE = 1
        private var demoType: DemoType? = DemoType.USE_TEXTURE_VIEW
        val isM300Product: Boolean
            get() {
                if (DJISDKManager.getInstance().product == null) {
                    return false
                }
                val model: Model = DJISDKManager.getInstance().product.model
                return model === Model.MATRICE_300_RTK
            }
    }

    private var socket: Socket? = null // For connection to Server

    private fun connectToServer() {
        Thread {
            val host = "184.155.92.251" // replace with your server IP address
            val port = 9999 // replace with your server port
            val timeout = 15000 // 15 seconds

            try {

                // Connect to server
               // socket = Socket(host, port)

                // Connect to server with timeout
                val address = InetSocketAddress(host, port)
                val tempSocket = Socket()
                tempSocket.connect(address, timeout)

                // Send message to server

                //val outputStream = OutputStreamWriter(tempSocket.getOutputStream())
                //outputStream.write("Hello, server!")
                //outputStream.flush()
                showToast("Connected to Server: $host : $port")

                // Assign the socket to the class property
                socket = tempSocket

            } catch (e: Exception) {
                // Handle connection error
                showToast("Connection failed: ${e.message}")
            }
        }.start()
    }
    private fun receiveResponseFromServer(): Float? { // "?" means the returned value may sometimes be "Null"
        // Make sure the socket is initialized and connected before attempting to receive data
        if (socket != null && socket!!.isConnected) {
            try {
                // Receive response from server
                val inputStream = socket!!.getInputStream()
                val buffer = ByteArray(1024)
                val length = inputStream.read(buffer)

                if (length != -1) {
                    val response = buffer.copyOf(length).decodeToString()
                    val yawStr = response.substringAfter(":").substringBefore(";").trim() // Extract the yaw angle string between ":" and ";"
                    return yawStr.toFloat() // Convert the yaw angle string to a float
                }

            } catch (e: Exception) {
                // Handle error
                showToast("Error receiving response from server: ${e.message}")
            }
        } else {
            showToast("Socket is not connected")
        }
        return null
    }

    private fun sendFrameToServer(mat: Mat) {
        if (socket != null && socket!!.isConnected) {
            val outputStream = socket?.getOutputStream()
            if (outputStream != null) {
                val buffer = MatOfByte()
                val compressionParams = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 50)
                Imgcodecs.imencode(".jpg", mat, buffer, compressionParams)
                val bytes = buffer.toArray()
                showToast("Number of bytes in the image: ${bytes.size}")

                // Send the size of the image data first
                val sizeBytes = ByteBuffer.allocate(8).putLong(bytes.size.toLong()).array()
                outputStream.write(sizeBytes)

                // Send the JPEG image over the network using a BufferedOutputStream
                val bos = BufferedOutputStream(outputStream)
                bos.write(bytes)
                bos.flush()

                // Receive acknowledgement from the server
                val ack = ByteArray(3)
                val inputStream = socket?.getInputStream()
                inputStream?.read(ack)
                if (ack.contentEquals("ACK".toByteArray())) {
                    showToast("Image received successfully")
                } else {
                    showToast("Failed to receive image")
                }

            }
        } else {
            showToast("Socket is not connected")
        }
    }















}