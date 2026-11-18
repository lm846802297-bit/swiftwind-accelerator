package com.swiftwind.accelerator

import android.app.Application
import com.swiftwind.accelerator.data.NodeStore
import com.swiftwind.accelerator.vpn.TunnelController

class AcceleratorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NodeStore.init(this)
        TunnelController.init(this)
    }
}
