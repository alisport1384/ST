package com.alisport.goldpin.data

import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Verifies the one-year test dataset is no longer shipped inside the APK. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StorageBundledDataTest {

    @Test
    fun oneYearDataIsNotBundledInApk() {
        val names = RuntimeEnvironment.getApplication().assets.list("").orEmpty().toSet()
        assertFalse("Compressed one-year dataset must stay outside the APK",
            names.contains("goldpin_xauusd_m1_1y.csv.gz"))
        assertFalse("Expanded one-year dataset must stay outside the APK",
            names.contains("goldpin_xauusd_m1_1y.csv"))
    }
}
