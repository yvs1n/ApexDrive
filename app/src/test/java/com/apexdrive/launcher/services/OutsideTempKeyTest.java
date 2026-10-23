package com.apexdrive.launcher.services;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OutsideTempKeyTest {

    @Test
    public void outsideTemperatureNamesMatch() {
        String[] names = {"out_temp", "outside_temperature", "OutTemp", "car_out_temp", "ext.temp",
                "exterior_temp", "ambient_air_temp", "temp_out", "can_temp_outside", "mcu_out_temp",
                "outdoor_tmp", "canbus.ext_temperature"};
        for (String n : names) assertTrue(n, CarThermometerWatcher.isOutsideTempKey(n));
    }

    @Test
    public void otherTemperaturesDoNotMatch() {
        String[] names = {"cpu_temp", "inside_temp", "cabin_temp", "ac_set_temp", "coolant_temp",
                "out_temp_unit", "temp_unit", "temperature", "screen_brightness", "battery_temp",
                "out_temp_show", null};
        for (String n : names) assertFalse(String.valueOf(n), CarThermometerWatcher.isOutsideTempKey(n));
    }
}
