package com.example.foodspoilagedetector.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SensorDataParserTest {
    @Test
    fun testParseBlockFormat() {
        val sampleContent = """
            [00:06:00.286] [Methanethiol (CH3SH)]
            Concentration: 0.01 ppm
            ------------------------------
            [00:06:00.407] [Ammonia (NH3)]
            Concentration: 0.00 ppm
            ------------------------------
            DHT11 #1 Temp: 26.2 °C
            DHT11 #1 Humi: 50 %RH
            ------------------------------
            [00:06:01.528] [Methanethiol (CH3SH)]
            Concentration: 0.02 ppm
        """.trimIndent()
        
        val readings = SensorDataParser.parseDatFile(sampleContent)
        
        // Should have 2 main groups (00:06:00 and 00:06:01)
        assertEquals(2, readings.size)
        
        val first = readings[0]
        assertEquals(0.01f, first.values["Methanethiol (CH3SH)"])
        assertEquals(0.00f, first.values["Ammonia (NH3)"])
        assertEquals(26.2f, first.values["DHT11 #1 Temp"])
        assertEquals(50f, first.values["DHT11 #1 Humi"])
        
        val second = readings[1]
        assertEquals(0.02f, second.values["Methanethiol (CH3SH)"])
    }

    @Test
    fun testParseSimpleFormat() {
        val sampleContent = """
            S1: 10.5, S2: 20.1
            S1: 11.2, S2: 19.8
        """.trimIndent()

        val readings = SensorDataParser.parseDatFile(sampleContent)

        assertEquals(2, readings.size)
        assertEquals(10.5f, readings[0].values["S1"])
        assertEquals(11.2f, readings[1].values["S1"])
    }
}
