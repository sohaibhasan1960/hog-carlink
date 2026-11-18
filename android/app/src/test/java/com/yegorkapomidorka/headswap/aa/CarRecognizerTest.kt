package com.yegorkapomidorka.headswap.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarRecognizerTest {
    @Test
    fun vwIdUnyxByChannelWhateverBtName() =
        assertEquals(CarPreset.VW_ID_UNYX, CarRecognizer.match(CarPrint("VW BT 4370", "VW BT 4370", channel = "20082138")))

    @Test
    fun hondaVezel() = assertEquals(CarPreset.HONDA_VEZEL_2022, CarRecognizer.match(CarPrint(channel = "20832125")))

    @Test
    fun mazdaCx5ByDesayModel() =
        assertEquals(CarPreset.MAZDA_CX5_2025, CarRecognizer.match(CarPrint("Desay SV Automotive", "G6S")))

    @Test
    fun tucsonByBoardBeforeChannel() =
        assertEquals(CarPreset.HYUNDAI_TUCSON_NX4C, CarRecognizer.match(CarPrint("Mobis", "x", board = "CCNC")))

    @Test
    fun elantraByProduct() =
        assertEquals(CarPreset.HYUNDAI_ELANTRA_7, CarRecognizer.match(CarPrint("QTI", "sm6150_au for arm64", product = "wp_cn7cpe_cn")))

    @Test
    fun dongfengByPioneer() = assertEquals(CarPreset.DONGFENG_DF6_2023, CarRecognizer.match(CarPrint("Pioneer", "RM56E8B")))

    @Test
    fun sharedVwCns3ChannelNotGuessed() = assertNull(CarRecognizer.match(CarPrint(channel = "20082122")))

    @Test
    fun unknownCar() = assertNull(CarRecognizer.match(CarPrint("Some HU", "X1", channel = "99999999")))

    @Test
    fun id6HandledSeparately() {
        val id6 = CarPrint("VW BT 5075", "VW BT 5075", channel = "20082128")
        assertTrue(CarRecognizer.isId6(id6))
        assertNull(CarRecognizer.match(id6))
        assertFalse(CarRecognizer.isId6(CarPrint(channel = "20082138")))
    }

    @Test
    fun labelSkipsBlanksAndDuplicates() =
        assertEquals("VW BT 5075 · ch 20082128", CarPrint("VW BT 5075", "VW BT 5075", channel = "20082128").label())
}
