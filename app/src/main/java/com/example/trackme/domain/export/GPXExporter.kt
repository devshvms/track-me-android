package com.example.trackme.domain.export

import android.content.Context
import android.util.Xml
import com.example.trackme.config.AppConfig
import com.example.trackme.data.local.entity.RideWithPoints
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

interface GPXExporter {
    suspend fun export(rideWithPoints: RideWithPoints, context: Context): File
}

class GPXExporterImpl : GPXExporter {
    override suspend fun export(rideWithPoints: RideWithPoints, context: Context): File {
        val exportsDir = File(context.cacheDir, AppConfig.EXPORT_DIR_NAME)
        if (!exportsDir.exists()) exportsDir.mkdirs()

        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        val file = File(exportsDir, "${AppConfig.GPX_FILE_PREFIX}${rideWithPoints.ride.id}.gpx")
        
        FileOutputStream(file).use { fos ->
            val serializer = Xml.newSerializer()
            serializer.setOutput(fos, "UTF-8")
            serializer.startDocument("UTF-8", true)
            
            serializer.startTag("", "gpx")
            serializer.attribute("", "version", "1.1")
            serializer.attribute("", "creator", "TrackMe Android")
            serializer.attribute("", "xmlns", "http://www.topografix.com/GPX/1/1")

            serializer.startTag("", "metadata")
            serializer.startTag("", "time")
            serializer.text(sdf.format(Date(rideWithPoints.ride.startTime)))
            serializer.endTag("", "time")
            serializer.endTag("", "metadata")

            serializer.startTag("", "trk")
            serializer.startTag("", "name")
            serializer.text("Ride ${rideWithPoints.ride.id}")
            serializer.endTag("", "name")
            
            serializer.startTag("", "desc")
            serializer.text("TrackMeID:${rideWithPoints.ride.id}")
            serializer.endTag("", "desc")

            serializer.startTag("", "trkseg")

            for (point in rideWithPoints.points) {
                serializer.startTag("", "trkpt")
                serializer.attribute("", "lat", point.latitude.toString())
                serializer.attribute("", "lon", point.longitude.toString())
                
                serializer.startTag("", "ele")
                serializer.text(point.altitude.toString())
                serializer.endTag("", "ele")

                serializer.startTag("", "time")
                serializer.text(sdf.format(Date(point.timestamp)))
                serializer.endTag("", "time")
                
                serializer.endTag("", "trkpt")
            }

            serializer.endTag("", "trkseg")
            serializer.endTag("", "trk")
            
            serializer.endTag("", "gpx")
            serializer.endDocument()
        }

        return file
    }
}
