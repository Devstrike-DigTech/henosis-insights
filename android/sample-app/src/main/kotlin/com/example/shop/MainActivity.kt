package com.example.shop

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.acme.payments.AcmePayments

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val label = TextView(this)
        setContentView(label)
        Thread {
            val result = AcmePayments.charge(1_500, "NGN")
            runOnUiThread { label.text = "charge: $result" }
        }.start()
    }
}
