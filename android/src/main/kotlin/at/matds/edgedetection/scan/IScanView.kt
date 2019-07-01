package at.matds.edgedetection.scan

import android.view.Display
import android.view.SurfaceView
import at.matds.edgedetection.view.PaperRectangle

interface IScanView {
    interface Proxy {
        fun exit()
        fun getDisplay(): Display
        fun getSurfaceView(): SurfaceView
        fun getPaperRect(): PaperRectangle
    }
}