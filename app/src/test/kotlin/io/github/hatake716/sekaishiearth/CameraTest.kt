package io.github.hatake716.sekaishiearth

import io.github.hatake716.sekaishiearth.globe.Camera
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CameraTest {
    private fun cam(lat: Double, lon: Double, alt: Double, w: Int = 1080, h: Int = 2400): Camera {
        val c = Camera()
        c.setViewport(w, h)
        c.centerLat = Math.toRadians(lat)
        c.centerLon = Math.toRadians(lon)
        c.altitude = alt
        c.update()
        return c
    }

    @Test
    fun centerProjectsToScreenCenter() {
        for ((lat, lon, alt) in listOf(Triple(35.0, 135.0, 2.0), Triple(-33.9, 18.4, 0.05), Triple(60.0, -150.0, 0.005), Triple(0.0, 0.0, 5.0))) {
            val c = cam(lat, lon, alt)
            val xyz = DoubleArray(3)
            Camera.toXyz(Math.toRadians(lat), Math.toRadians(lon), xyz)
            val out = FloatArray(3)
            assertTrue(c.project(xyz[0], xyz[1], xyz[2], out))
            assertEquals(540f, out[0], 0.5f)
            assertEquals(1200f, out[1], 0.5f)
            assertTrue(c.isFrontFacing(xyz[0], xyz[1], xyz[2]))
        }
    }

    @Test
    fun screenToLatLonRoundTrip() {
        val c = cam(48.86, 2.35, 0.3)
        for (sx in listOf(100f, 540f, 900f)) for (sy in listOf(400f, 1200f, 2000f)) {
            val ll = c.screenToLatLon(sx, sy) ?: continue
            val xyz = DoubleArray(3)
            Camera.toXyz(ll[0], ll[1], xyz)
            val out = FloatArray(3)
            assertTrue(c.project(xyz[0], xyz[1], xyz[2], out))
            assertEquals("sx", sx, out[0], 0.6f)
            assertEquals("sy", sy, out[1], 0.6f)
        }
    }

    @Test
    fun rayMissesGlobeOutsideSilhouette() {
        val c = cam(0.0, 0.0, 5.0)
        assertNull(c.screenToLatLon(0f, 0f))
        assertNotNull(c.screenToLatLon(540f, 1200f))
    }

    @Test
    fun fitAltitudeKeepsGlobeInsideShortSide() {
        val c = cam(0.0, 0.0, 1.0)
        c.altitude = c.fitAltitude()
        c.update()
        val r = c.globeRadiusPx()
        assertTrue("radius $r", r < 540 && r > 540 * 0.8)
        val l = cam(0.0, 0.0, 1.0, 2400, 1080)
        l.altitude = l.fitAltitude()
        l.update()
        val r2 = l.globeRadiusPx()
        assertTrue("radius $r2", r2 < 540 && r2 > 540 * 0.8)
    }

    @Test
    fun pinchKeepsFocalPointFixed() {
        val c = cam(35.0, 135.0, 0.8)
        val fx = 300f
        val fy = 1500f
        val before = c.screenToLatLon(fx, fy)!!
        c.altitude /= 1.7
        c.update()
        for (k in 0 until 3) {
            val after = c.screenToLatLon(fx, fy)!!
            c.centerLat += before[0] - after[0]
            c.centerLon += Camera.wrapLon(before[1] - after[1])
            c.clamp()
            c.update()
        }
        val check = c.screenToLatLon(fx, fy)!!
        val err = Camera.angularDistance(before[0], before[1], check[0], check[1])
        assertTrue("err=$err rpp=${c.radiansPerPixel()}", err < c.radiansPerPixel() * 0.5)
    }

    @Test
    fun wrapLon() {
        assertEquals(0.0, Camera.wrapLon(2 * Math.PI), 1e-12)
        assertTrue(abs(Camera.wrapLon(Math.toRadians(190.0)) - Math.toRadians(-170.0)) < 1e-12)
    }
}
