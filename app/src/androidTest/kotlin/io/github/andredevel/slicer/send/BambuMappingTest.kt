package io.github.andredevel.slicer.send

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BambuMappingTest {
    @Test
    fun amsMappingHasOneItemPerProjectFilamentLikeOrca() {
        val mapping = listOf(
            Slot(0, true, bambuAms = 0, bambuSlot = 2),
            null,
            Slot(1, true, bambuAms = 1, bambuSlot = 3),
            Slot(2, true, bambuAms = 128),
            Slot(3, true, bambuAms = BAMBU_EXTERNAL),
        )
        val (v0, v2) = BambuUploader.amsMapping(mapping)
        assertEquals("[2,-1,7,128,-1]", v0.toString())
        assertEquals(
            """[{"ams_id":0,"slot_id":2},{"ams_id":255,"slot_id":255},{"ams_id":1,"slot_id":3},{"ams_id":128,"slot_id":0},{"ams_id":254,"slot_id":0}]""",
            v2.toString(),
        )
    }

    @Test
    fun trayMappingKeepsTheSingleFilamentStart() {
        assertEquals("[5]", BambuUploader.trayMapping(5).first.toString())
        assertEquals("""[{"ams_id":1,"slot_id":1}]""", BambuUploader.trayMapping(5).second.toString())
        assertEquals("[-1]", BambuUploader.trayMapping(255).first.toString())
    }

    @Test
    fun trayColorDropsAlphaAndTransparent() {
        assertEquals("#FF8A3D", BambuUploader.bambuColor("ff8a3dFF"))
        assertNull(BambuUploader.bambuColor("00000000"))
        assertNull(BambuUploader.bambuColor(""))
    }
}
