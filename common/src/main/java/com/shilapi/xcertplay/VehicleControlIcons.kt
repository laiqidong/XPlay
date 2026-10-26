package com.shilapi.xcertplay

import android.content.Context
import android.graphics.drawable.Drawable
import com.shilapi.xcertplay.host.R

/** Matched line icons for custom controls, with OEM artwork for the car and fog lamp. */
internal object VehicleControlIcons {
    private const val OEM_PACKAGE = "com.xiaopeng.carcontrol"
    private var oemResources: android.content.res.Resources? = null

    fun load(context: Context, key: String, resourceOverride: android.content.res.Resources? = null): Drawable? {
        if (key == "vehicle_charge_slow") return context.getDrawable(R.drawable.ic_p7_vehicle_ac)?.mutate()
        if (key == "vehicle_charge_fast") return context.getDrawable(R.drawable.ic_p7_vehicle_dc)?.mutate()
        if (key == "vehicle_flash") return context.getDrawable(R.drawable.ic_p7_vehicle_flash)?.mutate()
        val (name, fallback) = when (key) {
            "vehicle_entry" -> "ic_carcontrol_car_o" to R.drawable.ic_p7_vehicle_control
            "vehicle_fog" -> "ic_carcontrol_rearfoglamp_o" to R.drawable.ic_p7_vehicle_fog
            "vehicle_trunk" -> "ic_carcontrol_trunk_o" to R.drawable.ic_p7_vehicle_trunk
            "vehicle_trunk_close" -> "ic_carcontrol_trunkclose_o" to R.drawable.ic_p7_vehicle_trunk
            else -> return null
        }
        return runCatching {
            val resources = resourceOverride ?: oemResources ?: context.packageManager.getResourcesForApplication(OEM_PACKAGE)
                .also { oemResources = it }
            val id = resources.getIdentifier(name, "drawable", OEM_PACKAGE)
            if (id == 0) null else resources.getDrawable(id, null)?.mutate()
        }.getOrNull() ?: context.getDrawable(fallback)?.mutate()
    }

}
